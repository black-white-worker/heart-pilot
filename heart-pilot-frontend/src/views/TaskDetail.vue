<template>
  <div v-if="detail">
    <div class="page-head">
      <div>
        <router-link to="/plans" class="back">← 返回行动规划</router-link>
        <h1>{{ detail.task.title }}</h1>
        <p>{{ detail.task.objective }}</p>
      </div>
      <div class="head-actions">
        <span class="badge" :class="statusClass(detail.task.status)">{{
          statusText(detail.task.status)
        }}</span
        ><button v-if="canCancel" class="btn danger" @click="cancel">取消任务</button
        ><button v-else-if="isFailed" class="btn primary" :disabled="retrying" @click="retryTask">
          {{ retrying ? '重试中…' : '重新执行' }}</button
        ><button v-else class="btn danger" :disabled="deleting" @click="removeTask">
          {{ deleting ? '正在删除…' : '删除记录' }}
        </button>
      </div>
    </div>
    <section class="panel agent-observer">
      <header class="observer-head">
        <div>
          <span class="eyebrow">Agent 可观测执行</span>
          <h2>{{ observerTitle }}</h2>
          <p>
            页面每 1.2 秒同步任务轨迹；外部能力不可用时会明确显示降级，不会编造地点、商品和距离。
          </p>
        </div>
        <div class="capability-badges">
          <span
            v-if="currentKind === 'PLACE_VISIT'"
            :class="['capability', evidence.sourceStatus === 'LIVE' ? 'online' : 'offline']"
            >地图 {{ evidence.sourceStatus === 'LIVE' ? '实时' : '暂无合格结果' }}</span
          ><span :class="['capability', aiStatusClass]">AI 对话 {{ aiStatusText }}</span
          ><span :class="['capability', webStatusClass]">网页搜索 {{ webStatusText }}</span
          ><span :class="['capability', reactState === '已参与' ? 'online' : 'offline']"
            >ReAct/MCP {{ reactState }}</span
          >
        </div>
      </header>
      <div class="phase-flow">
        <template v-for="(phase, index) in phaseDefinitions" :key="phase.code">
          <article :class="['phase-card', phaseStatus(phase.code)]">
            <span>{{ phaseStatus(phase.code) === 'done' ? '✓' : index + 1 }}</span>
            <div>
              <b>{{ phase.label }}</b
              ><small>{{ phaseHint(phase.code) }}</small>
            </div>
          </article>
          <i v-if="index < phaseDefinitions.length - 1">→</i>
        </template>
      </div>
      <div class="event-stream">
        <div class="stream-title">
          <b>实时执行轨迹</b
          ><small
            >{{ eventBranches.length }} 个规划分支 ·
            {{ executionEvents.length }} 条持久化事件</small
          >
        </div>
        <div v-if="!executionEvents.length" class="stream-empty">
          <span class="spinner"></span>任务启动后，这里会展示 Thought、Action、Observation 与
          Result。
        </div>
        <section v-for="branch in eventBranches" :key="branch.version" class="trace-branch">
          <button
            class="branch-row"
            type="button"
            :aria-expanded="isBranchExpanded(branch.version)"
            @click="toggleBranch(branch.version)"
          >
            <span class="branch-line"></span
            ><span class="branch-index">{{ branch.version + 1 }}</span>
            <span class="branch-main"
              ><b>{{ branch.title }}</b
              ><small>{{ branch.summary }}</small></span
            >
            <span class="branch-keywords">关键词：{{ branch.keywords }}</span
            ><span :class="['branch-status', branch.statusClass]">{{ branch.statusText }}</span>
            <span class="branch-toggle"
              >{{ isBranchExpanded(branch.version) ? '收起' : '展开' }}⌄</span
            >
          </button>
          <div v-if="isBranchExpanded(branch.version)" class="branch-events">
            <div v-if="!branch.events.length" class="stream-empty">
              <span class="spinner"></span>本轮轨迹正在同步…
            </div>
            <article
              v-for="event in branch.events"
              :key="event.id"
              :class="['event-card', event.eventType.toLowerCase()]"
            >
              <div class="event-icon">{{ eventIcon(event) }}</div>
              <div class="event-body">
                <div>
                  <b>{{ event.title }}</b
                  ><time>{{ time(event.createdAt) }}</time>
                </div>
                <ExpandableText :content="event.detail" :lines="4" />
                <footer>
                  <span v-if="event.provider">{{ event.provider }}</span
                  ><span v-if="event.toolName">工具：{{ event.toolName }}</span
                  ><span v-if="event.itemCount !== null && event.itemCount !== undefined"
                    >{{ event.itemCount }} 项</span
                  ><span v-if="event.durationMs !== null && event.durationMs !== undefined"
                    >{{ event.durationMs }} ms</span
                  ><a
                    v-if="event.sourceUrl"
                    :href="event.sourceUrl"
                    target="_blank"
                    rel="noreferrer"
                    >查看来源 ↗</a
                  >
                </footer>
              </div>
            </article>
          </div>
        </section>
      </div>
    </section>
    <div class="summary-grid">
      <section class="panel panel-pad">
        <h3>任务信息</h3>
        <dl class="task-info">
          <div>
            <dt>任务 ID</dt>
            <dd>#{{ detail.task.id }}</dd>
          </div>
          <div v-if="currentKind === 'PLACE_VISIT'">
            <dt>城市</dt>
            <dd>{{ parameters.city || '—' }}</dd>
          </div>
          <div>
            <dt>预算</dt>
            <dd>{{ displayBudgetText }}</dd>
          </div>
          <div v-if="currentKind === 'PLACE_VISIT'">
            <dt>问题数量</dt>
            <dd>{{ (parameters.questions || []).length }} 个</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ date(detail.task.createdAt) }}</dd>
          </div>
          <div>
            <dt>工具调用</dt>
            <dd>{{ detail.toolCalls.length }} 次</dd>
          </div>
        </dl>
      </section>
      <section class="panel panel-pad tool-log">
        <h3>工具审计</h3>
        <div v-if="!detail.toolCalls.length" class="muted">尚未调用工具</div>
        <div class="tool-items">
          <article v-for="c in detail.toolCalls" :key="c.id">
            <span>↗</span>
            <div>
              <b>{{ c.toolName }}</b
              ><small>{{ c.status }} · {{ c.durationMs || 0 }}ms</small>
            </div>
          </article>
        </div>
      </section>
    </div>
    <div class="detail-grid">
      <section class="panel steps-panel">
        <header>
          <div>
            <b>执行过程</b><small>当前第 {{ detail.task.currentStep }} 步</small>
          </div>
          <span
            >{{ detail.steps.filter((x: any) => x.status === 'COMPLETED').length }}/{{
              detail.steps.length
            }}</span
          >
        </header>
        <div class="timeline">
          <article v-for="s in detail.steps" :key="s.id" :class="s.status.toLowerCase()">
            <div class="dot">{{ s.status === 'COMPLETED' ? '✓' : s.stepNo }}</div>
            <div>
              <b>{{ s.name }}</b
              ><ExpandableText :content="s.detail || stepHint(s)" :lines="6" /><small
                v-if="s.completedAt"
                >{{ time(s.completedAt) }}</small
              >
            </div>
          </article>
        </div>

        <div
          v-if="detail.task.status === 'RUNNING' || detail.task.status === 'WAITING'"
          class="running-box"
        >
          <span class="spinner"></span>
          <div>
            <b>{{ runningTitle }}</b>
            <p>{{ runningHint }}</p>
          </div>
        </div>

        <!-- 需求检查点：结构化需求确认 + 冲突交互（Tier1/Tier2） -->
        <div
          v-if="detail.task.status === 'AWAITING_REQUIREMENT'"
          class="confirm-box requirement-checkpoint"
        >
          <span>需求检查点</span>
          <h3>系统已把你的需求解析为结构化约束，先核对再生成方案</h3>
          <div v-if="requirementBlockers.length" class="requirement-conflicts" role="alert">
            <b
              >发现
              {{ requirementBlockers.length }} 个冲突，需先调整约束（硬性冲突不会强行生成方案）：</b
            >
            <p
              v-for="(issue, index) in requirementBlockers"
              :key="'blocker-' + index"
              class="requirement-issue"
            >
              ⚠️ {{ issue.message }}
            </p>
            <small>可以下方逐条修改约束（改预算、删点位、放宽时间），修改后立即重新校验。</small>
          </div>
          <div
            v-if="requirementWarnings.length && !requirementBlockers.length"
            class="requirement-warnings"
          >
            <b>提醒（不影响生成，确认后可直接继续）：</b>
            <p
              v-for="(issue, index) in requirementWarnings"
              :key="'warning-' + index"
              class="requirement-issue"
            >
              {{ issue.message }}
            </p>
          </div>
          <div v-if="requirementData?.requirement" class="requirement-panel">
            <div class="requirement-sections">
              <!-- 四类约束 -->
              <section
                v-for="section in requirementSections"
                :key="section.path"
                class="req-section"
              >
                <b>{{ section.label }}</b>
                <ul>
                  <li v-for="item in requirementList(section.path)" :key="item">
                    {{ item }}
                    <button
                      class="req-remove"
                      title="移除该约束"
                      @click="removeListConstraint(section.path, item)"
                    >
                      ✕
                    </button>
                  </li>
                </ul>
                <div class="req-add">
                  <input
                    v-model="requirementListInput[section.path]"
                    class="input"
                    :placeholder="section.placeholder"
                    @keyup.enter="addListConstraint(section.path)"
                  />
                  <button class="btn small" @click="addListConstraint(section.path)">添加</button>
                </div>
              </section>
              <!-- 地点业务实体 -->
              <template v-if="requirementData?.requirement?.place">
                <section class="req-section">
                  <b>时间与出行</b>
                  <div class="req-grid">
                    <label
                      >开始时间
                      <input
                        v-model="requirementData.requirement.place.startTime"
                        class="input"
                        placeholder="14:00"
                        @change="
                          patchConstraint(
                            'place.startTime',
                            'SET',
                            requirementData.requirement.place.startTime
                          )
                        "
                      />
                    </label>
                    <label
                      >最晚返程
                      <input
                        v-model="requirementData.requirement.place.latestReturnTime"
                        class="input"
                        placeholder="20:00"
                        @change="
                          patchConstraint(
                            'place.latestReturnTime',
                            'SET',
                            requirementData.requirement.place.latestReturnTime
                          )
                        "
                      />
                    </label>
                    <label
                      >每点停留（分钟）
                      <input
                        v-model.number="requirementData.requirement.place.stayMinutesPerPlace"
                        class="input"
                        type="number"
                        @change="
                          patchConstraint(
                            'place.stayMinutesPerPlace',
                            'SET',
                            requirementData.requirement.place.stayMinutesPerPlace
                          )
                        "
                      />
                    </label>
                    <label
                      >预算上限（元）
                      <input
                        v-model.number="requirementData.requirement.place.budgetMax"
                        class="input"
                        type="number"
                        @change="
                          patchConstraint(
                            'place.budgetMax',
                            'SET',
                            requirementData.requirement.place.budgetMax
                          )
                        "
                      />
                    </label>
                  </div>
                </section>
                <section
                  v-for="section in placeListSections"
                  :key="section.path"
                  class="req-section"
                >
                  <b>{{ section.label }}</b>
                  <ul>
                    <li v-for="item in requirementList(section.path)" :key="item">
                      {{ item }}
                      <button
                        class="req-remove"
                        title="移除"
                        @click="removeListConstraint(section.path, item)"
                      >
                        ✕
                      </button>
                    </li>
                  </ul>
                  <div class="req-add">
                    <input
                      v-model="requirementListInput[section.path]"
                      class="input"
                      :placeholder="section.placeholder"
                      @keyup.enter="addListConstraint(section.path)"
                    />
                    <button class="btn small" @click="addListConstraint(section.path)">添加</button>
                  </div>
                </section>
              </template>
              <!-- 送礼业务实体 -->
              <template v-if="requirementData?.requirement?.gift">
                <section class="req-section">
                  <b>预算与对象</b>
                  <div class="req-grid">
                    <label
                      >预算上限（元）
                      <input
                        v-model.number="requirementData.requirement.gift.budgetMax"
                        class="input"
                        type="number"
                        @change="
                          patchConstraint(
                            'gift.budgetMax',
                            'SET',
                            requirementData.requirement.gift.budgetMax
                          )
                        "
                      />
                    </label>
                    <label
                      >场合
                      <input
                        v-model="requirementData.requirement.gift.occasion"
                        class="input"
                        @change="
                          patchConstraint(
                            'gift.occasion',
                            'SET',
                            requirementData.requirement.gift.occasion
                          )
                        "
                      />
                    </label>
                  </div>
                </section>
                <section
                  v-for="section in giftListSections"
                  :key="section.path"
                  class="req-section"
                >
                  <b>{{ section.label }}</b>
                  <ul>
                    <li v-for="item in requirementList(section.path)" :key="item">
                      {{ item }}
                      <button
                        class="req-remove"
                        title="移除"
                        @click="removeListConstraint(section.path, item)"
                      >
                        ✕
                      </button>
                    </li>
                  </ul>
                  <div class="req-add">
                    <input
                      v-model="requirementListInput[section.path]"
                      class="input"
                      :placeholder="section.placeholder"
                      @keyup.enter="addListConstraint(section.path)"
                    />
                    <button class="btn small" @click="addListConstraint(section.path)">添加</button>
                  </div>
                </section>
              </template>
            </div>
          </div>
          <div class="confirm-actions">
            <button
              class="btn"
              :disabled="requirementConfirming || !requirementData?.blocked"
              title="有冲突时重新运行会再次校验，仍冲突会回到本检查点"
              @click="retryTask"
            >
              重新校验需求
            </button>
            <button
              class="btn coral"
              :disabled="requirementConfirming || requirementData?.blocked"
              @click="approveRequirement"
            >
              {{ requirementConfirming ? '正在生成方案…' : '确认需求无误，继续生成方案' }}
            </button>
          </div>
          <small class="requirement-footnote">
            单条约束可直接修改（改预算、删黑名单、加必去点位等），系统只更新对应字段，无需整段重写需求描述。
          </small>
        </div>

        <div v-if="detail.task.status === 'AWAITING_CONFIRMATION'" class="confirm-box">
          <span>等待你的确认</span>
          <h3>先核对当前方案，也可以直接修改约束后重新规划</h3>
          <div class="preview">
            <StructuredText :content="detail.task.planPreview || '方案仍在整理，请稍后刷新。'" />
          </div>
          <p class="plan-cards-hint">
            下方"计划与版本"面板按行动类型展示卡片，可逐条核对后确认，或修改约束重新规划。
          </p>
          <div class="plan-editor">
            <div class="editor-title">
              <b>确认前修改当前参数</b><small>{{ editorHint }}</small>
            </div>

            <!-- 地点见面：省/市 + 预算 + 问题清单 -->
            <template v-if="currentKind === 'PLACE_VISIT'">
              <div class="grid-2">
                <div class="field">
                  <label>省 / 直辖市</label>
                  <select v-model="editor.province" class="select">
                    <option v-for="province in provinces" :key="province" :value="province">
                      {{ province }}
                    </option>
                  </select>
                </div>
                <div class="field">
                  <label>城市</label>
                  <select
                    v-model="editor.city"
                    class="select"
                    :disabled="!editor.province || citiesLoading"
                  >
                    <option value="" disabled>
                      {{ citiesLoading ? '加载城市中…' : '请选择城市' }}
                    </option>
                    <option v-for="city in cityOptions" :key="city" :value="city">
                      {{ city }}
                    </option>
                  </select>
                </div>
              </div>
              <div class="grid-2">
                <div class="field">
                  <label>预算（元）</label
                  ><input
                    v-model.number="editor.budget"
                    class="input"
                    type="number"
                    min="0"
                    placeholder="500"
                  />
                </div>
              </div>
              <div class="field">
                <label>需要方案逐项回答的问题 <small>每行一个，以当前完整清单为准</small></label
                ><textarea
                  v-model="editor.questionsText"
                  class="textarea questions"
                  placeholder="哪家店适合安静聊天？&#10;两个地点之间怎么走？&#10;有哪些不去商场、有停车位且适合聊天的备选？"
                ></textarea>
              </div>
            </template>

            <!-- 礼物：预算 + 场合 + 形式 + 对方喜好 -->
            <template v-else-if="currentKind === 'GIFT_RITUAL'">
              <div class="grid-2">
                <div class="field">
                  <label>礼物预算</label
                  ><input
                    v-model="editor.giftBudget"
                    class="input"
                    placeholder="例如：200到500元"
                  />
                </div>
                <div class="field">
                  <label>送礼场合</label>
                  <select v-model="editor.occasionType" class="select">
                    <option value="">不限</option>
                    <option value="生日">生日</option>
                    <option value="节日">节日</option>
                    <option value="道歉">道歉</option>
                    <option value="感谢">感谢</option>
                    <option value="纪念日">纪念日</option>
                  </select>
                </div>
              </div>
              <div class="field">
                <label>礼物形式</label>
                <select v-model="editor.giftForm" class="select">
                  <option value="">不限</option>
                  <option value="实物">实物</option>
                  <option value="红包">红包</option>
                  <option value="体验类活动">体验类活动</option>
                </select>
              </div>
              <div class="field">
                <label>对方喜好或禁忌</label
                ><input
                  v-model="editor.recipientPreferences"
                  class="input"
                  placeholder="例如：喜欢喝茶，不送香水"
                />
              </div>
            </template>

            <!-- 发消息：渠道 + 语气 + 回复期待 -->
            <template v-else-if="currentKind === 'MESSAGE'">
              <div class="grid-2">
                <div class="field">
                  <label>发送渠道</label>
                  <select v-model="editor.messageChannel" class="select">
                    <option value="">不限</option>
                    <option value="微信">微信</option>
                    <option value="短信">短信</option>
                    <option value="邮件">邮件</option>
                  </select>
                </div>
                <div class="field">
                  <label>语气风格</label>
                  <select v-model="editor.toneStyle" class="select">
                    <option value="">不限</option>
                    <option value="正式">正式</option>
                    <option value="亲切">亲切</option>
                    <option value="幽默">幽默</option>
                    <option value="委婉">委婉</option>
                  </select>
                </div>
              </div>
              <div class="field">
                <label>对方回复期待</label
                ><input
                  v-model="editor.replyExpectation"
                  class="input"
                  placeholder="例如：希望对方愿意周末出来坐坐"
                />
              </div>
              <div class="field">
                <label>明确边界 <small>选填，不希望消息里出现什么</small></label
                ><textarea
                  v-model="editor.boundary"
                  class="textarea"
                  placeholder="例如：不提上次吵架的事，不要施压，不要发长段小作文"
                ></textarea>
              </div>
            </template>

            <!-- 自我计划：内容 + 期望效果 + 频率 -->
            <template v-else-if="currentKind === 'SELF_PRACTICE'">
              <div class="field">
                <label>计划要做的事或要达成的目标</label
                ><input
                  v-model="editor.planContent"
                  class="input"
                  placeholder="例如：练习主动开启对话，控制情绪不急躁"
                />
              </div>
              <div class="grid-2">
                <div class="field">
                  <label>期望效果</label
                  ><input
                    v-model="editor.expectedOutcome"
                    class="input"
                    placeholder="例如：和对方聊天不再紧张"
                  />
                </div>
                <div class="field">
                  <label>频率</label>
                  <select v-model="editor.frequency" class="select">
                    <option value="">不限</option>
                    <option value="每日">每日</option>
                    <option value="每周几次">每周几次</option>
                  </select>
                </div>
              </div>
              <div class="field">
                <label>明确边界 <small>选填，不希望练习涉及什么</small></label
                ><textarea
                  v-model="editor.boundary"
                  class="textarea"
                  placeholder="例如：不节食、不熬夜、不与他人攀比进度"
                ></textarea>
              </div>
            </template>
          </div>
          <div class="confirm-actions">
            <button class="btn" :disabled="submitting || !canRevise" @click="confirmTask(false)">
              {{ submitting ? '正在提交…' : '应用约束修改并从第一步重新规划' }}</button
            ><button
              class="btn coral"
              :disabled="submitting || canRevise"
              @click="confirmTask(true)"
            >
              {{
                submitting ? '正在刷新检索…' : canRevise ? '请先应用当前修改' : '确认并实时补充检索'
              }}
            </button>
          </div>
        </div>
      </section>
    </div>
    <section v-if="detail.task.evidenceUpdatedAt" class="panel evidence-panel">
      <div class="evidence-head">
        <div>
          <span class="eyebrow">真实地点与路线证据</span>
          <h2>{{ evidence.city }} · {{ evidence.topics }}</h2>
        </div>
        <div class="evidence-head-side">
          <small>更新时间：{{ evidence.searchedAt || '—' }}</small>
          <button
            v-if="canReshufflePlaces"
            class="btn ghost"
            :disabled="reshuffling"
            @click="reshufflePlaces"
            title="不调外部地图接口，从已检索到的候选池里按类别均衡随机换一批，相邻两批允许部分重合"
          >
            {{ reshuffling ? '换一批中…' : '换一批候选地点' }}
          </button>
        </div>
      </div>
      <div v-if="mapCards.length" class="place-grid">
        <article v-for="place in visibleMapCards" :key="place.poiId || place.name" class="map-card">
          <img v-if="place.coverImageUrl" :src="place.coverImageUrl" :alt="place.name" />
          <span class="place-index">地图卡片</span>
          <h3>{{ place.name }}</h3>
          <p>{{ place.address }}</p>
          <small>{{ place.category || place.type }}</small>
          <div class="place-live-meta">
            <span :class="`business-${(place.businessStatus || 'UNKNOWN').toLowerCase()}`">
              {{ businessStatus(place.businessStatus) }}
            </span>
            <span v-if="place.rating">评分 {{ place.rating }}</span>
            <span v-if="place.businessHours">{{ place.businessHours }}</span>
          </div>
          <small v-if="place.statusCheckedAt">状态核验：{{ place.statusCheckedAt }}</small
          ><small v-if="place.businessStatusBasis === 'DERIVED_FROM_AMAP_OPENING_HOURS'"
            >营业状态按高德营业时间与当前时刻推算，请在出发前复核</small
          ><a v-if="place.mapUrl" :href="place.mapUrl" target="_blank" rel="noreferrer"
            >在高德地图中查看 ↗</a
          >
          <div v-if="place.routeFromPrevious" class="card-route">
            从上一站{{ routeModeText(place.routeFromPrevious) }}
            {{ formatDistance(place.routeFromPrevious.distanceMeters) }} · 约
            {{ place.routeFromPrevious.durationMinutes }} 分钟
          </div>
        </article>
      </div>
      <div v-if="mapCards.length > 6" class="place-more-row">
        <button class="place-more-btn" @click="showAllMapCards = !showAllMapCards">
          {{ showAllMapCards ? '收起地点' : `展开全部 ${mapCards.length} 个地点` }}
        </button>
      </div>
      <div v-else class="evidence-empty">
        {{ evidence.notice || '没有取得符合当前地点范围的可核验地图地点。' }}
      </div>
      <div v-if="evidence.routes?.length" class="route-list">
        <h3>
          地点间路线
          <small class="route-hint">（距离与耗时仅供简单参考，实际出行请以地图实时路况为准）</small>
        </h3>
        <div class="route-map-overview">
          <div class="route-map-title">
            <div><b>路线总览</b><small>A → B → C → D 按行程顺序连接</small></div>
            <small>轨迹与底图来自高德地图</small>
          </div>
          <img v-if="routeMapUrl" :src="routeMapUrl" alt="地点间路线总览图" />
          <div v-else-if="routeMapLoading" class="route-map-placeholder">正在生成路线图…</div>
          <div v-else class="route-map-placeholder">
            {{
              routeMapUnavailable ? '路线图片暂不可用，可继续使用下方导航链接。' : '等待路线数据。'
            }}
          </div>
        </div>
        <article
          v-for="(route, index) in evidence.routes"
          :key="`${route.originName}-${route.destinationName}-${index}`"
        >
          <div class="route-points">
            <b>{{ route.originName }}</b
            ><span>{{ routeModeText(route) }}</span
            ><b>{{ route.destinationName }}</b>
          </div>
          <div class="route-stats">
            <strong>{{ formatDistance(route.distanceMeters) }}</strong
            ><strong>约 {{ route.durationMinutes }} 分钟</strong
            ><small v-if="route.routeCheckedAt">实时刷新：{{ route.routeCheckedAt }}</small
            ><a :href="routeNavigationUrl(route)" target="_blank" rel="noreferrer">打开导航 ↗</a>
          </div>
        </article>
      </div>
      <p v-if="mapCards.length" class="evidence-notice">{{ evidence.notice }}</p>
    </section>
    <section v-if="plan && (plan.versions?.length || planItems.length)" class="panel version-panel">
      <header class="version-head">
        <div>
          <span class="eyebrow">计划与版本</span>
          <h2>
            目标：{{ goalTypeLabel(plan.goalType) }} · 状态：{{ planStatusText(plan.planStatus) }}
          </h2>
          <p>
            按行动类型拆分的可执行条目；每次重新规划都会保留旧版本，确认后当前版本成为正式计划。
          </p>
        </div>
        <small v-if="plan.versions?.length">{{ plan.versions.length }} 个版本 · 最新在前</small>
      </header>
      <div v-if="planItems.length" class="plan-cards">
        <article
          v-for="item in planItems"
          :key="item.id"
          class="action-card"
          :class="cardKindClass(item.executionKind)"
        >
          <div class="action-card-head">
            <span class="action-kind">{{ actionKindLabel(item.executionKind) }}</span>
            <b>{{ item.title }}</b
            ><span class="item-status">{{ itemStatusText(item.status) }}</span>
          </div>
          <div class="action-card-body">
            <template v-if="item.executionKind === 'MESSAGE'">
              <blockquote v-if="item.payload.draft" class="msg-draft">
                {{ item.payload.draft }}
              </blockquote>
              <p v-if="item.payload.tone">语气：{{ item.payload.tone }}</p>
              <p v-if="item.payload.sendTiming">建议时机：{{ item.payload.sendTiming }}</p>
              <p v-if="item.payload.forbiddenExpressions?.length" class="muted">
                避免：{{ item.payload.forbiddenExpressions.join('、') }}
              </p>
            </template>
            <template v-else-if="item.executionKind === 'PLACE_VISIT'">
              <p v-if="item.payload.status === 'NEEDS_CITY'">
                地点行动需要城市信息，请在确认区补充城市后重新规划。
              </p>
              <p v-else-if="item.payload.status === 'NO_PLACE'">
                {{
                  item.payload.notice || '当前城市暂未取得可核验的地点，系统不会编造店名或地址。'
                }}
              </p>
              <template v-else>
                <p v-if="item.payload.placeName">
                  <b>{{ item.payload.placeName }}</b
                  >（{{ item.payload.address }}）
                </p>
                <p v-if="item.payload.businessHours">营业时间：{{ item.payload.businessHours }}</p>
                <p v-if="item.payload.routeMode">
                  从上一站{{ routeModeText2(item.payload.routeMode) }}约
                  {{ item.payload.durationMinutes }} 分钟
                </p>
                <a
                  v-if="item.payload.mapUrl"
                  :href="item.payload.mapUrl"
                  target="_blank"
                  rel="noreferrer"
                  >在高德地图中查看 ↗</a
                >
              </template>
            </template>
            <template v-else-if="item.executionKind === 'CONVERSATION'">
              <p v-if="item.payload.goal">沟通目标：{{ item.payload.goal }}</p>
              <p v-if="item.payload.opening">开场白：{{ item.payload.opening }}</p>
              <ul v-if="item.payload.keyExpressions?.length" class="key-list">
                <li v-for="line in item.payload.keyExpressions" :key="line">{{ line }}</li>
              </ul>
              <p v-if="item.payload.concreteRequest">
                具体请求：{{ item.payload.concreteRequest }}
              </p>
              <p v-if="item.payload.exitCondition">退出条件：{{ item.payload.exitCondition }}</p>
            </template>
            <template v-else-if="item.executionKind === 'GIFT_RITUAL'">
              <p v-if="item.payload.preparation">准备事项：{{ item.payload.preparation }}</p>
              <p v-if="item.payload.budgetText">预算安排：{{ item.payload.budgetText }}</p>
              <ul v-if="item.payload.steps?.length" class="key-list">
                <li v-for="line in item.payload.steps" :key="line">{{ line }}</li>
              </ul>
              <div v-if="item.payload.giftIdeas?.length" class="gift-ideas">
                <p><b>AI 分析后的礼物候选</b></p>
                <p class="muted search-tip">
                  京东、拼多多可直接查看结果；淘宝首次打开需登录一次（浏览器会记住）。
                </p>
                <p v-if="item.payload.searchSource" class="muted search-tip">
                  <span v-if="item.payload.searchSource === 'serpapi'"
                    >价格与品牌已联网校准（付费搜索），价格为参考价</span
                  >
                  <span v-else>搜索增强暂不可用，以上为 AI 基于喜好的推荐</span>
                </p>
                <p v-if="item.payload.searchQuotaTip" class="muted search-tip">
                  {{ item.payload.searchQuotaTip }}
                </p>
                <ul class="key-list">
                  <li v-for="idea in item.payload.giftIdeas" :key="idea.title">
                    <b>{{ idea.title }}</b>
                    <span v-if="idea.priceHint" class="muted">（参考价 {{ idea.priceHint }}）</span>
                    <div v-if="idea.reason" class="muted">{{ idea.reason }}</div>
                    <div v-if="idea.urls?.length" class="idea-links">
                      <a
                        v-for="u in idea.urls"
                        :key="u.platform"
                        :href="u.url"
                        target="_blank"
                        rel="noopener"
                        referrerpolicy="unsafe-url"
                        >{{ u.platform }}搜 ↗</a
                      >
                    </div>
                    <div v-if="idea.sources?.length" class="idea-sources">
                      <small>参考来源：</small>
                      <a
                        v-for="(s, i) in idea.sources"
                        :key="i"
                        :href="s.url"
                        target="_blank"
                        rel="noreferrer"
                        >{{ s.title }}</a
                      >
                    </div>
                  </li>
                </ul>
              </div>
            </template>
            <template v-else-if="item.executionKind === 'SELF_PRACTICE'">
              <p v-if="item.payload.practiceContent" class="pre-line">
                {{ item.payload.practiceContent }}
              </p>
              <p v-if="item.payload.durationMinutes">
                建议时长：{{ item.payload.durationMinutes }} 分钟
              </p>
              <p v-if="item.payload.completionCriteria">
                完成标准：{{ item.payload.completionCriteria }}
              </p>
            </template>
            <template v-else-if="item.executionKind === 'OBSERVATION'">
              <p v-if="item.payload.observeContent" class="pre-line">
                {{ item.payload.observeContent }}
              </p>
              <ul v-if="item.payload.recordFields?.length" class="key-list">
                <li v-for="line in item.payload.recordFields" :key="line">{{ line }}</li>
              </ul>
              <p v-if="item.payload.forbiddenInferences">
                禁止推断：{{ item.payload.forbiddenInferences }}
              </p>
            </template>
            <ExpandableText
              v-if="item.instruction && !['MESSAGE', 'CONVERSATION'].includes(item.executionKind)"
              :content="item.instruction"
              :lines="6"
            />
          </div>
          <div class="action-card-foot">
            <span v-if="item.timingSuggestion">时机：{{ item.timingSuggestion }}</span>
            <span v-if="item.estimatedDurationMinutes"
              >约 {{ item.estimatedDurationMinutes }} 分钟</span
            ><span v-if="item.estimatedCost !== null && item.estimatedCost !== undefined"
              >预算约 {{ item.estimatedCost }} 元</span
            ><span :class="['risk', riskClass(item.riskLevel)]">{{
              riskText(item.riskLevel)
            }}</span>
          </div>
        </article>
      </div>
      <div class="version-list">
        <article
          v-for="v in sortedVersions"
          :key="v.id"
          :class="['version-row', v.status.toLowerCase()]"
        >
          <span class="version-no">V{{ v.versionNo + 1 }}</span>
          <div class="version-main">
            <b>{{ versionStatusText(v.status) }}</b
            ><small>{{ date(v.createdAt) }}</small>
            <ExpandableText v-if="v.previewText" :content="v.previewText" :lines="4" />
            <p v-if="v.note" class="version-note">驳回/修改说明：{{ v.note }}</p>
          </div>
          <span class="version-status">{{ versionStatusText(v.status) }}</span>
        </article>
      </div>
    </section>
    <section v-if="detail.task.finalResult" class="panel result">
      <span class="eyebrow">行动计划书已生成</span>
      <h2>你的可执行行动计划书</h2>
      <StructuredText :content="detail.task.finalResult" />
      <div class="result-actions">
        <button
          v-if="!detail.pdfFile"
          class="btn coral"
          :disabled="pdfGenerating"
          @click="generatePdf"
        >
          {{ pdfGenerating ? '正在生成 PDF…' : '生成 PDF 文件' }}</button
        ><button v-else class="btn coral" :disabled="pdfDownloading" @click="downloadPdf">
          {{ pdfDownloading ? '正在下载…' : '下载计划书 PDF' }}</button
        ><span>{{
          detail.pdfFile
            ? `PDF 已生成（${fileSize(detail.pdfFile.sizeBytes)}），可在本计划详情下载。`
            : '请先确认上方计划书内容，再按需生成 PDF；生成和下载是两个独立操作。'
        }}</span>
      </div>
    </section>
  </div>
  <div v-else class="panel empty">正在读取任务…</div>
  <transition name="toast"
    ><div v-if="error" class="toast">{{ error }}</div></transition
  >
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, streamSSE } from '../api'
import ExpandableText from '../components/ExpandableText.vue'
import StructuredText from '../components/StructuredText.vue'

interface EditorState {
  province: string
  city: string
  budget: number | null
  questionsText: string
  giftBudget: string
  occasionType: string
  giftForm: string
  recipientPreferences: string
  messageChannel: string
  toneStyle: string
  replyExpectation: string
  boundary: string
  planContent: string
  expectedOutcome: string
  frequency: string
}

const route = useRoute()
const router = useRouter()
const detail = ref<any>()
const plan = ref<any>()
const error = ref('')
const timer = ref<number>()
const errorTimer = ref<number>()
const pdfGenerating = ref(false)
const pdfDownloading = ref(false)
const submitting = ref(false)
const deleting = ref(false)
const retrying = ref(false)
const reshuffling = ref(false)
const hydratedKey = ref('')
const expandedBranches = ref<Set<number>>(new Set())
const routeMapUrl = ref('')
const routeMapKey = ref('')
const routeMapLoading = ref(false)
const routeMapUnavailable = ref(false)
const editor = reactive<EditorState>({
  province: '',
  city: '',
  budget: null,
  questionsText: '',
  // 礼物专属
  giftBudget: '',
  occasionType: '',
  giftForm: '',
  recipientPreferences: '',
  // 发消息专属
  messageChannel: '',
  toneStyle: '',
  replyExpectation: '',
  boundary: '',
  // 自我计划专属
  planContent: '',
  expectedOutcome: '',
  frequency: ''
})
const cityOptions = ref<string[]>([])
const citiesLoading = ref(false)
// ---- 结构化需求检查点（Tier1/Tier2）----
const requirementData = ref<any>(null)
const requirementListInput = ref<Record<string, string>>({})
const requirementConfirming = ref(false)
const requirementIssueClass = (issue: any) =>
  issue?.severity === 'BLOCKER' ? 'req-issue-blocker' : 'req-issue-warning'
const requirementBlockers = computed(() => {
  const issues = requirementData.value?.issues
  return Array.isArray(issues) ? issues.filter((i: any) => i.severity === 'BLOCKER') : []
})
const requirementWarnings = computed(() => {
  const issues = requirementData.value?.issues
  return Array.isArray(issues) ? issues.filter((i: any) => i.severity === 'WARNING') : []
})
/** 四类约束的可编辑分组（地点/送礼共用） */
const requirementSections = computed(() => [
  { path: 'hardConstraints', label: '硬性约束（必须满足）', placeholder: '如：晚上8点前到家' },
  { path: 'priorityPreferences', label: '优先偏好（尽量满足）', placeholder: '如：喜欢广西菜' },
  {
    path: 'optionalEnhancements',
    label: '可选加分项（有余力再做）',
    placeholder: '如：下雨的室内备选'
  },
  { path: 'exclusions', label: '排除黑名单（坚决不做）', placeholder: '如：不去吵闹的商场' }
])
const placeListSections = computed(() => [
  { path: 'place.mustVisit', label: '必去点位', placeholder: '如：人民公园' },
  { path: 'place.recommendedVisit', label: '推荐点位', placeholder: '如：咖啡馆' },
  { path: 'place.forbiddenPlaces', label: '禁止点位', placeholder: '如：某商场' }
])
const giftListSections = computed(() => [
  { path: 'gift.stylePreferences', label: '风格偏好', placeholder: '如：喜欢喝茶' },
  { path: 'gift.forbiddenCategories', label: '禁止品类', placeholder: '如：不送香水' }
])
/** 按点分路径读取结构化需求列表（不存在时返回空数组） */
function requirementList(path: string): string[] {
  const requirement = requirementData.value?.requirement
  if (!requirement) return []
  let current: any = requirement
  for (const segment of path.split('.')) {
    if (current == null) return []
    current = current[segment]
  }
  return Array.isArray(current) ? current : []
}
const provinces = [
  '北京市',
  '天津市',
  '上海市',
  '重庆市',
  '河北省',
  '山西省',
  '辽宁省',
  '吉林省',
  '黑龙江省',
  '江苏省',
  '浙江省',
  '安徽省',
  '福建省',
  '江西省',
  '山东省',
  '河南省',
  '湖北省',
  '湖南省',
  '广东省',
  '海南省',
  '四川省',
  '贵州省',
  '云南省',
  '陕西省',
  '甘肃省',
  '青海省',
  '台湾省',
  '内蒙古自治区',
  '广西壮族自治区',
  '西藏自治区',
  '宁夏回族自治区',
  '新疆维吾尔自治区',
  '香港特别行政区',
  '澳门特别行政区'
]
watch(
  () => editor.province,
  async (province, previousProvince) => {
    if (!province) return
    citiesLoading.value = true
    try {
      cityOptions.value = await api.get('/agent-tasks/region-cities', { params: { province } })
      if (previousProvince && previousProvince !== province) editor.city = ''
      if (cityOptions.value.length === 1) editor.city = cityOptions.value[0]
    } catch (requestError: any) {
      showError(requestError.response?.data?.message || '城市列表加载失败')
    } finally {
      citiesLoading.value = false
    }
  }
)

const parameters = computed<any>(() => parseJson(detail.value?.task.parametersJson, {}))

// 当前任务的行动类型：优先取创建时指定的 preferredActionKinds，缺省按地点见面展示
const currentKind = computed<string>(() => {
  const kinds: any[] = parameters.value?.preferredActionKinds
  if (Array.isArray(kinds) && kinds.length) return String(kinds[0])
  return 'PLACE_VISIT'
})

// 预算显示文本：送礼场景从结构化需求 gift.budgetText 取（前端不传 parameters.budget），
// 地点场景仍用 parameters.budget 数字。统一供任务信息面板与顶部版本摘要使用。
const displayBudgetText = computed<string>(() => {
  if (currentKind.value === 'GIFT_RITUAL') {
    const giftText = requirementData.value?.requirement?.gift?.budgetText
    if (giftText && String(giftText).trim()) return String(giftText).trim()
    // 结构化需求还没加载到时，兜底看 editor.giftBudget
    if (editor.value?.giftBudget && String(editor.value.giftBudget).trim()) {
      return String(editor.value.giftBudget).trim()
    }
  }
  return budgetText(parameters.value.budget)
})

// 仅在地点类任务、且处于等待用户确认阶段时，才展示"换一批候选地点"按钮
const canReshufflePlaces = computed(
  () =>
    currentKind.value === 'PLACE_VISIT' &&
    detail.value?.task?.status === 'AWAITING_CONFIRMATION' &&
    mapCards.value.length > 0
)

// 顶部副标题：不同行动类型走不同的能力链路，文案随之变化
const observerTitle = computed(
  () =>
    ({
      GIFT_RITUAL: '从联网检索到礼物候选，每一步都有证据',
      MESSAGE: '从沟通分析到消息草稿，每一步都有证据',
      SELF_PRACTICE: '从练习设计到复盘标准，每一步都有证据'
    })[currentKind.value] || '从检索到路线，每一步都有证据'
)

// 步骤条按行动类型切换：地点见面走地图检索流程，礼物走联网商品检索，消息/练习走文案生成
const phaseDefinitions = computed(() => {
  const base = [{ code: 'GENERATE', label: '生成最终计划' }]
  switch (currentKind.value) {
    case 'GIFT_RITUAL':
      return [
        { code: 'ANALYZE', label: '分析送礼需求' },
        { code: 'SEARCH', label: '检索礼物候选' },
        { code: 'FILTER', label: '筛选合适礼物' },
        { code: 'ROUTE', label: '准备与送出时机' },
        ...base
      ]
    case 'MESSAGE':
      return [
        { code: 'ANALYZE', label: '分析沟通目标' },
        { code: 'SEARCH', label: '起草消息草稿' },
        { code: 'FILTER', label: '语气与表达检查' },
        { code: 'ROUTE', label: '确定发送时机' },
        ...base
      ]
    case 'SELF_PRACTICE':
      return [
        { code: 'ANALYZE', label: '分析练习目标' },
        { code: 'SEARCH', label: '设计练习内容' },
        { code: 'FILTER', label: '制定节奏与标准' },
        { code: 'ROUTE', label: '准备复盘记录' },
        ...base
      ]
    default:
      return [
        { code: 'ANALYZE', label: '分析行程需求' },
        { code: 'SEARCH', label: '检索展览 / 餐厅' },
        { code: 'FILTER', label: '筛选真实地点' },
        { code: 'ROUTE', label: '计算地点路线' },
        ...base
      ]
  }
})

const evidence = computed<any>(() =>
  parseJson(detail.value?.task.journeyEvidenceJson, {
    places: [],
    routes: [],
    sourceStatus: 'DEGRADED',
    notice: ''
  })
)
const mapCards = computed<any[]>(() =>
  evidence.value.mapCards?.length ? evidence.value.mapCards : evidence.value.places || []
)
const showAllMapCards = ref(false)
const visibleMapCards = computed<any[]>(() =>
  showAllMapCards.value ? mapCards.value : mapCards.value.slice(0, 6)
)
const executionEvents = computed<any[]>(() => detail.value?.executionEvents || [])
const currentVersionEvents = computed<any[]>(() =>
  executionEvents.value.filter((item) => eventVersion(item) === (detail.value?.task.versionNo || 0))
)
const eventBranches = computed<any[]>(() => {
  const grouped = new Map()
  executionEvents.value.forEach((event) => {
    const version = eventVersion(event)
    if (!grouped.has(version)) grouped.set(version, [])
    grouped.get(version).push(event)
  })
  const currentVersion = detail.value?.task.versionNo || 0
  if (!grouped.has(currentVersion)) grouped.set(currentVersion, [])
  return [...grouped.entries()]
    .sort(([left], [right]) => left - right)
    .map(([version, events]) => {
      const analyzeMetadata =
        [...events]
          .reverse()
          .map(eventMetadata)
          .find((meta) => meta.city !== undefined && meta.budget !== undefined) || {}
      const topicMetadata =
        [...events]
          .reverse()
          .map(eventMetadata)
          .find((meta) => meta.topics) || {}
      const isCurrent = version === currentVersion
      const city = analyzeMetadata.city ?? (isCurrent ? parameters.value.city : '') ?? ''
      const budget = analyzeMetadata.budget ?? (isCurrent ? parameters.value.budget : null)
      // 送礼场景当前版本优先从结构化需求读预算文本，避免 parameters.budget 缺失导致"未限定"
      const budgetLabel =
        currentKind.value === 'GIFT_RITUAL' && isCurrent
          ? displayBudgetText.value
          : budgetText(budget)
      const questionCount =
        analyzeMetadata.questionCount ??
        analyzeMetadata.questions?.length ??
        (isCurrent ? originalQuestions.value.length : 0)
      const keywords =
        topicMetadata.topics || (isCurrent ? evidence.value.topics : '') || '等待提取'
      return {
        version,
        events,
        title: version === 0 ? '首次规划' : `第 ${version} 次修改重规划`,
        summary:
          `${city || (currentKind.value === 'PLACE_VISIT' ? '地点待确认' : '不限地点')} · ` +
          `${budgetLabel}` +
          (currentKind.value === 'PLACE_VISIT' ? ` · ${questionCount} 个问题` : '') +
          ` · ${events.length} 条轨迹`,
        keywords,
        ...branchStatus(version, events, isCurrent)
      }
    })
})
const reactState = computed(() => {
  if (
    currentVersionEvents.value.some(
      (item) => item.provider?.includes('MCP') && item.status === 'SUCCEEDED'
    )
  )
    return '已参与'
  if (currentVersionEvents.value.some((item) => item.title.includes('未启用'))) return '未启用'
  return '待调用'
})

// 外部能力状态：AI 对话 key 与网页搜索 key 的运行时状态（正常/未配置/额度耗尽）
const capabilities = ref<any>({ webSearch: {}, aiChat: {} })
function capabilityText(entry: any) {
  switch (entry?.status) {
    case 'OK':
      return '正常'
    case 'NO_KEY':
      return '未配置 key'
    case 'QUOTA_EXHAUSTED':
      return '额度不足'
    case 'AUTH_FAILED':
      return 'key 无效'
    default:
      return '检测中'
  }
}
const aiStatusText = computed(() => capabilityText(capabilities.value.aiChat))
const webStatusText = computed(() => capabilityText(capabilities.value.webSearch))
const aiStatusClass = computed(() =>
  capabilities.value.aiChat?.status === 'OK' ? 'online' : 'offline'
)
const webStatusClass = computed(() =>
  capabilities.value.webSearch?.status === 'OK' ? 'online' : 'offline'
)
async function loadCapabilities() {
  try {
    capabilities.value = await api.get('/agent-tasks/capabilities')
  } catch {
    // 接口失败不阻塞任务页
  }
}
const canCancel = computed(
  () => detail.value && !['SUCCEEDED', 'CANCELLED', 'FAILED'].includes(detail.value.task.status)
)
const isFailed = computed(() => detail.value?.task.status === 'FAILED')
const originalQuestions = computed(() =>
  Array.isArray(parameters.value.questions) ? parameters.value.questions : []
)
const planItems = computed(() => plan.value?.currentItems || [])
const sortedVersions = computed(() =>
  [...(plan.value?.versions || [])].sort((left, right) => right.versionNo - left.versionNo)
)
const enteredQuestions = computed(() =>
  editor.questionsText
    .split(/\n/)
    .map((value) => value.trim())
    .filter(Boolean)
)

// 确认表单副标题：按行动类型说明本轮改什么
const editorHint = computed(
  () =>
    ({
      GIFT_RITUAL: '修改预算、场合与对方喜好后重新分析，AI 会重新推荐具体礼物候选。',
      MESSAGE: '修改渠道、语气、回复期待与边界后，AI 会重新生成消息草稿。',
      SELF_PRACTICE: '修改练习内容、期望效果、频率与边界后，AI 会重新生成练习计划。'
    })[currentKind.value] ||
    '这里的地点、预算和问题清单以最后一次提交为准；应用修改后会新增一个规划分支，并重新提取对应关键词。'
)

// 确认编辑器可编辑的专属字段标签（按"标签：内容"行格式识别）
const EDITABLE_KIND_KEYS = new Set([
  '礼物预算',
  '预算（元）',
  '送礼场合',
  '礼物形式',
  '对方喜好',
  '发送渠道',
  '语气风格',
  '回复期待',
  '明确边界',
  '计划内容',
  '期望效果',
  '频率'
])

// 把非地点任务的专属字段按"标签：内容"行格式拼进 contextNotes（与创建页格式一致）。
// 关键：必须保留创建时填写的"时间约束 / 沟通背景 / 明确边界"等非专属行，
// 只替换编辑器可改的专属行与沟通背景，避免重规划时丢掉用户最初提交的描述。
function buildContextNotes() {
  const lines = []
  // 1. 保留原始说明中的非专属行（含历史"沟通背景""时间约束""明确边界"等行，原样透传不丢）
  const originalLines: string[] = (parameters.value?.contextNotes || '').split(/\n/)
  originalLines.forEach((line) => {
    const idx = line.indexOf('：')
    const key = idx > 0 ? line.slice(0, idx).trim() : ''
    if (EDITABLE_KIND_KEYS.has(key)) return
    if (line.trim()) lines.push(line.trim())
  })
  // 2. 追加当前行动类型的专属字段行
  if (currentKind.value === 'GIFT_RITUAL') {
    if (editor.giftBudget.trim()) lines.push(`礼物预算：${editor.giftBudget.trim()}`)
    if (editor.occasionType) lines.push(`送礼场合：${editor.occasionType}`)
    if (editor.giftForm) lines.push(`礼物形式：${editor.giftForm}`)
    if (editor.recipientPreferences.trim())
      lines.push(`对方喜好：${editor.recipientPreferences.trim()}`)
  } else if (currentKind.value === 'MESSAGE') {
    if (editor.messageChannel) lines.push(`发送渠道：${editor.messageChannel}`)
    if (editor.toneStyle) lines.push(`语气风格：${editor.toneStyle}`)
    if (editor.replyExpectation.trim()) lines.push(`回复期待：${editor.replyExpectation.trim()}`)
    if (editor.boundary.trim()) lines.push(`明确边界：${editor.boundary.trim()}`)
  } else if (currentKind.value === 'SELF_PRACTICE') {
    if (editor.planContent.trim()) lines.push(`计划内容：${editor.planContent.trim()}`)
    if (editor.expectedOutcome.trim()) lines.push(`期望效果：${editor.expectedOutcome.trim()}`)
    if (editor.frequency) lines.push(`频率：${editor.frequency}`)
    if (editor.boundary.trim()) lines.push(`明确边界：${editor.boundary.trim()}`)
  }
  return lines.join('\n')
}

// 从已有 contextNotes 中按"标签：内容"回显专属字段
function hydrateKindFields(notesText: string) {
  const map: Record<string, string> = {}
  ;(notesText || '').split(/\n/).forEach((line) => {
    // 同时兼容中文冒号"："和英文冒号":"
    const idx = line.indexOf('：') > 0 ? line.indexOf('：') : line.indexOf(':')
    if (idx > 0) map[line.slice(0, idx).trim()] = line.slice(idx + 1).trim()
  })
  editor.giftBudget = map['礼物预算'] || ''
  // "预算（元）"行回显到预算数字框：仅当参数里没有预算时采用（避免覆盖后端主字段）
  if (!editor.budget && map['预算（元）']) {
    const amount = Number(map['预算（元）'])
    editor.budget = Number.isFinite(amount) ? amount : map['预算（元）']
  }
  editor.occasionType = map['送礼场合'] || ''
  editor.giftForm = map['礼物形式'] || ''
  editor.recipientPreferences = map['对方喜好'] || ''
  editor.messageChannel = map['发送渠道'] || ''
  editor.toneStyle = map['语气风格'] || ''
  editor.replyExpectation = map['回复期待'] || ''
  editor.boundary = map['明确边界'] || map['边界'] || ''
  editor.planContent = map['计划内容'] || ''
  editor.expectedOutcome = map['期望效果'] || ''
  editor.frequency = map['频率'] || ''
}

// 当前 contextNotes（含本表单修改）与原始值是否一致
const notesChanged = computed(() => buildContextNotes() !== (parameters.value.contextNotes || ''))

const canRevise = computed(() => {
  if (currentKind.value === 'PLACE_VISIT') {
    return (
      Boolean(editor.province) &&
      Boolean(editor.city.trim()) &&
      (editor.province !== (parameters.value.province || '') ||
        editor.city.trim() !== (parameters.value.city || '') ||
        normalizeBudgetValue(editor.budget) !== normalizeBudgetValue(parameters.value.budget) ||
        JSON.stringify(enteredQuestions.value) !== JSON.stringify(originalQuestions.value) ||
        notesChanged.value)
    )
  }
  // 非地点任务：预算走 contextNotes 文本（礼物预算），由 notesChanged 统一覆盖，不再单独比较数字预算
  return notesChanged.value
})
const runningTitle = computed(() =>
  detail.value?.task.currentStep >= 6 ? '正在补充检索并生成计划书' : '正在合并修改并重新规划'
)
const runningHint = computed(() =>
  detail.value?.task.currentStep >= 6
    ? '确认阶段新增问题已合并，系统正在重新提取类别、刷新实时来源并逐项回答。'
    : '地点、预算、最初问题和历次建议已合并，正在重新搜索真实地点。'
)

onMounted(async () => {
  await load()
  loadCapabilities()
  startPolling()
})
onBeforeUnmount(() => {
  clearInterval(timer.value)
  clearTimeout(errorTimer.value)
  if (routeMapUrl.value) URL.revokeObjectURL(routeMapUrl.value)
})

function parseJson(value: string | null | undefined, fallback: any): any {
  try {
    return JSON.parse(value || '')
  } catch {
    return fallback
  }
}
function eventVersion(event: any): number {
  return Number.isInteger(event.taskVersion) ? event.taskVersion : 0
}
function eventMetadata(event: any): any {
  return parseJson(event?.metadataJson, {})
}
function normalizeBudgetValue(value: any): string {
  if (value === null || value === undefined || value === '') return ''
  const amount = Number(value)
  return Number.isFinite(amount) ? String(amount) : String(value).trim()
}
function budgetText(value: any): string {
  const normalized = normalizeBudgetValue(value)
  if (!normalized) return '未限定'
  const amount = Number(normalized)
  return `${Number.isFinite(amount) ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 20 }).format(amount) : normalized} 元`
}
function branchStatus(version: number, events: any[], isCurrent: boolean) {
  if (!isCurrent) return { statusText: '已产生后续修改', statusClass: 'revised' }
  const status = detail.value?.task.status
  if (['RUNNING', 'WAITING', 'RETRY_WAIT'].includes(status))
    return { statusText: '同步执行中', statusClass: 'active' }
  if (status === 'AWAITING_CONFIRMATION') return { statusText: '等待确认', statusClass: 'waiting' }
  if (status === 'SUCCEEDED') return { statusText: '已完成', statusClass: 'done' }
  if (status === 'FAILED' || events.at(-1)?.eventType === 'ERROR')
    return { statusText: '执行异常', statusClass: 'failed' }
  return { statusText: statusText(status), statusClass: '' }
}
function isBranchExpanded(version: number) {
  return expandedBranches.value.has(version)
}
function toggleBranch(version: number) {
  const next = new Set(expandedBranches.value)
  next.has(version) ? next.delete(version) : next.add(version)
  expandedBranches.value = next
}
function startPolling() {
  clearInterval(timer.value)
  if (!detail.value?.task) return
  if (['RUNNING', 'WAITING'].includes(detail.value.task.status))
    timer.value = setInterval(load, 1200)
}
function hydrateEditor() {
  const key = `${detail.value.task.id}:${detail.value.task.versionNo}`
  if (hydratedKey.value === key) return
  editor.province = parameters.value.province || ''
  editor.city = parameters.value.city || ''
  editor.budget = parameters.value.budget ?? null
  editor.questionsText = originalQuestions.value.join('\n')
  hydrateKindFields(parameters.value.contextNotes || '')
  hydratedKey.value = key
}
async function load() {
  const taskId = Number(route.params.id)
  if (!Number.isFinite(taskId) || taskId <= 0) return
  try {
    const next = await api.get(`/agent-tasks/${taskId}`)
    detail.value = next
    loadRouteMap()
    try {
      plan.value = await api.get(`/agent-tasks/${taskId}/plan`)
    } catch {
      plan.value = null
    }
    if (next.task.status === 'AWAITING_CONFIRMATION') hydrateEditor()
    if (next.task.status === 'AWAITING_REQUIREMENT' || next.task.status === 'AWAITING_CONFIRMATION')
      await loadRequirement()
    if (!['RUNNING', 'WAITING'].includes(next.task.status)) clearInterval(timer.value)
  } catch {
    showError('任务读取失败')
  }
}

// ---- 结构化需求检查点（Tier1/Tier2）----
async function loadRequirement() {
  try {
    const response = await api.get(`/agent-tasks/${route.params.id}/requirement`)
    requirementData.value = response || null
    if (!response?.blocked) requirementConfirming.value = false
  } catch {
    requirementData.value = null
  }
}

/** 单条约束局部修改：PATCH 后端 JSON 字段后重新校验（无需整段重写需求） */
async function patchConstraint(path: string, op: string, value: unknown) {
  try {
    const response = await api.patch(`/agent-tasks/${route.params.id}/requirement`, {
      path,
      op,
      value
    })
    requirementData.value = response
    requirementListInput.value[path] = ''
    if (!response?.blocked) {
      requirementConfirming.value = false
      showError('约束已更新，校验通过，可以继续生成方案')
    } else {
      showError('约束已更新，但仍有冲突需要调整')
    }
  } catch (requestError: any) {
    showError(requestError.response?.data?.message || '约束修改失败')
  }
}

/** 列表约束：追加一项 */
async function addListConstraint(path: string) {
  const value = (requirementListInput.value[path] || '').trim()
  if (!value) return
  await patchConstraint(path, 'ADD', value)
}

/** 列表约束：移除一项 */
async function removeListConstraint(path: string, value: string) {
  await patchConstraint(path, 'REMOVE', value)
}

/** 需求检查点确认：核对无误后继续生成方案（SSE 流式推送后续进度） */
async function approveRequirement() {
  if (requirementConfirming.value || requirementData.value?.blocked) return
  requirementConfirming.value = true
  error.value = ''
  startPolling()
  try {
    const stream = await streamSSE(`/agent-tasks/${route.params.id}/requirement/approve`, null, {
      step: load,
      revision: load,
      confirmation: load,
      requirement: (event: any) => {
        requirementData.value = event || requirementData.value
      },
      done: load,
      error: (event: any) => showError(event.message || '任务执行失败'),
      close: async () => {
        await load()
        startPolling()
      },
      transportError: async () => {
        await load()
        startPolling()
      }
    })
    await stream.completed
  } catch (requestError: any) {
    await load()
    showError(requestError.response?.data?.message || requestError.message || '确认失败')
  } finally {
    requirementConfirming.value = false
  }
}
async function loadRouteMap() {
  const routes = evidence.value.routes || []
  const key = `${detail.value?.task.id || ''}:${detail.value?.task.evidenceUpdatedAt || ''}:${routes.length}`
  if (!routes.length || routeMapKey.value === key) return
  routeMapKey.value = key
  routeMapLoading.value = true
  routeMapUnavailable.value = false
  try {
    const blob = await api.blob(`/agent-tasks/${route.params.id}/route-map`)
    if (routeMapUrl.value) URL.revokeObjectURL(routeMapUrl.value)
    routeMapUrl.value = URL.createObjectURL(blob)
  } catch {
    routeMapUnavailable.value = true
  } finally {
    routeMapLoading.value = false
  }
}
function optimisticRun(approved: boolean) {
  if (!detail.value?.task) return
  detail.value.task.status = 'RUNNING'
  detail.value.task.currentStep = approved ? 6 : 1
  detail.value.steps.forEach((step: any) => {
    if (approved) {
      if (step.stepNo === 5)
        Object.assign(step, {
          status: 'COMPLETED',
          detail: '已确认问题清单，正在实时刷新动态检索类别。'
        })
      if (step.stepNo === 6)
        Object.assign(step, {
          status: 'RUNNING',
          detail: '正在提取补充问题关键词、刷新公开信息并逐项回答。'
        })
    } else {
      step.status = step.stepNo === 1 ? 'RUNNING' : 'PENDING'
      step.detail = step.stepNo === 1 ? '正在合并本轮修改与最初要求。' : null
      step.completedAt = null
    }
  })
}
async function confirmTask(approved: boolean) {
  if (submitting.value || (approved && canRevise.value) || (!approved && !canRevise.value)) return
  const taskId = Number(route.params.id)
  if (!Number.isFinite(taskId) || taskId <= 0) return
  submitting.value = true
  error.value = ''
  const isPlace = currentKind.value === 'PLACE_VISIT'
  const payload = {
    approved,
    note: '',
    province: approved || !isPlace ? null : editor.province,
    city: approved || !isPlace ? null : editor.city.trim(),
    budget: approved || !isPlace ? null : (editor.budget ?? '') === '' ? null : editor.budget,
    questions: approved || !isPlace ? null : enteredQuestions.value,
    contextNotes: approved ? null : buildContextNotes()
  }
  optimisticRun(approved)
  startPolling()
  try {
    const stream = await streamSSE(`/agent-tasks/${taskId}/confirm`, payload, {
      step: load,
      revision: load,
      confirmation: load,
      done: load,
      error: (event) => showError(event.message || '任务执行失败'),
      close: async () => {
        await load()
        startPolling()
      },
      transportError: async () => {
        await load()
        startPolling()
      }
    })
    await stream.completed
  } catch (requestError: any) {
    await load()
    if (['RUNNING', 'SUCCEEDED'].includes(detail.value?.task.status)) return
    const message = requestError.response?.data?.message || requestError.message || '提交失败'
    if (!message.includes('任务当前不等待确认') && !message.includes('任务正在运行'))
      showError(message)
  } finally {
    submitting.value = false
  }
}

// 换一批候选地点：后端从已持久化的候选池里按类别均衡随机重抽卡片，
// 不调外部地图接口、不改行程主线与路线；成功后直接刷新详情即可看到新一批卡片。
async function reshufflePlaces() {
  if (reshuffling.value) return
  reshuffling.value = true
  error.value = ''
  try {
    await api.post(`/agent-tasks/${route.params.id}/reshuffle-places`)
    await load()
  } catch (e: any) {
    showError(e?.response?.data?.message || e?.message || '换一批地点失败')
  } finally {
    reshuffling.value = false
  }
}

function phaseStatus(code: string) {
  const events = currentVersionEvents.value.filter((item) => item.phase === code)
  if (!events.length) return 'pending'
  const latest = events.at(-1)
  if (latest.eventType === 'ERROR') return 'failed'
  if (latest.status === 'RUNNING') return 'active'
  return 'done'
}
function phaseHint(code: string) {
  return (
    currentVersionEvents.value.filter((item) => item.phase === code).at(-1)?.title ||
    '等待前一步完成'
  )
}
function eventIcon(event: any) {
  const kind = String(event.eventType || '')
  return (
    (
      {
        THOUGHT: '想',
        ACTION: '行',
        OBSERVATION: '观',
        RESULT: '果',
        WARNING: '!',
        ERROR: '×'
      } as Record<string, string>
    )[kind] || '·'
  )
}
function formatDistance(meters: number) {
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} 公里` : `${meters} 米`
}
function effectiveRouteMode(route: any): string {
  if (route.mode && route.mode !== 'WALKING') return String(route.mode)
  if (route.distanceMeters <= 1800) return 'WALKING'
  if (route.distanceMeters <= 6000) return 'BICYCLING'
  return 'DRIVING'
}
function routeModeText(route: any) {
  return (
    { WALKING: '步行', BICYCLING: '骑行', TRANSIT: '地铁/公交', DRIVING: '驾车' }[
      effectiveRouteMode(route)
    ] || '出行'
  )
}
function routeModeText2(mode: string) {
  return (
    { WALKING: '步行', BICYCLING: '骑行', TRANSIT: '地铁/公交', DRIVING: '驾车' }[mode] || '出行'
  )
}
const goalTypeLabel = (value: string) =>
  ({
    CONNECTION: '增进连接',
    REPAIR: '修复关系',
    BOUNDARY: '建立边界',
    CELEBRATION: '庆祝表达',
    DECISION: '共同决策',
    SELF_GROWTH: '自我成长'
  })[value] || '未指定'
const actionKindLabel = (value: string) =>
  ({
    PLACE_VISIT: '地点见面',
    MESSAGE: '发消息',
    CONVERSATION: '当面沟通',
    GIFT_RITUAL: '礼物',
    SELF_PRACTICE: '自我计划',
    OBSERVATION: '观察记录'
  })[value] || value
const cardKindClass = (value: string) =>
  ({
    PLACE_VISIT: 'card-place',
    MESSAGE: 'card-message',
    CONVERSATION: 'card-conversation',
    GIFT_RITUAL: 'card-gift',
    SELF_PRACTICE: 'card-practice',
    OBSERVATION: 'card-observation'
  })[value] || ''
const itemStatusText = (value: string) =>
  ({ PENDING: '待执行', COMPLETED: '已完成', SKIPPED: '已跳过' })[value] || value || '待执行'
const versionStatusText = (value: string) =>
  ({
    DRAFT: '候选草稿',
    APPROVED: '正式版本',
    SUPERSEDED: '已被新版本取代',
    REJECTED: '已驳回'
  })[value] || value
const planStatusText = (value: string) =>
  ({ DRAFT: '候选计划，待确认', APPROVED: '已确认', ARCHIVED: '已归档' })[value] || value || '—'
const riskText = (value: string) =>
  ({ LOW: '低风险', MEDIUM: '需留意', HIGH: '高风险' })[value] || value || ''
const riskClass = (value: string) => String(value || '').toLowerCase()
function routeNavigationUrl(route: any) {
  const mode = { WALKING: 'walk', BICYCLING: 'ride', TRANSIT: 'bus', DRIVING: 'car' }[
    effectiveRouteMode(route)
  ]
  return route.navigationUrl?.replace(/([?&]mode=)[^&]*/, `$1${mode}`) || '#'
}
function businessStatus(status: string) {
  return { OPEN: '营业中', CLOSED: '已打烊', UNKNOWN: '营业状态待核验' }[status] || '营业状态待核验'
}
function showError(message: string) {
  clearTimeout(errorTimer.value)
  error.value = message
  errorTimer.value = setTimeout(() => {
    error.value = ''
  }, 3200)
}
async function generatePdf() {
  pdfGenerating.value = true
  try {
    detail.value.pdfFile = await api.post(`/agent-tasks/${route.params.id}/pdf`)
  } catch (requestError: any) {
    showError(requestError.response?.data?.message || 'PDF 生成失败，请稍后重试')
  } finally {
    pdfGenerating.value = false
  }
}
async function downloadPdf() {
  pdfDownloading.value = true
  try {
    await api.download(
      `/agent-tasks/${route.params.id}/pdf`,
      `行动计划书-${detail.value.task.title}.pdf`
    )
  } catch (requestError: any) {
    showError(requestError.response?.data?.message || 'PDF 下载失败，请稍后重试')
  } finally {
    pdfDownloading.value = false
  }
}
async function cancel() {
  if (!window.confirm('确认取消这个任务？')) return
  await api.post(`/agent-tasks/${route.params.id}/cancel`)
  await load()
}
async function retryTask() {
  retrying.value = true
  error.value = ''
  startPolling()
  try {
    const stream = await streamSSE(`/agent-tasks/${route.params.id}/run`, null, {
      step: load,
      revision: load,
      confirmation: load,
      requirement: async (event: any) => {
        requirementData.value = event || requirementData.value
        if (event?.blocked) {
          await load()
          startPolling()
        }
      },
      'requirement-conflict': async (event: any) => {
        requirementData.value = event || requirementData.value
        await load()
      },
      done: load,
      error: (event) => showError(event.message || '任务执行失败'),
      close: async () => {
        await load()
        startPolling()
      },
      transportError: async () => {
        await load()
        startPolling()
      }
    })
    await stream.completed
  } catch (requestError: any) {
    await load()
    showError(requestError.response?.data?.message || requestError.message || '重试失败')
  } finally {
    retrying.value = false
  }
}
async function removeTask() {
  if (!window.confirm('删除该行程记录、执行步骤和已生成的 PDF？此操作不可恢复。')) return
  deleting.value = true
  try {
    await api.delete(`/agent-tasks/${route.params.id}`)
    router.push('/plans')
  } catch (requestError: any) {
    showError(requestError.response?.data?.message || '删除失败')
  } finally {
    deleting.value = false
  }
}
function fileSize(bytes: number) {
  return bytes >= 1048576
    ? `${(bytes / 1048576).toFixed(1)} MB`
    : `${Math.max(1, Math.ceil(bytes / 1024))} KB`
}
function statusText(status: string) {
  return (
    {
      WAITING: '准备执行',
      RUNNING: '执行中',
      RETRY_WAIT: '等待重试',
      AWAITING_CONFIRMATION: '等待确认',
      AWAITING_REQUIREMENT: '等待需求确认',
      SUCCEEDED: '已完成',
      FAILED: '执行失败',
      CANCELLED: '已取消'
    }[status] || status
  )
}
function statusClass(status: string) {
  return status === 'SUCCEEDED'
    ? 'green'
    : status === 'AWAITING_CONFIRMATION' || status === 'AWAITING_REQUIREMENT'
      ? 'coral'
      : ''
}
function stepHint(step: any) {
  return step.status === 'RUNNING'
    ? '正在执行…'
    : step.status === 'WAITING_CONFIRMATION'
      ? step.stepNo === 1
        ? '等待你调整需求约束'
        : '等待你的确认'
      : '等待前一步完成'
}
function date(value: string) {
  return new Date(value).toLocaleString('zh-CN')
}
function time(value: string) {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}
</script>

<style scoped>
.agent-observer {
  padding: 28px;
  margin-bottom: 20px;
  background: linear-gradient(145deg, #fffefa 0%, #f5f8f4 100%);
}
.observer-head {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-start;
}
.observer-head h2 {
  margin: 7px 0 6px;
  font-size: 22px;
}
.observer-head p {
  max-width: 680px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}
.capability-badges {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.capability {
  padding: 7px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
.capability.online {
  color: #276143;
  background: #e3f1e8;
}
.capability.offline {
  color: #8a6541;
  background: #f5ead9;
}
.phase-flow {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 25px 0;
}
.phase-flow > i {
  color: #bbb7ae;
  font-style: normal;
}
.phase-card {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  flex: 1;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}
.phase-card > span {
  display: grid;
  place-items: center;
  flex: 0 0 27px;
  height: 27px;
  border-radius: 50%;
  background: #eeece6;
  color: #8f8b82;
  font-size: 11px;
  font-weight: 700;
}
.phase-card div {
  display: grid;
  min-width: 0;
}
.phase-card b {
  font-size: 12px;
  white-space: nowrap;
}
.phase-card small {
  margin-top: 3px;
  overflow: hidden;
  color: var(--muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.phase-card.done {
  border-color: #cfe2d5;
  background: #f5faf6;
}
.phase-card.done > span {
  color: #fff;
  background: var(--green);
}
.phase-card.active {
  border-color: #efb8ad;
  background: #fff6f3;
}
.phase-card.active > span {
  color: #fff;
  background: var(--coral);
  animation: pulse 1.3s infinite;
}
.phase-card.failed {
  border-color: #e6b5ad;
  background: #fff3f1;
}
.phase-card.failed > span {
  color: #fff;
  background: #b44c3f;
}
.event-stream {
  padding: 18px;
  border: 1px solid #e5e2da;
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.72);
}
.stream-title {
  display: flex;
  align-items: center;
  margin-bottom: 10px;
}
.stream-title b {
  font-size: 14px;
}
.stream-title small {
  margin-left: auto;
  color: var(--muted);
  font-size: 11px;
}
.stream-empty {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  color: var(--muted);
  font-size: 12px;
}
.trace-branch {
  position: relative;
  border-top: 1px solid var(--line);
}
.branch-row {
  position: relative;
  width: 100%;
  display: grid;
  grid-template-columns: 4px 30px minmax(180px, 1fr) minmax(150px, 0.8fr) auto 46px;
  align-items: center;
  gap: 10px;
  padding: 13px 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.branch-row:hover .branch-main b {
  color: var(--coral);
}
.branch-line {
  align-self: stretch;
  border-radius: 3px;
  background: #d8ddd9;
}
.trace-branch:last-child .branch-line {
  background: var(--green);
}
.branch-index {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  color: #fff;
  background: #69746c;
  font-size: 11px;
  font-weight: 800;
}
.branch-main {
  display: grid;
  min-width: 0;
}
.branch-main b {
  font-size: 13px;
  transition: 0.2s;
}
.branch-main small,
.branch-keywords {
  margin-top: 3px;
  overflow: hidden;
  color: var(--muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.branch-status {
  padding: 4px 8px;
  border-radius: 999px;
  color: #5c665f;
  background: #edf0ee;
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}
.branch-status.active {
  color: #9b493d;
  background: #fff0ec;
}
.branch-status.waiting {
  color: #8b6134;
  background: #f8ecd8;
}
.branch-status.done {
  color: #2f6646;
  background: #e7f2ea;
}
.branch-status.failed {
  color: #9f4034;
  background: #f9e6e2;
}
.branch-status.revised {
  color: #716e67;
  background: #f0eee9;
}
.branch-toggle {
  color: #8d8981;
  font-size: 10px;
  white-space: nowrap;
}
.branch-events {
  padding: 0 0 6px 44px;
}
.event-card {
  display: grid;
  grid-template-columns: 31px 1fr;
  gap: 11px;
  padding: 13px 0;
  border-top: 1px solid var(--line);
}
.event-icon {
  display: grid;
  place-items: center;
  width: 29px;
  height: 29px;
  border-radius: 9px;
  color: #fff;
  background: #6d766f;
  font-size: 11px;
  font-weight: 800;
}
.event-card.action .event-icon {
  background: var(--coral);
}
.event-card.observation .event-icon {
  background: #52806a;
}
.event-card.result .event-icon {
  background: var(--green);
}
.event-card.warning .event-icon {
  color: #7b582e;
  background: #f1d9ab;
}
.event-card.error .event-icon {
  background: #ad4639;
}
.event-body > div {
  display: flex;
  gap: 12px;
  align-items: center;
}
.event-body b {
  font-size: 13px;
}
.event-body time {
  margin-left: auto;
  color: #aaa69d;
  font-size: 10px;
}
.event-body p {
  margin: 5px 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.event-body footer {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.event-body footer span,
.event-body footer a {
  padding: 3px 7px;
  border-radius: 6px;
  color: #77736b;
  background: #f2f0ea;
  font-size: 10px;
}
.event-body footer a {
  color: var(--coral);
}
.evidence-panel {
  padding: 30px;
  margin-top: 20px;
}
.evidence-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.evidence-head h2 {
  margin: 7px 0 0;
}
.evidence-head > small {
  color: var(--muted);
  font-size: 11px;
}
.evidence-head-side {
  display: flex;
  align-items: center;
  gap: 12px;
}
.evidence-head-side small {
  color: var(--muted);
  font-size: 11px;
}
.btn.ghost {
  background: transparent;
  border: 1px solid var(--border, #d9d9d9);
  color: var(--text, #333);
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
}
.btn.ghost:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.evidence-empty {
  padding: 18px;
  margin-top: 18px;
  border: 1px dashed #d9c6a7;
  border-radius: 12px;
  color: #806a48;
  background: #fff9ee;
  font-size: 12px;
  line-height: 1.6;
}
.evidence-empty code {
  padding: 2px 5px;
  border-radius: 4px;
  background: #f3eadb;
}
.place-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
  margin-top: 20px;
}
.place-more-row {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}
.place-more-btn {
  padding: 8px 20px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: var(--text);
}
.place-more-btn:hover {
  background: var(--soft);
}
.place-grid article {
  position: relative;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fffefa;
}
.map-card > img {
  width: calc(100% + 36px);
  height: 130px;
  margin: -18px -18px 14px;
  border-radius: 14px 14px 0 0;
  object-fit: cover;
}
.place-live-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin: 9px 0;
}
.place-live-meta span {
  padding: 3px 7px;
  border-radius: 999px;
  background: #f2f0ea;
  color: #6f6b63;
  font-size: 10px;
}
.place-live-meta .business-open {
  color: #356247;
  background: #e7f3ea;
}
.place-live-meta .business-closed {
  color: #9c483a;
  background: var(--coral-soft);
}
.card-route {
  padding: 8px 10px;
  margin-top: 11px;
  border-radius: 8px;
  color: #4c6856;
  background: #edf4ef;
  font-size: 10px;
}
.route-map-overview {
  overflow: hidden;
  margin: 14px 0 8px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #f6f5f0;
}
.route-map-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 16px;
  background: #fffefa;
}
.route-map-title > div {
  display: grid;
  gap: 3px;
}
.route-map-title b {
  font-size: 13px;
}
.route-map-title small {
  color: var(--muted);
  font-size: 10px;
}
.route-map-overview > img {
  display: block;
  width: 100%;
  max-height: 460px;
  object-fit: contain;
}
.route-map-placeholder {
  display: grid;
  place-items: center;
  min-height: 220px;
  color: var(--muted);
  font-size: 12px;
}
.place-index {
  display: inline-block;
  padding: 3px 7px;
  border-radius: 5px;
  color: #366247;
  background: #e9f3ec;
  font-size: 10px;
  font-weight: 700;
}
.place-grid h3 {
  margin: 10px 0 6px;
  font-size: 15px;
}
.place-grid p {
  min-height: 36px;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}
.place-grid small {
  display: block;
  margin: 8px 0;
  color: #9b978e;
  font-size: 10px;
}
.place-grid a,
.route-list a {
  color: var(--coral);
  font-size: 11px;
}
.route-list {
  margin-top: 24px;
}
.route-list > h3 {
  font-size: 16px;
}
.route-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--muted, #888);
  margin-left: 6px;
}
.route-list article {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 16px 0;
  border-top: 1px solid var(--line);
}
.route-points {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}
.route-points b {
  font-size: 13px;
}
.route-points span {
  padding: 4px 8px;
  border-radius: 999px;
  color: #51705e;
  background: #edf4ef;
  font-size: 10px;
}
.route-stats {
  display: flex;
  align-items: center;
  gap: 14px;
}
.route-stats strong {
  font-size: 12px;
}
.evidence-notice {
  padding: 12px 14px;
  margin: 18px 0 0;
  border-radius: 10px;
  color: #716b60;
  background: #f4f1e9;
  font-size: 11px;
  line-height: 1.5;
}
.back {
  display: block;
  margin-bottom: 12px;
  color: var(--muted);
  font-size: 13px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
}
.summary-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
  margin-bottom: 20px;
}
.summary-grid h3 {
  margin: 0 0 17px;
  font-size: 16px;
}
.tool-items {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 24px;
}
.steps-panel {
  padding: 28px;
}
.steps-panel > header {
  display: flex;
  align-items: center;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--line);
}
.steps-panel > header div {
  display: grid;
}
.steps-panel > header b {
  font-size: 17px;
}
.steps-panel > header small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 12px;
}
.steps-panel > header > span {
  margin-left: auto;
  font-size: 14px;
}
.timeline {
  padding: 20px 0;
}
.timeline article {
  position: relative;
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 14px;
  min-height: 84px;
}
.timeline article:not(:last-child):before {
  content: '';
  position: absolute;
  left: 17px;
  top: 34px;
  bottom: 0;
  width: 1px;
  background: var(--line);
}
.dot {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 35px;
  height: 35px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: #f8f6f1;
  color: #98958d;
  font-size: 12px;
}
.completed .dot {
  color: white;
  background: var(--green);
  border-color: var(--green);
}
.running .dot {
  color: white;
  background: var(--coral);
  border-color: var(--coral);
  animation: pulse 1.3s infinite;
}
.waiting_confirmation .dot {
  color: #a74738;
  background: var(--coral-soft);
  border-color: #f1c8bf;
}
.timeline b {
  font-size: 14px;
}
.timeline p {
  margin: 6px 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.timeline small {
  color: #aaa79f;
  font-size: 11px;
}
.running-box {
  padding: 18px 20px;
  margin-top: 2px;
  border: 1px solid #d9e6dd;
  border-radius: 14px;
  background: #f1f7f3;
  display: flex;
  align-items: center;
  gap: 14px;
}
.running-box b {
  font-size: 15px;
}
.running-box p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 12px;
}
.spinner {
  width: 24px;
  height: 24px;
  border: 3px solid #c8ddce;
  border-top-color: var(--green);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.confirm-box {
  padding: 22px;
  border: 1px solid #efc8bf;
  border-radius: 15px;
  background: #fdf0ed;
}
.confirm-box > span {
  color: #a84939;
  font-size: 12px;
  font-weight: 700;
}
.confirm-box h3 {
  margin: 8px 0 14px;
  font-size: 18px;
}
/* ---- 需求检查点（Tier1/Tier2）---- */
.requirement-checkpoint {
  border-color: #efd3bf;
  background: #fdf6ef;
}
.requirement-checkpoint > span {
  color: #a86a39;
}
.requirement-conflicts {
  padding: 12px 14px;
  margin-bottom: 12px;
  border: 1px solid #e8b4a4;
  border-radius: 10px;
  background: #fdeae3;
  color: #8c2f1d;
}
.requirement-warnings {
  padding: 12px 14px;
  margin-bottom: 12px;
  border: 1px solid #ecd7a8;
  border-radius: 10px;
  background: #fdf6e0;
  color: #7a5a16;
}
.requirement-issue {
  margin: 6px 0 0;
  font-size: 13.5px;
  line-height: 1.55;
}
.requirement-panel {
  margin: 12px 0;
  border: 1px solid #f0d8c4;
  border-radius: 12px;
  background: #fffefa;
}
.requirement-sections {
  display: grid;
  gap: 16px;
  padding: 16px;
}
.req-section {
  padding: 12px 14px;
  border: 1px dashed #ecd7c4;
  border-radius: 10px;
  background: #fffdf8;
}
.req-section > b {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  color: #7a5a3a;
}
.req-section ul {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 0 8px;
  padding: 0;
  list-style: none;
}
.req-section ul li {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-radius: 999px;
  background: #f7ead9;
  font-size: 12.5px;
  color: #6b4a2a;
}
.req-remove {
  border: 0;
  background: transparent;
  color: #a86a39;
  cursor: pointer;
  font-size: 11px;
  line-height: 1;
}
.req-add {
  display: flex;
  gap: 8px;
  align-items: center;
}
.req-add .input {
  flex: 1;
  min-width: 0;
  font-size: 13px;
}
.req-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}
.req-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12.5px;
  color: #7a5a3a;
}
.req-grid .input {
  font-size: 13px;
}
.requirement-footnote {
  display: block;
  margin-top: 10px;
  color: #9a7a5a;
  font-size: 12px;
  line-height: 1.6;
}
.req-add .btn.small {
  min-height: 32px;
  padding: 5px 12px;
  font-size: 13px;
}
.preview {
  max-height: 420px;
  padding: 18px;
  overflow: auto;
  border: 1px solid #f0d8d1;
  border-radius: 12px;
  background: #fffefa;
}
.plan-cards {
  display: grid;
  gap: 14px;
}
.action-card {
  position: relative;
  overflow: hidden;
  padding: 18px;
  border: 1px solid #f0d8d1;
  border-radius: 14px;
  background: #fffefa;
}
.action-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  background: #d9b8ae;
}
.action-card.card-place::before {
  background: #4e8a6c;
}
.action-card.card-message::before {
  background: #8a6fc2;
}
.action-card.card-conversation::before {
  background: #c28a4e;
}
.action-card.card-gift::before {
  background: #c24e6f;
}
.action-card.card-practice::before {
  background: #4e7fc2;
}
.action-card.card-observation::before {
  background: #6c9a4e;
}
.action-card-head {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.action-card-head b {
  font-size: 15px;
}
.action-kind {
  padding: 4px 9px;
  border-radius: 999px;
  color: #fff;
  background: #9c8379;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
.card-place .action-kind {
  background: #4e8a6c;
}
.card-message .action-kind {
  background: #8a6fc2;
}
.card-conversation .action-kind {
  background: #c28a4e;
}
.card-gift .action-kind {
  background: #c24e6f;
}
.card-practice .action-kind {
  background: #4e7fc2;
}
.card-observation .action-kind {
  background: #6c9a4e;
}
.item-status {
  margin-left: auto;
  padding: 3px 8px;
  border-radius: 999px;
  color: #6f6b63;
  background: #f2f0ea;
  font-size: 11px;
  white-space: nowrap;
}
.action-card-body {
  margin-top: 12px;
  display: grid;
  gap: 7px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}
.action-card-body p,
.action-card-body ul {
  margin: 0;
}
.action-card-body b {
  color: #433f39;
}
.msg-draft {
  margin: 0;
  padding: 12px 14px;
  border-left: 3px solid #8a6fc2;
  border-radius: 8px;
  background: #f6f3fc;
  color: #433f39;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.key-list {
  padding-left: 18px;
}
.pre-line {
  white-space: pre-wrap;
}
.action-card-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #eee2da;
}
.action-card-foot span {
  padding: 3px 8px;
  border-radius: 999px;
  color: #6f6b63;
  background: #f2f0ea;
  font-size: 11px;
}
.action-card-foot .risk {
  margin-left: auto;
}
.action-card-foot .risk.low {
  color: #356247;
  background: #e7f3ea;
}
.action-card-foot .risk.medium {
  color: #8b6134;
  background: #f8ecd8;
}
.action-card-foot .risk.high {
  color: #9f4034;
  background: #f9e6e2;
}
.version-panel {
  margin-top: 20px;
  padding: 30px;
}
.version-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
}
.version-head h2 {
  margin: 7px 0 4px;
  font-size: 20px;
}
.version-head p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}
.version-head > small {
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
}
.version-list {
  display: grid;
  gap: 10px;
  margin-top: 18px;
}
.version-row {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) auto;
  align-items: start;
  gap: 14px;
  padding: 15px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fffefa;
}
.version-row.approved {
  border-color: #cfe2d5;
  background: #f5faf6;
}
.version-row.superseded {
  opacity: 0.72;
}
.version-row.rejected {
  border-color: #f0d0c8;
  background: #fdf3f1;
}
.version-no {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  color: #fff;
  background: #69746c;
  font-size: 12px;
  font-weight: 800;
}
.version-row.approved .version-no {
  background: var(--green);
}
.version-row.rejected .version-no {
  background: #b44c3f;
}
.version-main {
  display: grid;
  gap: 4px;
  min-width: 0;
}
.version-main b {
  font-size: 14px;
}
.version-main small {
  color: var(--muted);
  font-size: 11px;
}
.version-note {
  margin: 2px 0 0;
  color: #9f4034;
  font-size: 12px;
}
.version-status {
  padding: 4px 9px;
  border-radius: 999px;
  color: #5c665f;
  background: #edf0ee;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
.version-row.approved .version-status {
  color: #2f6646;
  background: #e7f2ea;
}
.version-row.rejected .version-status {
  color: #9f4034;
  background: #f9e6e2;
}
@media (max-width: 700px) {
  .version-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .version-row {
    grid-template-columns: 46px minmax(0, 1fr);
  }
  .version-status {
    grid-column: 2;
    justify-self: start;
  }
}
.plan-editor {
  margin-top: 14px;
  padding: 18px;
  border: 1px solid #f0d8d1;
  border-radius: 12px;
  background: #fffaf7;
  display: grid;
  gap: 14px;
}
.editor-title {
  display: grid;
}
.editor-title small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 12px;
}
.plan-editor .textarea {
  background: white;
}
.questions {
  min-height: 100px;
}
.field label small {
  margin-left: 5px;
  color: var(--muted);
  font-weight: 400;
}
.confirm-actions {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  flex-wrap: wrap;
}
.plan-cards-hint {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 12px;
}
aside {
  display: grid;
  align-content: start;
  gap: 18px;
}
aside h3 {
  margin: 0 0 17px;
  font-size: 16px;
}
.task-info {
  display: grid;
  gap: 13px;
}
.task-info div {
  display: flex;
}
.task-info dt {
  color: var(--muted);
  font-size: 12px;
}
.task-info dd {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
}
.tool-log article {
  padding: 11px 0;
  display: flex;
  gap: 10px;
  border-top: 1px solid var(--line);
}
.tool-log article > span {
  color: var(--coral);
}
.tool-log b,
.tool-log small {
  display: block;
  font-size: 12px;
}
.tool-log small,
.muted {
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
}
.search-tip {
  font-size: 11px;
  line-height: 1.6;
}
.idea-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.idea-links a {
  padding: 2px 8px;
  border-radius: 10px;
  border: 1px solid var(--border, #e5e7eb);
  color: var(--primary, #4f6ef7);
  font-size: 11px;
  text-decoration: none;
}
.idea-links a:hover {
  background: rgba(79, 110, 247, 0.08);
}
.idea-sources {
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.6;
}
.idea-sources small {
  color: var(--muted);
  margin-right: 4px;
}
.idea-sources a {
  color: var(--muted);
  margin-right: 10px;
  word-break: break-all;
}
.result {
  margin-top: 20px;
  padding: 30px;
}
.result h2 {
  margin: 9px 0 20px;
}
.result-actions {
  margin-top: 24px;
  display: flex;
  align-items: center;
  gap: 14px;
}
.result-actions span {
  color: var(--muted);
  font-size: 12px;
}
.toast-enter-active,
.toast-leave-active {
  transition: 0.22s;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes pulse {
  50% {
    box-shadow: 0 0 0 6px rgba(239, 106, 84, 0.12);
  }
}
@media (max-width: 850px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
  aside {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 550px) {
  aside {
    grid-template-columns: 1fr;
  }
  .result-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 900px) {
  .phase-flow {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
  .phase-flow > i {
    display: none;
  }
  .observer-head {
    flex-direction: column;
  }
  .capability-badges {
    justify-content: flex-start;
  }
  .route-list article {
    align-items: flex-start;
    flex-direction: column;
  }
  .route-stats {
    flex-wrap: wrap;
  }
}
@media (max-width: 700px) {
  .branch-row {
    grid-template-columns: 4px 30px 1fr auto;
  }
  .branch-keywords {
    grid-column: 3;
  }
  .branch-status {
    grid-row: 1;
    grid-column: 4;
  }
  .branch-toggle {
    grid-row: 2;
    grid-column: 4;
  }
  .branch-events {
    padding-left: 20px;
  }
}
@media (max-width: 550px) {
  .phase-flow {
    grid-template-columns: 1fr;
  }
  .agent-observer,
  .evidence-panel {
    padding: 20px;
  }
  .stream-title {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
  .stream-title small {
    margin-left: 0;
  }
  .event-body > div {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }
  .event-body time {
    margin-left: 0;
  }
  .evidence-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .route-points {
    align-items: flex-start;
    flex-direction: column;
  }
  .place-grid {
    grid-template-columns: 1fr;
  }
}
/* Task detail carries dense operational data, so captions never drop below 12px. */
.agent-observer {
  padding: 32px;
}
.observer-head h2 {
  font-size: 25px;
}
.observer-head p {
  max-width: 820px;
  font-size: 15px;
  line-height: 1.75;
}
.capability {
  padding: 8px 11px;
  font-size: 12px;
}
.phase-card {
  padding: 14px;
}
.phase-card > span {
  flex-basis: 30px;
  height: 30px;
  font-size: 12px;
}
.phase-card b {
  font-size: 14px;
}
.phase-card small {
  font-size: 12px;
}
.event-stream {
  padding: 22px;
}
.stream-title b {
  font-size: 16px;
}
.stream-title small,
.stream-empty {
  font-size: 13px;
}
.branch-row {
  grid-template-columns: 4px 34px minmax(220px, 1.2fr) minmax(180px, 0.8fr) auto 52px;
  padding: 15px 0;
}
.branch-index {
  width: 31px;
  height: 31px;
  font-size: 12px;
}
.branch-main b {
  font-size: 15px;
}
.branch-main small,
.branch-keywords {
  font-size: 12px;
}
.branch-status,
.branch-toggle {
  font-size: 12px;
}
.event-card {
  grid-template-columns: 35px 1fr;
  gap: 12px;
  padding: 15px 0;
}
.event-icon {
  width: 33px;
  height: 33px;
  font-size: 12px;
}
.event-body b {
  font-size: 14px;
}
.event-body time {
  font-size: 12px;
}
.event-body p {
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.event-body footer span,
.event-body footer a {
  padding: 4px 8px;
  font-size: 12px;
}
.evidence-panel {
  padding: 32px;
}
.evidence-head > small {
  font-size: 12px;
}
.evidence-empty {
  font-size: 14px;
}
.place-grid {
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}
.place-grid article {
  padding: 20px;
}
.place-index {
  font-size: 12px;
}
.place-grid h3 {
  font-size: 17px;
}
.place-grid p {
  font-size: 14px;
}
.place-grid small {
  font-size: 12px;
}
.place-grid a,
.route-list a {
  font-size: 13px;
}
.route-list > h3 {
  font-size: 18px;
}
.route-points b {
  font-size: 14px;
}
.route-points span {
  font-size: 12px;
}
.route-stats strong {
  font-size: 14px;
}
.evidence-notice {
  font-size: 13px;
}
.back {
  font-size: 14px;
}
.detail-grid {
  grid-template-columns: minmax(0, 1fr);
  gap: 22px;
}
.steps-panel {
  padding: 30px;
}
.steps-panel > header b {
  font-size: 19px;
}
.steps-panel > header small {
  font-size: 13px;
}
.timeline b {
  font-size: 15px;
}
.timeline p {
  font-size: 14px;
  line-height: 1.7;
}
.timeline small {
  font-size: 12px;
}
.running-box b {
  font-size: 16px;
}
.running-box p {
  font-size: 14px;
}
.confirm-box > span {
  font-size: 13px;
}
.editor-title small {
  font-size: 13px;
}
.summary-grid h3 {
  font-size: 18px;
}
.task-info {
  gap: 15px;
}
.task-info dt,
.task-info dd {
  font-size: 13px;
}
.tool-log b,
.tool-log small {
  font-size: 13px;
}
.tool-log small,
.muted {
  font-size: 12px;
}
.result-actions span {
  font-size: 13px;
}
.timeline .expandable-text {
  margin: 6px 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.7;
}
.event-body .expandable-text {
  margin: 6px 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}
.event-body footer {
  margin-top: 8px;
}
@media (max-width: 850px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
  .summary-grid {
    grid-template-columns: 1fr;
  }
  .agent-observer,
  .evidence-panel,
  .steps-panel {
    padding: 24px;
  }
}
@media (max-width: 550px) {
  .tool-items {
    grid-template-columns: 1fr;
  }
}
/* Keep the final responsive overrides after the dense desktop typography rules above. */
@media (max-width: 700px) {
  :deep(*) {
    min-width: 0;
  }
  .observer-head,
  .evidence-head,
  .route-map-title,
  .stream-title,
  .running-box {
    align-items: flex-start;
    flex-direction: column;
  }
  .agent-observer,
  .evidence-panel,
  .steps-panel {
    padding: 18px;
  }
  .event-stream,
  .confirm-box,
  .plan-editor,
  .preview {
    padding: 14px;
  }
  .phase-flow,
  .summary-grid,
  .tool-items,
  .place-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .branch-row {
    grid-template-columns: 4px 31px minmax(0, 1fr) auto;
    gap: 8px;
  }
  .branch-keywords {
    grid-column: 3 / -1;
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .branch-status {
    grid-row: 1;
    grid-column: 4;
    max-width: 96px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .branch-toggle {
    grid-row: 2;
    grid-column: 4;
  }
  .branch-events {
    padding-left: 8px;
  }
  .event-card {
    grid-template-columns: 33px minmax(0, 1fr);
  }
  .event-body > div,
  .route-list article,
  .route-points,
  .route-stats,
  .result-actions,
  .confirm-actions {
    align-items: stretch;
    flex-direction: column;
  }
  .event-body time,
  .stream-title small {
    margin-left: 0;
  }
  .event-body p,
  .event-body footer,
  .timeline p,
  .route-points b,
  .route-stats small,
  .task-info dd {
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  .confirm-actions .btn,
  .result-actions .btn {
    width: 100%;
    white-space: normal;
  }
  .route-map-overview > img {
    height: auto;
  }
  .toast {
    right: 14px;
    bottom: 14px;
    left: 14px;
  }
}
@media (max-width: 420px) {
  .agent-observer,
  .evidence-panel,
  .steps-panel,
  .summary-grid .panel-pad,
  .result {
    padding: 15px;
  }
  .phase-card b,
  .phase-card small {
    white-space: normal;
  }
  .task-info div {
    align-items: flex-start;
    gap: 12px;
  }
  .task-info dd {
    text-align: right;
  }
}
</style>
