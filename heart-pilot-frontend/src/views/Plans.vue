<template>
  <div class="page-head">
    <div>
      <h1>{{ planningMode ? '行动规划' : '我的计划' }}</h1>
      <p v-if="planningMode">分析目标和约束，生成可确认、可修改、可恢复的行动方案。</p>
      <p v-else>查看历史计划、执行轨迹、计划版本和可下载的正式计划书。</p>
    </div>
    <button v-if="planningMode" class="btn coral" @click="showCreate = true">＋ 创建规划</button>
  </div>
  <section v-if="planningMode" class="agent-brief panel">
    <div>
      <span class="badge green">按行动类型定制</span>
      <h2>每一步都看得见，关键决定由你确认。</h2>
      <p>
        Agent
        会先分析你的目标与约束，再按行动类型调用对应能力：地点见面检索真实餐厅/景点与路线，礼物联网检索公开商品推荐，发消息与自我计划生成定制方案。你确认后才生成最终计划。
      </p>
    </div>
    <div class="mini-flow">
      <span>需求分析</span><i>→</i><span>信息检索</span><i>→</i><span>定制方案</span><i>→</i
      ><span class="confirm">你确认</span><i>→</i><span>方案 / PDF</span>
    </div>
  </section>
  <div v-if="!planningMode" class="task-toolbar">
    <h3>我的任务</h3>
    <select v-model="filter" class="select">
      <option value="">全部状态</option>
      <option value="RUNNING">运行中</option>
      <option value="AWAITING_CONFIRMATION">等待确认</option>
      <option value="SUCCEEDED">已完成</option>
    </select>
  </div>
  <div v-if="!planningMode && !filtered.length" class="panel empty">
    <b>还没有行动计划</b>前往“行动规划”创建一个目标，让 Agent 为你拆解和执行。
  </div>
  <div v-if="!planningMode" class="task-list">
    <div
      v-for="t in filtered"
      :key="t.id"
      class="task-row panel"
      role="link"
      tabindex="0"
      @click="router.push(`/plans/${t.id}`)"
      @keydown.enter="router.push(`/plans/${t.id}`)"
    >
      <span class="task-icon">{{ statusIcon(t.status) }}</span>
      <div>
        <b>{{ t.title }}</b>
        <p>{{ t.objective }}</p>
      </div>
      <div class="progress">
        <span>{{ t.currentStep }}/{{ Math.min(t.maxSteps, 7) }} 步</span
        ><i><em :style="{ width: `${(t.currentStep / 7) * 100}%` }"></em></i>
      </div>
      <span class="badge" :class="statusClass(t.status)">{{ statusText(t.status) }}</span
      ><button
        v-if="!['RUNNING', 'WAITING'].includes(t.status)"
        class="row-delete"
        title="删除行程记录"
        @click.stop="removeTask(t)"
      >
        删除</button
      ><strong>→</strong>
    </div>
  </div>
  <div v-if="planningMode && showCreate" class="modal-backdrop" @click.self="showCreate = false">
    <form class="modal panel" @submit.prevent="create">
      <header>
        <div>
          <span class="eyebrow">新的 Agent 任务</span>
          <h2>你想完成什么？</h2>
        </div>
        <button type="button" @click="showCreate = false">×</button>
      </header>
      <div class="field">
        <label>任务名称</label
        ><input
          v-model="form.title"
          class="input"
          maxlength="140"
          placeholder="例如：南宁周末约会"
        />
      </div>
      <div class="field">
        <label>目标与约束</label
        ><textarea
          v-model="form.objective"
          class="textarea"
          required
          placeholder="例如：周六下午约会，喜欢广西菜和安静散步，不去太吵的商场"
        ></textarea>
      </div>
      <div class="field">
        <label>规划目标 <small>必选</small></label>
        <select v-model="form.goalType" class="select" required>
          <option value="" disabled>请选择规划目标</option>
          <option v-for="(label, value) in goalTypeOptions" :key="value" :value="value">
            {{ label }}
          </option>
        </select>
      </div>
      <div class="field">
        <label>希望生成的行动类型 <small>必选，选择后填写对应信息</small></label>
        <div class="kind-grid">
          <label v-for="(label, value) in actionKindOptions" :key="value" class="kind-box">
            <input v-model="form.actionKind" type="radio" :value="value" />
            <span>{{ label }}</span>
          </label>
        </div>
      </div>

      <!-- 地点见面专属字段 -->
      <div v-if="form.actionKind === 'PLACE_VISIT'" class="location-box">
        <div class="location-title">地点见面，请补充地点与检索约束</div>
        <div class="grid-2">
          <div class="field">
            <label>省 / 直辖市</label>
            <select v-model="form.province" class="select" required>
              <option value="" disabled>请选择省级行政区</option>
              <option v-for="province in provinces" :key="province" :value="province">
                {{ province }}
              </option>
            </select>
          </div>
          <div class="field">
            <label>城市</label>
            <select
              v-model="form.city"
              class="select"
              required
              :disabled="!form.province || citiesLoading"
            >
              <option value="" disabled>{{ citiesLoading ? '加载城市中…' : '请选择城市' }}</option>
              <option v-for="city in cityOptions" :key="city" :value="city">{{ city }}</option>
            </select>
          </div>
        </div>
        <div class="grid-2">
          <div class="field">
            <label>预算（元）</label
            ><input
              v-model.number="form.budget"
              class="input"
              type="number"
              min="0"
              placeholder="500"
            />
          </div>
          <div class="field">
            <label>参与人数</label
            ><input
              v-model.number="form.partySize"
              class="input"
              type="number"
              min="1"
              :placeholder="'2'"
            />
          </div>
        </div>
        <div class="field">
          <label>场所类型 <small>可多选</small></label>
          <div class="tag-row">
            <label v-for="t in venueTypes" :key="t" class="tag-chip">
              <input v-model="form.venueTypes" type="checkbox" :value="t" />
              <span>{{ t }}</span>
            </label>
          </div>
          <input
            v-if="form.venueTypes.includes('其他')"
            v-model="form.customVenue"
            class="input"
            style="margin-top: 10px"
            placeholder="请输入场所关键词，例如：桌游吧、书店、手工坊"
          />
        </div>
        <div class="field">
          <label>希望方案回答的问题 <small>每行一个，可填写多个</small></label
          ><textarea
            v-model="form.questionsText"
            class="textarea questions"
            required
            placeholder="哪家店适合安静聊天？&#10;两个地点之间怎么走？&#10;下雨时有什么室内备选？"
          ></textarea>
        </div>
      </div>

      <!-- 发消息专属字段 -->
      <div v-else-if="form.actionKind === 'MESSAGE'" class="kind-extra-box">
        <div class="location-title">发消息，请补充消息细节</div>
        <div class="grid-2">
          <div class="field">
            <label>发送渠道</label>
            <select v-model="form.messageChannel" class="select">
              <option value="">不限</option>
              <option value="微信">微信</option>
              <option value="短信">短信</option>
              <option value="邮件">邮件</option>
            </select>
          </div>
          <div class="field">
            <label>语气风格</label>
            <select v-model="form.toneStyle" class="select">
              <option value="">不限</option>
              <option value="正式">正式</option>
              <option value="亲切">亲切</option>
              <option value="幽默">幽默</option>
              <option value="委婉">委婉</option>
            </select>
          </div>
        </div>
        <div class="field">
          <label>对方回复期待 <small>希望对方回应什么</small></label
          ><input
            v-model="form.replyExpectation"
            class="input"
            placeholder="例如：希望对方愿意周末出来坐坐"
          />
        </div>
      </div>

      <!-- 礼物专属字段 -->
      <div v-else-if="form.actionKind === 'GIFT_RITUAL'" class="kind-extra-box">
        <div class="location-title">礼物，请补充送礼细节</div>
        <div class="grid-2">
          <div class="field">
            <label>预算范围</label
            ><input v-model="form.giftBudget" class="input" placeholder="例如：200到500元" />
          </div>
          <div class="field">
            <label>场合类型</label>
            <select v-model="form.occasionType" class="select">
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
          <select v-model="form.giftForm" class="select">
            <option value="">不限</option>
            <option value="实物">实物</option>
            <option value="红包">红包</option>
            <option value="体验类活动">体验类活动</option>
          </select>
        </div>
        <div class="field">
          <label>对方喜好或禁忌</label
          ><input
            v-model="form.recipientPreferences"
            class="input"
            placeholder="例如：喜欢喝茶，不送香水"
          />
        </div>
      </div>

      <!-- 自我计划专属字段 -->
      <div v-else-if="form.actionKind === 'SELF_PRACTICE'" class="kind-extra-box">
        <div class="location-title">自我计划，请补充计划内容</div>
        <div class="field">
          <label>计划要做的事或要达成的目标</label
          ><input
            v-model="form.planContent"
            class="input"
            placeholder="例如：练习主动开启对话，控制情绪不急躁"
          />
        </div>
        <div class="grid-2">
          <div class="field">
            <label>期望效果</label
            ><input
              v-model="form.expectedOutcome"
              class="input"
              placeholder="例如：和对方聊天不再紧张"
            />
          </div>
          <div class="field">
            <label>频率</label>
            <select v-model="form.frequency" class="select">
              <option value="">不限</option>
              <option value="每日">每日</option>
              <option value="每周几次">每周几次</option>
            </select>
          </div>
        </div>
      </div>

      <div class="field">
        <label>明确边界 <small>选填，不希望计划做什么</small></label
        ><textarea
          v-model="form.boundary"
          class="textarea"
          placeholder="例如：不推荐太贵的餐厅，不安排太晚的活动，不要让对方有压力"
        ></textarea>
      </div>
      <p v-if="createError" class="form-error">{{ createError }}</p>
      <footer>
        <button type="button" class="btn" @click="showCreate = false">取消</button
        ><button class="btn primary" :disabled="creating || !form.actionKind || !form.goalType">
          {{ creating ? '创建中…' : '创建并开始' }}
        </button>
      </footer>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, streamSSE } from '../api'

interface TaskLite {
  id: number
  title: string
  objective: string
  status: string
  currentStep: number
  maxSteps: number
  [key: string]: unknown
}
interface TaskForm {
  title: string
  objective: string
  goalType: string
  actionKind: string
  boundary: string
  province: string
  city: string
  budget: number | null
  questionsText: string
  partySize: number | null
  venueTypes: string[]
  customVenue: string
  messageChannel: string
  toneStyle: string
  replyExpectation: string
  giftBudget: string
  occasionType: string
  recipientPreferences: string
  giftForm: string
  planContent: string
  expectedOutcome: string
  frequency: string
}
const router = useRouter(),
  route = useRoute(),
  tasks = ref<TaskLite[]>([]),
  filter = ref(''),
  showCreate = ref(false),
  creating = ref(false),
  createError = ref(''),
  cityOptions = ref<string[]>([]),
  citiesLoading = ref(false),
  form = reactive<TaskForm>({
    title: '',
    objective: '',
    goalType: '',
    actionKind: '',
    boundary: '',
    province: '',
    city: '',
    budget: null,
    questionsText: '',
    partySize: null,
    venueTypes: [],
    customVenue: '',
    messageChannel: '',
    toneStyle: '',
    replyExpectation: '',
    giftBudget: '',
    occasionType: '',
    recipientPreferences: '',
    giftForm: '',
    planContent: '',
    expectedOutcome: '',
    frequency: ''
  }),
  provinces = [
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
const filtered = computed(() =>
  filter.value ? tasks.value.filter((x) => x.status === filter.value) : tasks.value
)
const planningMode = computed(() => route.meta.planning === true)
const placeSelected = computed(() => form.actionKind === 'PLACE_VISIT')
const venueTypes: string[] = ['吃饭', '咖啡', '公园', '看展', '散步', '其他']
const goalTypeOptions: Record<string, string> = {
  CONNECTION: '增进连接',
  REPAIR: '修复关系',
  BOUNDARY: '建立边界',
  CELEBRATION: '庆祝表达',
  DECISION: '共同决策',
  SELF_GROWTH: '自我成长'
}
const actionKindOptions: Record<string, string> = {
  PLACE_VISIT: '地点见面',
  MESSAGE: '发消息',
  GIFT_RITUAL: '礼物',
  SELF_PRACTICE: '自我计划'
}
onMounted(() => {
  if (planningMode.value) showCreate.value = true
  else load()
})
// /planning 与 /plans 共用同一个 Plans.vue 组件，Vue Router 切换时会复用实例，
// onMounted 不会再次触发。这里 watch 路由模式变化：从"行动规划"切到"我的计划"时
// 主动重新拉一次任务列表，否则首次点侧边栏"我的计划"会显示空列表，必须刷新才行。
watch(
  () => route.meta.planning === true,
  (isPlanning, wasPlanning) => {
    if (isPlanning) {
      showCreate.value = true
    } else {
      showCreate.value = false
      if (!wasPlanning) return
      load()
    }
  }
)
watch(
  () => form.province,
  async (province) => {
    form.city = ''
    cityOptions.value = []
    if (!province) return
    citiesLoading.value = true
    try {
      cityOptions.value = await api.get('/agent-tasks/region-cities', { params: { province } })
      if (cityOptions.value.length === 1) form.city = cityOptions.value[0]
    } catch (e: any) {
      createError.value = e.response?.data?.message || '城市列表加载失败'
    } finally {
      citiesLoading.value = false
    }
  }
)
async function load() {
  const page = await api.get('/agent-tasks')
  tasks.value = page.content
}
function createIdempotencyKey() {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }

  const bytes = new Uint8Array(16)
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
async function create() {
  creating.value = true
  createError.value = ''
  try {
    if (!form.actionKind) {
      throw new Error('请先选择行动类型')
    }
    if (!form.goalType) {
      throw new Error('请选择规划目标')
    }
    const questions = form.questionsText
      .split(/\n/)
      .map((x) => x.trim())
      .filter(Boolean)
    // 只有选择了地点型行动才要求并传地点、预算与问题；否则后端无需地点检索
    const place = placeSelected.value
    if (place && (!form.province || !form.city.trim())) {
      throw new Error('选择地点见面时需要填写省和城市')
    }
    const extraLines = []
    if (place) {
      if (form.partySize) extraLines.push(`参与人数：${form.partySize}人`)
      if (form.venueTypes.length) {
        const types = form.venueTypes.filter((t) => t !== '其他')
        if (types.length) extraLines.push(`场所偏好：${types.join('、')}`)
        if (form.customVenue) extraLines.push(`其他场所：${form.customVenue}`)
      }
    }
    if (form.actionKind === 'MESSAGE') {
      if (form.messageChannel) extraLines.push(`发送渠道：${form.messageChannel}`)
      if (form.toneStyle) extraLines.push(`语气风格：${form.toneStyle}`)
      if (form.replyExpectation) extraLines.push(`回复期待：${form.replyExpectation}`)
    }
    if (form.actionKind === 'GIFT_RITUAL') {
      if (form.giftBudget) extraLines.push(`礼物预算：${form.giftBudget}`)
      if (form.occasionType) extraLines.push(`送礼场合：${form.occasionType}`)
      if (form.giftForm) extraLines.push(`礼物形式：${form.giftForm}`)
      if (form.recipientPreferences) extraLines.push(`对方喜好：${form.recipientPreferences}`)
    }
    if (form.actionKind === 'SELF_PRACTICE') {
      if (form.planContent) extraLines.push(`计划内容：${form.planContent}`)
      if (form.expectedOutcome) extraLines.push(`期望效果：${form.expectedOutcome}`)
      if (form.frequency) extraLines.push(`频率：${form.frequency}`)
    }
    const background = [form.boundary ? `明确边界：${form.boundary}` : '', ...extraLines]
      .filter(Boolean)
      .join('\n')
    const key = createIdempotencyKey()
    const t = await api.post(
      '/agent-tasks',
      {
        title: form.title,
        objective: form.objective,
        parameters: {
          province: place ? form.province : '',
          city: place ? form.city.trim() : '',
          budget: place ? form.budget : null,
          questions: place ? questions : [],
          // 场所类型多选与"其他"自定义词单独结构化透传，供 LLM 关键词提取器直接取用，
          // 不再只埋在 contextNotes 文本里被规则切句稀释
          venueTypes: place ? form.venueTypes : undefined,
          customVenue: place && form.customVenue ? form.customVenue : undefined,
          goalType: form.goalType || undefined,
          preferredActionKinds: [form.actionKind],
          // 消息行动：渠道/语气/回复期待单独透传，供后端结构化读取；contextNotes 仍保留文本行用于展示
          messageChannel:
            form.actionKind === 'MESSAGE' ? form.messageChannel || undefined : undefined,
          toneStyle: form.actionKind === 'MESSAGE' ? form.toneStyle || undefined : undefined,
          replyExpectation:
            form.actionKind === 'MESSAGE' ? form.replyExpectation || undefined : undefined,
          contextNotes: background || undefined
        }
      },
      { headers: { 'Idempotency-Key': key } }
    )
    showCreate.value = false
    if (!t?.id) throw new Error('任务创建成功但未返回 ID，请刷新列表查看')
    // 触发任务在后台运行，不等待完成，立刻跳转到详情页看生成进度
    streamSSE(`/agent-tasks/${t.id}/run`, null, {}).catch(() => {})
    router.push(`/plans/${t.id}`)
  } catch (e: any) {
    createError.value = e.response?.data?.message || e.message || '任务创建失败'
  } finally {
    creating.value = false
  }
}
async function removeTask(t: TaskLite) {
  if (!confirm(`删除「${t.title}」、执行记录和已生成的 PDF？`)) return
  await api.delete(`/agent-tasks/${t.id}`)
  tasks.value = tasks.value.filter((item) => item.id !== t.id)
}
function statusText(s: string) {
  return (
    {
      WAITING: '待启动',
      RUNNING: '运行中',
      AWAITING_CONFIRMATION: '等待确认',
      RETRY_WAIT: '等待重试',
      SUCCEEDED: '已完成',
      FAILED: '失败',
      CANCELLED: '已取消'
    }[s] || s
  )
}
function statusIcon(s: string) {
  return s === 'SUCCEEDED'
    ? '✓'
    : s === 'AWAITING_CONFIRMATION'
      ? '!'
      : s === 'RUNNING'
        ? '↻'
        : '↗'
}
function statusClass(s: string) {
  return s === 'SUCCEEDED' ? 'green' : s === 'AWAITING_CONFIRMATION' ? 'coral' : ''
}
</script>

<style scoped>
.agent-brief {
  padding: 36px;
  display: flex;
  align-items: center;
  gap: 44px;
  background: linear-gradient(120deg, #fffefa, #f2f0e9);
}
.agent-brief > div:first-child {
  max-width: 760px;
}
.agent-brief h2 {
  margin: 14px 0 9px;
  font-size: 26px;
}
.agent-brief p {
  margin: 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.8;
}
.mini-flow {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 9px;
}
.mini-flow span {
  padding: 10px 12px;
  border-radius: 8px;
  background: white;
  border: 1px solid var(--line);
  font-size: 13px;
  font-weight: 600;
}
.mini-flow .confirm {
  color: #a94b3d;
  background: var(--coral-soft);
}
.mini-flow i {
  color: #aaa69c;
  font-style: normal;
}
.task-toolbar {
  margin: 34px 0 16px;
  display: flex;
  align-items: center;
}
.task-toolbar h3 {
  margin: 0;
  font-size: 19px;
}
.task-toolbar .select {
  width: 170px;
  margin-left: auto;
  padding: 8px 10px;
}
.task-list {
  display: grid;
  gap: 12px;
}
.task-row {
  padding: 20px 22px;
  display: grid;
  grid-template-columns: 46px 1fr 150px auto auto 22px;
  align-items: center;
  gap: 16px;
  cursor: pointer;
  transition: 0.2s;
}
.task-row:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 25px rgba(30, 30, 28, 0.06);
}
.task-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: #eeece5;
  color: #6a685f;
}
.task-row b {
  font-size: 16px;
}
.task-row p {
  max-width: 760px;
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.progress span {
  color: var(--muted);
  font-size: 12px;
}
.progress i {
  display: block;
  height: 5px;
  margin-top: 6px;
  border-radius: 4px;
  background: #ece9e1;
  overflow: hidden;
}
.progress em {
  display: block;
  height: 100%;
  background: var(--coral);
}
.row-delete {
  min-height: 34px;
  padding: 6px;
  border: 0;
  background: transparent;
  color: #a45548;
  font-size: 13px;
}
.task-row > strong {
  color: #aaa79e;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
  overflow: hidden;
  background: rgba(27, 27, 25, 0.42);
  backdrop-filter: blur(5px);
}
.modal {
  width: min(640px, 100%);
  max-height: calc(100dvh - 40px);
  padding: 32px;
  display: grid;
  gap: 19px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-color: #aaa69c #eeece5;
  scrollbar-width: thin;
  -webkit-overflow-scrolling: touch;
}
.modal::-webkit-scrollbar {
  width: 7px;
}
.modal::-webkit-scrollbar-track {
  border-radius: 10px;
  background: #eeece5;
}
.modal::-webkit-scrollbar-thumb {
  border-radius: 10px;
  background: #aaa69c;
}
.modal header {
  display: flex;
}
.modal header h2 {
  margin: 7px 0 22px;
}
.modal header button {
  margin-left: auto;
  align-self: start;
  border: 0;
  background: transparent;
  font-size: 24px;
}
.modal footer {
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  margin-top: 6px;
}
.form-error {
  margin: 0;
  color: #b23f31;
  font-size: 14px;
}
@media (max-width: 850px) {
  .agent-brief {
    align-items: flex-start;
    flex-direction: column;
  }
  .mini-flow {
    margin: 0;
    flex-wrap: wrap;
  }
  .task-row {
    grid-template-columns: 44px 1fr auto;
  }
  .task-row .progress {
    display: none;
  }
  .task-row > .badge {
    grid-column: 2;
  }
  .row-delete {
    grid-column: 2;
    justify-self: start;
  }
  .task-row > strong {
    grid-row: 1;
    grid-column: 3;
  }
}
@media (max-width: 500px) {
  .agent-brief {
    padding: 24px;
  }
  .mini-flow i {
    display: none;
  }
  .modal {
    width: 100%;
    max-height: calc(100dvh - 24px);
    padding: 24px 18px;
    gap: 17px;
  }
  .modal-backdrop {
    place-items: center;
    padding: 12px;
  }
  .modal footer {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr);
    padding-bottom: max(2px, env(safe-area-inset-bottom));
  }
  .modal footer .btn {
    width: 100%;
    min-width: 0;
  }
}
.questions {
  min-height: 115px;
}
.field label small {
  margin-left: 6px;
  color: var(--muted);
  font-weight: 400;
}
.kind-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
}
.kind-box {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fffefa;
  cursor: pointer;
  font-size: 13px;
  transition: 0.15s;
}
.kind-box:hover {
  border-color: #e6b8ae;
}
.kind-box:has(input:checked) {
  border-color: var(--coral);
  background: var(--coral-soft);
  color: #9b4637;
  font-weight: 600;
}
.kind-box input {
  accent-color: var(--coral);
}
.location-box {
  padding: 16px;
  border: 1px solid #efd0c7;
  border-radius: 12px;
  background: #fff8f4;
  display: grid;
  gap: 14px;
}
.kind-extra-box {
  padding: 16px;
  border: 1px solid #e8e4da;
  border-radius: 12px;
  background: #faf8f3;
  display: grid;
  gap: 14px;
}
.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: white;
  cursor: pointer;
  font-size: 13px;
  transition: 0.15s;
}
.tag-chip:hover {
  border-color: #e6b8ae;
}
.tag-chip:has(input:checked) {
  border-color: var(--coral);
  background: var(--coral-soft);
  color: #9b4637;
  font-weight: 600;
}
.tag-chip input {
  accent-color: var(--coral);
}
.location-title {
  color: #a0493b;
  font-size: 12px;
  font-weight: 700;
}
@media (max-width: 600px) {
  .kind-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
