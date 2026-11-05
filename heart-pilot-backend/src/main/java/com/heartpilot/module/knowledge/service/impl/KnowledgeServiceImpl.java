package com.heartpilot.module.knowledge.service.impl;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.service.RedisResultCacheService;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.module.knowledge.entity.KnowledgeChunk;
import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeReviewStatus;
import com.heartpilot.module.knowledge.repository.KnowledgeChunkRepository;
import com.heartpilot.module.knowledge.repository.KnowledgeDocumentRepository;
import com.heartpilot.module.knowledge.service.KnowledgeMarkdownParser;
import com.heartpilot.module.knowledge.service.KnowledgeQueryPlanner;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import com.heartpilot.module.knowledge.service.KnowledgeTaxonomy;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库（RAG）核心服务实现。 完整流程：管理员上传文件 → Tika 抽取纯文本 → 清洗 → 按 1200 字/120 字重叠切片 → 每个切片写入 knowledge_chunk 表并通过
 * Spring AI VectorStore（PGVector）做向量相似度检索。
 *
 * <p>设计要点： - 上传后同步处理：先存对象存储落库文档记录，再解析切片；任一步失败把文档状态置为 FAILED - 切片向量化采用批量写入，中途失败时回滚已写入的 PGVector
 * 向量和已保存的 chunk，避免脏数据 - 检索走三级策略：Redis 缓存命中 → PGVector 向量相似度（阈值 0.58）→ 关键词包含兜底 - AI（DashScope
 * Embedding）未配置或向量检索异常时降级为全表关键词扫描，保证检索可用 - 删除文档时先按 vectorId 清理 PGVector，再删 chunk
 * 表、对象存储文件、文档元数据；存储删除失败不阻断元数据删除
 */
@Service
public class KnowledgeServiceImpl implements KnowledgeService {
    /** 允许上传的 MIME 类型白名单，octet-stream 兜底给无类型的 Markdown/TXT */
    private static final Set<String> SUPPORTED_TYPES =
            Set.of(
                    "text/plain",
                    "text/markdown",
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/octet-stream");

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final StorageService storage;

    /** PGVector 向量存储，Bean 名 relationshipVectorStore，由 Spring AI 自动配置 */
    private final VectorStore vectors;

    private final MeterRegistry metrics;
    private final RedisResultCacheService cache;

    /** 检索前的查询改写与分类/场景路由，用于缓存键组装与结果过滤 */
    private final KnowledgeQueryPlanner queryPlanner;

    /** Markdown 文档解析器，全量重建知识库时按标题切分章节与元数据 */
    private final KnowledgeMarkdownParser markdownParser;

    /** 向量相似度阈值，低于该值的弱相关结果被过滤 */
    private final double similarityThreshold;

    /** 是否启用 AI Embedding：DashScope key 未配置时为 false，检索降级为关键词匹配 */
    private final boolean aiEnabled;

    /** Apache Tika 统一抽取 PDF/Word/TXT/Markdown 文本 的工具包*/
    private final Tika tika = new Tika();

    /**
     * 构造器注入。 vectors 通过 @Qualifier 指定 relationshipVectorStore（关系知识库专用向量库）； aiEnabled 根据 DashScope
     * key 是否配置决定是否启用向量检索。
     */
    public KnowledgeServiceImpl(
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            StorageService storage,
            @Qualifier("relationshipVectorStore") VectorStore vectors,
            MeterRegistry metrics,
            RedisResultCacheService cache,
            KnowledgeQueryPlanner queryPlanner,
            KnowledgeMarkdownParser markdownParser,
            @Value("${spring.ai.dashscope.api-key:not-configured}") String key,
            @Value("${app.rag.similarity-threshold:0.58}") double similarityThreshold) {
        this.documents = documents;
        this.chunks = chunks;
        this.storage = storage;
        this.vectors = vectors;
        this.metrics = metrics;
        this.cache = cache;
        this.queryPlanner = queryPlanner;
        this.markdownParser = markdownParser;
        this.aiEnabled = !key.isBlank() && !"not-configured".equals(key);
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * 手动上传实现：先校验 MIME 白名单与文件非空，读取字节后以 manual- 前缀生成索引版本、计算内容 SHA-256， 再统一委托 ingest()
     * 完成对象存储落库与解析切片；字节读取失败抛 422，解析失败由 ingest 统一转 422 并置 FAILED。
     */
    @Override
    public KnowledgeDocument upload(MultipartFile file, DocumentMetadata metadata, Long userId) {
        validate(file);
        try {
            byte[] bytes = file.getBytes();
            // 手动上传：索引文本即原文件内容，索引版本以 manual- 前缀标记
            return ingest(
                    Optional.ofNullable(file.getOriginalFilename()).orElse("document"),
                    Optional.ofNullable(file.getContentType()).orElse("application/octet-stream"),
                    bytes,
                    null,
                    metadata,
                    userId,
                    "manual-" + defaultValue(metadata.contentVersion(), "1.0", 40),
                    null,
                    sha256(bytes));
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_READ_FAILED", "无法读取知识文档");
        }
    }

    /** 文档入库统一入口：写入元数据 → 存对象存储 → 触发解析切片。 indexText 非空时用其作为索引/向量化文本（重建场景），否则用原始字节。 */
    private KnowledgeDocument ingest(
            String originalName,
            String contentType,
            byte[] storedBytes,
            String indexText,
            DocumentMetadata metadata,
            Long userId,
            String indexVersion,
            String repositoryPath,
            String contentHash) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy(userId);
        document.setOriginalName(originalName);
        document.setContentType(contentType);
        document.setSizeBytes(storedBytes.length);
        document.setCategory(KnowledgeTaxonomy.requireCategory(metadata.category()));
        document.setApplicableScenario(cleanMetadata(metadata.applicableScenario(), 160));
        document.setRelationshipStage(cleanMetadata(metadata.relationshipStage(), 64));
        document.setSourceName(cleanMetadata(metadata.sourceName(), 160));
        document.setSourceUrl(cleanMetadata(metadata.sourceUrl(), 500));
        document.setContentVersion(defaultValue(metadata.contentVersion(), "1.0", 40));
        document.setReviewStatus(
                metadata.reviewStatus() == null
                        ? KnowledgeReviewStatus.IN_REVIEW
                        : metadata.reviewStatus());
        document.setRiskTags(cleanMetadata(metadata.riskTags(), 300));
        document.setEvidenceLevel(
                metadata.evidenceLevel() == null
                        ? com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel
                                .UNVERIFIED
                        : metadata.evidenceLevel());
        document.setIndexVersion(indexVersion);
        document.setRepositoryPath(repositoryPath);
        document.setContentHash(contentHash);

        try {
            StorageService.StoredObject stored =
                    storage.store(storedBytes, originalName, contentType, "knowledge");
            document.setStorageKey(stored.key());
            document = documents.saveAndFlush(document);
            // 重建场景用正文（去掉元数据头）做索引，普通上传用原始字节
            byte[] indexBytes =
                    indexText == null ? storedBytes : indexText.getBytes(StandardCharsets.UTF_8);
            try (InputStream input = new ByteArrayInputStream(indexBytes)) {
                process(document, input);
            }
            metrics.counter("heartpilot.rag.documents", "outcome", "ready").increment();
            return document;
        } catch (Exception exception) {
            // 解析/向量化失败：标记 FAILED 并记录错误，文档元数据保留便于管理员排查
            document.setStatus(KnowledgeDocumentStatus.FAILED);
            document.setErrorMessage(shorten(exception.getMessage(), 480));
            if (document.getStorageKey() != null) documents.save(document);
            metrics.counter("heartpilot.rag.documents", "outcome", "failed").increment();
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DOCUMENT_PARSE_FAILED",
                    "文档解析失败：" + Optional.ofNullable(exception.getMessage()).orElse("未知错误"));
        }
    }

    /**
     * 全量重建实现：流式遍历目录下 .md 文件逐个用 markdownParser 解析，以全部文档内容哈希再摘要作为本次 indexVersion； 先逐个 delete
     * 清空旧文档（级联清理向量与对象存储），再按目录顺序重新 ingest 入库，最后汇总文档数与切片数返回。
     */
    @Override
    public RebuildResult rebuildRepository(Path sourceDirectory, Long userId) {
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IllegalArgumentException("知识目录不存在: " + sourceDirectory);
        }
        List<KnowledgeMarkdownParser.ParsedDocument> parsed;
        try (var paths = Files.walk(sourceDirectory)) {
            parsed =
                    paths.filter(Files::isRegularFile)
                            .filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .toLowerCase()
                                                    .endsWith(".md"))
                            .sorted()
                            .map(
                                    path -> {
                                        try {
                                            return markdownParser.parse(path);
                                        } catch (IOException exception) {
                                            throw new IllegalArgumentException(
                                                    "无法读取知识文件: " + path, exception);
                                        }
                                    })
                            .toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法扫描知识目录: " + sourceDirectory, exception);
        }
        if (parsed.isEmpty()) throw new IllegalArgumentException("知识目录中没有 Markdown 文件");
        // 用全部文档内容哈希的摘要作为本次重建的索引版本号，内容变化时版本随之变化
        String indexVersion =
                sha256(
                                parsed.stream()
                                        .map(KnowledgeMarkdownParser.ParsedDocument::contentHash)
                                        .reduce("", String::concat)
                                        .getBytes(StandardCharsets.UTF_8))
                        .substring(0, 16);

        // 全量重建：先清空现有文档（级联清理向量与对象存储），再按目录重新入库
        for (KnowledgeDocument existing : new ArrayList<>(documents.findAll()))
            delete(existing.getId());
        int chunksWritten = 0;
        for (KnowledgeMarkdownParser.ParsedDocument item : parsed) {
            KnowledgeDocument document =
                    ingest(
                            item.title() + ".md",
                            "text/markdown",
                            item.raw().getBytes(StandardCharsets.UTF_8),
                            item.body(),
                            item.metadata(),
                            userId,
                            indexVersion,
                            sourceDirectory.relativize(item.path()).toString().replace('\\', '/'),
                            item.contentHash());
            chunksWritten += document.getChunkCount();
        }
        return new RebuildResult(indexVersion, parsed.size(), chunksWritten);
    }

    /**
     * 实现：取最近更新的 READY 文档的 indexVersion 作为当前版本，无任何就绪文档时回退 "empty"； 该版本号会拼进检索 Redis
     * 缓存键并写入消息审计快照，内容变化时缓存自然失效。
     */
    @Override
    public String currentIndexVersion() {
        return documents
                .findFirstByStatusOrderByUpdatedAtDesc(KnowledgeDocumentStatus.READY)
                .map(KnowledgeDocument::getIndexVersion)
                .orElse("empty");
    }

    /** 文档处理主流程：Tika 抽文本 → 清洗 → 切片 → 逐片写 chunk 表 + PGVector。 中途失败时回滚已写入的向量和已保存的 chunk，避免脏数据。 */
    private void process(KnowledgeDocument document, InputStream input) throws Exception {
        document.setStatus(KnowledgeDocumentStatus.PROCESSING);
        document.setErrorMessage(null);
        documents.save(document);
        // Tika 自动识别 PDF/Word/TXT/Markdown 并抽取纯文本
        String text = clean(tika.parseToString(input));
        if (text.isBlank()) throw new IllegalArgumentException("文档未解析出有效文本");
        // 切片：每片 1200 字，相邻片重叠 120 字，保持上下文连贯
        List<String> pieces = split(text, 1_200, 120);
        List<String> writtenVectorIds = new ArrayList<>();
        try {
            for (int index = 0; index < pieces.size(); index++) {
                String piece = pieces.get(index);
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(index);
                chunk.setContent(piece);
                chunk.setSectionTitle(section(piece, index));
                chunk.setKeywords(keywords(piece));
                // 粗估 token 数：中文约 3 字符/token
                chunk.setTokenCount(Math.max(1, piece.length() / 3));
                // 向量元数据随片写入 PGVector，检索时据此回填文档名/章节/序号
                Map<String, Object> metadata =
                        Map.of(
                                "documentId",
                                document.getId(),
                                "documentName",
                                document.getOriginalName(),
                                "section",
                                chunk.getSectionTitle(),
                                "chunkIndex",
                                index);
                Document vectorDocument = new Document(piece, metadata);
                // 写入 PGVector，vectorDocument.getId() 即向量主键，后续删除用
                vectors.add(List.of(vectorDocument));
                writtenVectorIds.add(vectorDocument.getId());
                chunk.setVectorId(vectorDocument.getId());
                chunks.save(chunk);
            }
        } catch (Exception exception) {
            // 回滚：删除已写入 PGVector 的向量和已保存的 chunk 记录
            if (!writtenVectorIds.isEmpty()) vectors.delete(writtenVectorIds);
            chunks.deleteByDocumentId(document.getId());
            throw exception;
        }
        document.setChunkCount(pieces.size());
        document.setStatus(KnowledgeDocumentStatus.READY);
        documents.save(document);
    }

    /**
     * 检索实现：先经 queryPlanner 生成改写查询/分类/场景/词表，组装含索引版本的 Redis 缓存键； 缓存命中直接返回，未命中委托
     * retrieveUncached（PGVector 语义优先、异常或不足时关键词兜底），结果再回填缓存。
     */
    @Override
    public List<Source> retrieve(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();

        // 对原始 query 做预处理，生成结构化 Plan（改写查询、分类、场景、分词词表）
        KnowledgeQueryPlanner.Plan plan = queryPlanner.plan(query);
        // 构建 Redis 缓存键：版本 + 改写查询 + 分类 + 场景 + 条数
        String cacheKey =
                "v3|"
                        + currentIndexVersion()
                        + "|"
                        + plan.rewrittenQuery()
                        + "|"
                        + plan.category()
                        + "|"
                        + plan.scenario()
                        + "|"
                        + limit;
        Optional<Source[]> cached = cache.getKnowledge(cacheKey, Source[].class);
        if (cached.isPresent()) {
            metrics.counter("heartpilot.rag.retrieval", "strategy", "redis_cache").increment();
            return List.of(cached.get());
        }

        // 缓存未命中：执行原始检索逻辑，调用向量库检索文档
        List<Source> result = retrieveUncached(plan, limit);
        // 将本次检索出来的文档结果写入Redis缓存，后续相同query可以直接读缓存
        cache.putKnowledge(cacheKey, result);
        return result;
    }

    /** 未命中缓存时的实际检索：优先 PGVector 语义相似度，失败/未命中降级为关键词包含。 */
    private List<Source> retrieveUncached(KnowledgeQueryPlanner.Plan plan, int limit) {
        LinkedHashMap<String, Source> found = new LinkedHashMap<>();
        // 向量检索分支
        if (aiEnabled) {
            try {
                // 向量相似度检索
                List<Document> matches =
                        vectors.similaritySearch(
                                SearchRequest.builder()
                                        .query(plan.rewrittenQuery()) // 查询规划器
                                        .topK(Math.max(limit * 3, limit)) // 召回数量
                                        .similarityThreshold(similarityThreshold) // 相似度溢值
                                        .build());
                if (matches != null && !matches.isEmpty()) {
                    metrics.counter("heartpilot.rag.retrieval", "strategy", "vector").increment();
                    for (Document match : matches) {
                        Long documentId = longMetadata(match, "documentId");
                        KnowledgeDocument document =
                                documentId == null
                                        ? null
                                        : documents.findById(documentId).orElse(null);
                        // 校验文档状态、分类、场景是否符合当前查询plan，不符合直接跳过该切片
                        if (!eligible(document, plan)) continue;
                        // 从向量元数据中安全读取 int 值
                        int chunkIndex = intMetadata(match, "chunkIndex");
                        // 构造Source对象：封装文档信息、章节名、切片文本、切片索引、相似度阈值
                        Source source =
                                source(
                                        document,
                                        String.valueOf(
                                                match.getMetadata()
                                                        .getOrDefault("section", "相关片段")),
                                        match.getText(),
                                        chunkIndex,
                                        similarityThreshold);
                        found.putIfAbsent(documentId + ":" + chunkIndex, source);
                        if (found.size() >= limit) break;
                    }
                }
            } catch (Exception ignored) {
                // 向量检索异常（如 Embedding 服务不可用）：打点后降级到关键词匹配
                metrics.counter("heartpilot.rag.retrieval_failures", "strategy", "vector")
                        .increment();
            }
        }
        // 如果向量检索拿到的结果数量不足limit，执行降级：关键词检索补充结果
        if (found.size() < limit) keywordFallback(plan, limit, found);
        return found.values().stream().limit(limit).toList();
    }

    /** 关键词兜底：全表扫描切片，按命中词数占比计算相关度，过滤后补入结果集。 */
    private void keywordFallback(
            KnowledgeQueryPlanner.Plan plan, int limit, LinkedHashMap<String, Source> found) {
        metrics.counter("heartpilot.rag.retrieval", "strategy", "keyword_fallback").increment();
        for (KnowledgeChunk chunk : chunks.findAll()) {
            KnowledgeDocument document = documents.findById(chunk.getDocumentId()).orElse(null);
            if (!eligible(document, plan)) continue;
            // 统计该切片命中了多少个查询分词，据此估算相关度
            long hits =
                    plan.terms().stream()
                            .filter(term -> chunk.getContent().toLowerCase().contains(term))
                            .count();
            double relevance = hits / (double) Math.max(1, Math.min(plan.terms().size(), 6));
            // 相关度过低的切片直接跳过
            if (relevance < 0.17) continue;
            String key = document.getId() + ":" + chunk.getChunkIndex();
            found.putIfAbsent(
                    key,
                    source(
                            document,
                            chunk.getSectionTitle(),
                            chunk.getContent(),
                            chunk.getChunkIndex(),
                            relevance));
            if (found.size() >= limit) return;
        }
    }

    /** 判断文档是否可用于本次检索：必须已就绪且审核通过，并匹配计划分类与场景。 */
    private boolean eligible(KnowledgeDocument document, KnowledgeQueryPlanner.Plan plan) {
        if (document == null
                || document.getStatus() != KnowledgeDocumentStatus.READY
                || document.getReviewStatus() != KnowledgeReviewStatus.APPROVED) return false;
        if (plan.category() != null && !plan.category().equals(document.getCategory()))
            return false;
        return plan.scenario() == null
                || document.getApplicableScenario() == null
                || document.getApplicableScenario().isBlank()
                || document.getApplicableScenario().contains("通用")
                || document.getApplicableScenario().contains(plan.scenario());
    }

    /** 由文档实体与切片信息组装检索命中的 Source，携带分类、来源、证据等级等引用字段。 */
    private Source source(
            KnowledgeDocument document,
            String section,
            String content,
            int chunkIndex,
            double relevance) {
        return new Source(
                document.getId(),
                document.getOriginalName(),
                section,
                content,
                chunkIndex,
                document.getCategory(),
                document.getApplicableScenario(),
                document.getRelationshipStage(),
                document.getSourceName(),
                document.getSourceUrl(),
                document.getContentVersion(),
                document.getRiskTags(),
                document.getEvidenceLevel(),
                relevance,
                document.getIndexVersion());
    }

    /** 从向量元数据中安全读取 Long 值，缺失或类型不合法时返回 null。 */
    private Long longMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 从向量元数据中安全读取 int 值，缺失或类型不合法时返回 0。 */
    private int intMetadata(Document document, String key) {
        Object value = document.getMetadata().getOrDefault(key, 0);
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 实现：管理员视角直接分页返回全部文档元数据，不按用户过滤，排序由调用方通过 Pageable 指定。 */
    @Override
    public Page<KnowledgeDocument> list(Pageable pageable) {
        return documents.findAll(pageable);
    }

    /** 查看内容实现：只读取已经持久化的切片，并严格按 chunkIndex 升序拼接。 切片之间保留空行便于管理员核对边界，不触发 Tika 解析或重新向量化。 */
    @Override
    public DocumentContent content(Long id) {
        KnowledgeDocument document =
                documents.findById(id).orElseThrow(() -> ApiException.notFound("文档不存在"));
        List<KnowledgeChunk> orderedChunks = chunks.findByDocumentIdOrderByChunkIndexAsc(id);
        String content =
                orderedChunks.stream()
                        .map(KnowledgeChunk::getContent)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n\n"));
        return new DocumentContent(
                document.getId(), document.getOriginalName(), orderedChunks.size(), content);
    }

    /** 审核通过实现：只允许 READY + IN_REVIEW 状态流转。 同时生成新的索引版本，使包含旧版本号的检索与回答缓存自然失效，保证通过后可立即被检索。 */
    @Transactional
    @Override
    public KnowledgeDocument approve(Long id) {
        KnowledgeDocument document =
                documents.findById(id).orElseThrow(() -> ApiException.notFound("文档不存在"));
        if (document.getStatus() != KnowledgeDocumentStatus.READY) {
            throw ApiException.conflict("DOCUMENT_NOT_READY", "文档尚未处理完成，不能通过审核");
        }
        if (document.getReviewStatus() == KnowledgeReviewStatus.APPROVED) {
            return document;
        }
        if (document.getReviewStatus() != KnowledgeReviewStatus.IN_REVIEW) {
            throw ApiException.conflict("DOCUMENT_REVIEW_STATE_INVALID", "当前审核状态不能执行通过操作");
        }
        document.setReviewStatus(KnowledgeReviewStatus.APPROVED);
        // 缓存键包含当前索引版本；审核时更新版本即可让旧缓存自然失效，无需扫描 Redis key。
        document.setIndexVersion("approved-" + System.currentTimeMillis());
        return documents.save(document);
    }

    /**
     * 删除实现（@Transactional）：先收集该文档全部 chunk 的 vectorId 批量删 PGVector，再删 chunk 记录；
     * 对象存储文件删除失败仅吞异常不阻断元数据删除，最后删文档元数据；文档不存在抛 404。
     */
    @Transactional
    @Override
    public void delete(Long id) {
        KnowledgeDocument document =
                documents.findById(id).orElseThrow(() -> ApiException.notFound("文档不存在"));
        // 先收集该文档所有切片的 PGVector vectorId，批量删除向量
        List<String> vectorIds =
                chunks.findByDocumentIdOrderByChunkIndexAsc(id).stream()
                        .map(KnowledgeChunk::getVectorId)
                        .filter(Objects::nonNull)
                        .toList();
        if (!vectorIds.isEmpty()) vectors.delete(vectorIds);
        chunks.deleteByDocumentId(id);
        try {
            storage.delete(document.getStorageKey());
        } catch (IOException ignored) {
            // 存储删除失败不阻断元数据删除，避免孤立文件阻塞清理任务
        }
        documents.delete(document);
    }

    /** 上传前校验：文件非空且 MIME 类型在白名单内 */
    private void validate(MultipartFile file) {
        if (file.isEmpty()) throw ApiException.badRequest("文件不能为空");
        String type = Optional.ofNullable(file.getContentType()).orElse("application/octet-stream");
        if (!SUPPORTED_TYPES.contains(type)) {
            throw ApiException.badRequest("仅支持 Markdown、TXT、PDF 和 Word 文件");
        }
    }

    /** 清洗抽取文本：去 NUL 字节、压缩多余空白和连续空行，提升切片质量 */
    private String clean(String value) {
        return value.replace("\u0000", "")
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** 按固定窗口切片：每片 size 字符，相邻片重叠 overlap 字符，保持上下文连贯 */
    private List<String> split(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length(); start += size - overlap) {
            int end = Math.min(text.length(), start + size);
            result.add(text.substring(start, end));
            if (end == text.length()) break;
        }
        return result;
    }

    /** 从切片中提取 Markdown 一级标题作为章节名，无标题时用"第 N 节"兜底 */
    private String section(String text, int index) {
        return text.lines()
                .filter(line -> line.startsWith("#"))
                .findFirst()
                .map(line -> line.replaceFirst("^#+\\s*", ""))
                .orElse("第 " + (index + 1) + " 节");
    }

    /** 提取切片关键词（最多 10 个，逗号拼接），用于关键词兜底检索 */
    private String keywords(String text) {
        return terms(text).stream()
                .limit(10)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    /** 按非字母数字字符切词，小写、去重、取长度>=2 的前 20 个词 */
    private List<String> terms(String text) {
        return Pattern.compile("[^\\p{L}\\p{N}]+")
                .splitAsStream(text.toLowerCase())
                .filter(value -> value.length() >= 2)
                .distinct()
                .limit(20)
                .toList();
    }

    /** 截断字符串到指定长度，null 返回 null，用于错误信息存储前的长度控制 */
    private String shorten(String value, int length) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), length));
    }

    /** 清洗可选元数据：去空白并截断到指定长度，空值归一为 null。 */
    private String cleanMetadata(String value, int length) {
        if (value == null || value.isBlank()) return null;
        return shorten(value.strip(), length);
    }

    /** 清洗元数据并在为空时回退到默认值，同时按长度截断。 */
    private String defaultValue(String value, String fallback, int length) {
        String cleaned = cleanMetadata(value, length);
        return cleaned == null ? fallback : cleaned;
    }

    /** 计算字节数组的 SHA-256 并返回十六进制字符串，用于内容指纹与索引版本。 */
    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
