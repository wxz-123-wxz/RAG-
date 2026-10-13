<script setup lang="ts">
import { ArrowDown, ArrowUp, BookOpenText, Check, Copy, FileText, History, Plus, RotateCcw, Search, ThumbsDown, ThumbsUp } from 'lucide-vue-next'
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http, getErrorMessage, type ApiResponse } from '@/api/http'
import { getKnowledgeBaseDisplayName } from '@/api/knowledge'
import AnswerContent from '@/components/AnswerContent.vue'
import EvidenceSources from '@/components/EvidenceSources.vue'

interface Citation {
  number: number
  documentTitle: string
  heading?: string
  pageStart?: number
  quote: string
}

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  status?: 'answered' | 'refused' | 'error'
  citations?: Citation[]
  questionId?: string
  persisted?: boolean
  model?: string
  answerMode?: string
  answerOrigin?: string
  evidenceState?: string
  helpful?: boolean
  feedbackSaving?: boolean
  citationsLoading?: boolean
  citationsError?: boolean
  retryQuestion?: string
  retryMode?: string
}

interface AssistantResponse {
  id: string; content: string; status: string; citations: Citation[]; model: string
  answerOrigin: string; evidenceState: string
}

const selectedKnowledgeBase = ref('all')
const knowledgeBases = ref<Array<{ id: string; name: string; courseName: string; status: string }>>([])
const conversationId = ref<string | null>(null)
const question = ref('')
const sending = ref(false)
const messages = ref<Message[]>([])
const chatConfigured = ref(false)
const chatModel = ref('')
const capabilitiesUnavailable = ref(false)
const capabilitiesLoading = ref(false)
const basesLoading = ref(false)
const basesError = ref(false)
const scrollPane = ref<HTMLElement | null>(null)
const evidencePane = ref<HTMLElement | null>(null)
const mobileEvidencePane = ref<HTMLElement | null>(null)
const mobileViewport = ref(false)
const input = ref<{ focus: () => void } | null>(null)
const evidenceOpen = ref(false)
const evidenceMessageId = ref<string | null>(null)
const activeCitation = ref<number | null>(null)
const copiedId = ref('')
const nearBottom = ref(true)
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyError = ref(false)
const openingId = ref<string | null>(null)
const conversations = ref<Array<{ id: string; title: string; knowledgeBaseId?: string | null }>>([])
const busy = computed(() => sending.value || openingId.value !== null)
const activeAnswer = computed(() => messages.value.find(m => m.id === evidenceMessageId.value)
  ?? [...messages.value].reverse().find(m => m.role === 'assistant'))
const activeCitations = computed(
  () => activeAnswer.value?.citations ?? [],
)
const latestAnswer = computed(() => [...messages.value].reverse().find(m => m.role === 'assistant'))
let copyTimer: ReturnType<typeof setTimeout> | undefined
let mediaQuery: MediaQueryList | undefined
let disposed = false
let evidenceTrigger: HTMLElement | null = null

function updateViewport() {
  mobileViewport.value = mediaQuery?.matches ?? false
  if (!mobileViewport.value) evidenceOpen.value = false
}

function answerLabel(message: Message) {
  if (message.status === 'error') return '请求失败'
  if (message.status === 'refused') return '依据不足'
  if (message.answerOrigin === 'GENERAL') return '通用知识 · 未联网核验'
  if (message.model === 'extractive-no-llm') return '原文摘录'
  if (!message.model) return '历史回答 · 模式未记录'
  return message.evidenceState === 'MODEL_REPORTED_PARTIAL' ? '模型判断：部分依据' : '依据资料生成'
}
function onScroll() {
  const pane = scrollPane.value
  nearBottom.value = !pane || pane.scrollHeight - pane.scrollTop - pane.clientHeight < 100
}
async function scrollToLatest(force = false) {
  const shouldScroll = force || nearBottom.value
  await nextTick()
  if (shouldScroll) scrollPane.value?.scrollTo({ top: scrollPane.value.scrollHeight, behavior: 'auto' })
  onScroll()
}
function focusEvidence() {
  const pane = mobileViewport.value ? mobileEvidencePane.value : evidencePane.value
  const target = activeCitation.value ? pane?.querySelector<HTMLElement>(`[data-source="${activeCitation.value}"]`) : null
  target?.scrollIntoView({ block: 'nearest', behavior: 'auto' })
  ;(target ?? pane)?.focus({ preventScroll: true })
}
async function loadCitations(message: Message | undefined) {
  if (!message || message.citationsLoading || !message.persisted || message.answerOrigin === 'GENERAL') return
  message.citationsLoading = true
  message.citationsError = false
  try { message.citations = (await http.get<ApiResponse<Citation[]>>(`/messages/${message.id}/citations`)).data.data }
  catch { message.citationsError = true }
  finally { message.citationsLoading = false }
}
async function showEvidence(message: Message | undefined, number?: number) {
  if (!message) return
  if (document.activeElement instanceof HTMLElement) evidenceTrigger = document.activeElement
  evidenceMessageId.value = message.id
  activeCitation.value = number ?? null
  if (mobileViewport.value) evidenceOpen.value = true
  if (message.citations === undefined || message.citationsError) await loadCitations(message)
  await nextTick()
  if (!mobileViewport.value) focusEvidence()
}
function restoreEvidenceFocus() { if (evidenceTrigger?.isConnected) evidenceTrigger.focus({ preventScroll: true }) }
async function copyAnswer(message: Message) {
  try {
    await navigator.clipboard.writeText(message.content)
    copiedId.value = message.id
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copiedId.value = '' }, 2500)
    ElMessage.success('回答已复制')
  }
  catch { ElMessage.error('复制失败，请选择回答文本复制') }
}
function appendAnswer(answer: AssistantResponse, questionId?: string, mode?: string) {
  messages.value.push({ ...answer, role: 'assistant', status: answer.status === 'REFUSED' ? 'refused' : 'answered',
    questionId, answerMode: mode, persisted: true })
  evidenceMessageId.value = answer.id
  activeCitation.value = null
  void scrollToLatest()
}

async function loadKnowledgeBases() {
  basesLoading.value = true
  basesError.value = false
  try {
    const response = await http.get<ApiResponse<Array<{ id: string; name: string; courseName: string; status: string }>>>('/knowledge-bases')
    knowledgeBases.value = response.data.data.filter((base) => base.status === 'PUBLISHED')
    if (selectedKnowledgeBase.value !== 'all' && !knowledgeBases.value.some(base => base.id === selectedKnowledgeBase.value)) selectedKnowledgeBase.value = 'all'
  } catch (error) {
    knowledgeBases.value = []
    basesError.value = true
    ElMessage.error(getErrorMessage(error, '知识库加载失败'))
  } finally {
    basesLoading.value = false
  }
}

async function ensureConversation(content: string) {
  if (conversationId.value) return conversationId.value
  const response = await http.post<ApiResponse<{ id: string }>>('/conversations', {
    knowledgeBaseId: selectedKnowledgeBase.value,
    title: content.slice(0, 40),
  })
  conversationId.value = response.data.data.id
  return conversationId.value
}

async function loadCapabilities() {
  if (capabilitiesLoading.value) return
  capabilitiesLoading.value = true
  capabilitiesUnavailable.value = false
  try {
    const capabilities = (await http.get<ApiResponse<{ answerMode: string; chatConfigured: boolean; chatModel: string }>>('/system/capabilities')).data.data
    chatConfigured.value = capabilities.chatConfigured
    chatModel.value = capabilities.chatModel
  }
  catch { capabilitiesUnavailable.value = true; chatConfigured.value = false }
  finally { capabilitiesLoading.value = false }
}
onMounted(() => {
  mediaQuery = window.matchMedia('(max-width: 1100px)')
  updateViewport()
  mediaQuery.addEventListener('change', updateViewport)
  void loadKnowledgeBases()
  void loadCapabilities()
})
onUnmounted(() => {
  disposed = true
  clearTimeout(copyTimer)
  mediaQuery?.removeEventListener('change', updateViewport)
})

const suggestions = computed(() => {
  return [
    '需求分析通常包含哪些工作？',
    '单一职责原则是什么？',
    '如何设计一份有效的测试用例？',
    '代码审查应该重点关注哪些方面？',
  ]
})

function startConversation() {
  if (busy.value) return
  messages.value = []
  question.value = ''
  conversationId.value = null
  evidenceMessageId.value = null
  evidenceOpen.value = false
  activeCitation.value = null
  nearBottom.value = true
  void nextTick(() => input.value?.focus())
}

function useSuggestion(value: string) {
  question.value = value
  input.value?.focus()
}

async function sendQuestion() {
  const content = question.value.trim()
  if (!content || busy.value || !knowledgeBases.value.length || !selectedKnowledgeBase.value || !chatConfigured.value) return
  const localId = crypto.randomUUID()
  const requestedMode = 'generated'
  messages.value.push({ id: localId, role: 'user', content })
  question.value = ''
  sending.value = true
  void scrollToLatest(true)
  try {
    const id = await ensureConversation(content)
    const response = await http.post<ApiResponse<{ userMessageId: string; assistantMessage: AssistantResponse }>>(`/conversations/${id}/messages`, { content, answerMode: requestedMode })
    appendAnswer(response.data.data.assistantMessage, response.data.data.userMessageId, requestedMode)
  } catch (error) {
    const failedId = crypto.randomUUID()
    messages.value.push({ id: failedId, role: 'assistant', content: getErrorMessage(error, '问答服务暂时不可用，请稍后重试。'), status: 'error', citations: [], retryQuestion: content, retryMode: requestedMode })
    evidenceMessageId.value = failedId
    if (!question.value) question.value = content
  } finally {
    sending.value = false
    void scrollToLatest()
  }
}
function editFailedQuestion(message: Message) {
  if (busy.value) return
  question.value = message.retryQuestion ?? ''
  input.value?.focus()
}
async function feedback(message: Message, helpful: boolean) {
  if (message.feedbackSaving || busy.value || message.helpful === helpful) return
  message.feedbackSaving = true
  try { await http.put(`/messages/${message.id}/feedback`, { helpful }); message.helpful = helpful; ElMessage.success('反馈已保存') }
  catch (error) { ElMessage.error(getErrorMessage(error)) }
  finally { message.feedbackSaving = false }
}
async function loadFeedback(message: Message) {
  if (!message.persisted || message.role !== 'assistant') return
  try {
    const response = await http.get<ApiResponse<{ helpful?: boolean | null } | null>>(`/messages/${message.id}/feedback`)
    const helpful = response.data.data?.helpful
    if (helpful !== null && helpful !== undefined) message.helpful = helpful
  } catch {
    // 反馈读取失败不影响历史回答显示，用户仍可重新提交反馈。
  }
}
async function regenerate(message: Message) {
  if (!message.questionId || busy.value || message.feedbackSaving || !chatConfigured.value) return
  sending.value = true
  try {
    const response = await http.post<ApiResponse<{ assistantMessage: { id: string; content: string; status: string; citations: Citation[]; model: string; answerOrigin: string; evidenceState: string } }>>(`/messages/${message.questionId}/regenerations`, { answerMode: 'generated' }, { timeout: 40_000 })
    const answer = response.data.data.assistantMessage
    Object.assign(message, { id: answer.id, content: answer.content, citations: answer.citations, citationsError: false, model: answer.model, answerOrigin: answer.answerOrigin, evidenceState: answer.evidenceState, helpful: undefined, status: answer.status === 'REFUSED' ? 'refused' : 'answered' })
    evidenceMessageId.value = answer.id
    activeCitation.value = null
  } catch (error) { ElMessage.error(getErrorMessage(error)) } finally { sending.value = false }
}

async function generalAnswer(message: Message) {
  if (busy.value || !chatConfigured.value || !message.persisted || message.role !== 'assistant'
    || message.answerOrigin === 'GENERAL' || message.status === 'error') return
  if (disposed || !messages.value.includes(message)) return
  sending.value = true
  try {
    const response = await http.post<ApiResponse<{ assistantMessage: { id: string; content: string; model: string; answerOrigin: string; evidenceState: string } }>>(`/messages/${message.id}/general-answer`, { confirm: true })
    const answer = response.data.data.assistantMessage
    appendAnswer({ ...answer, status: 'ANSWERED', citations: [] }, undefined, 'general')
  } catch (error) { ElMessage.error(getErrorMessage(error)) }
  finally { sending.value = false }
}

async function showHistory() {
  if (busy.value || historyLoading.value) return
  historyOpen.value = true
  historyLoading.value = true
  historyError.value = false
  try { conversations.value = (await http.get<ApiResponse<typeof conversations.value>>('/conversations')).data.data }
  catch (error) { historyError.value = true; ElMessage.error(getErrorMessage(error)) }
  finally { historyLoading.value = false }
}
async function openConversation(id: string) {
  if (busy.value || historyLoading.value) return
  openingId.value = id
  try {
    interface StoredMessage { id: string; role: string; content: string; parentMessageId?: string; answerStatus?: string; modelName?: string; answerOrigin?: string; evidenceState?: string }
    const detail = (await http.get<ApiResponse<{ conversation: { knowledgeBaseId?: string | null }; messages: StoredMessage[] }>>(`/conversations/${id}`)).data.data
    if (detail.conversation.knowledgeBaseId != null && !knowledgeBases.value.some(base => base.id === detail.conversation.knowledgeBaseId)) {
      ElMessage.warning('该会话的知识库未发布或当前不可访问'); return
    }
    const loaded: Message[] = detail.messages.map(m => ({
      id: m.id, role: m.role === 'USER' ? 'user' : 'assistant', content: m.content,
      status: m.answerStatus === 'REFUSED' ? 'refused' : 'answered', persisted: m.role === 'ASSISTANT',
      model: m.modelName, answerOrigin: m.answerOrigin, evidenceState: m.evidenceState,
      questionId: m.answerOrigin === 'GENERAL' ? undefined : m.parentMessageId,
      answerMode: m.modelName === 'extractive-no-llm' ? 'extractive' : 'generated',
      citations: m.role === 'ASSISTANT' && m.answerOrigin !== 'GENERAL' && m.answerStatus !== 'REFUSED' ? undefined : [],
    }))
    messages.value = loaded
    void Promise.all(loaded.filter((message) => message.role === 'assistant').map(loadFeedback))
    conversationId.value = id
    selectedKnowledgeBase.value = detail.conversation.knowledgeBaseId ?? 'all'
    evidenceMessageId.value = null
    evidenceOpen.value = false
    question.value = ''
    activeCitation.value = null
    historyOpen.value = false
    if (latestAnswer.value?.citations === undefined) void loadCitations(latestAnswer.value)
    void scrollToLatest(true)
  } catch (error) { ElMessage.error(getErrorMessage(error, '会话加载失败')) }
  finally { openingId.value = null }
}
</script>

<template>
  <div class="chat-workspace">
    <section class="conversation-pane">
      <header class="conversation-toolbar">
        <el-select v-model="selectedKnowledgeBase" class="knowledge-select" aria-label="检索范围" placeholder="选择检索范围" :loading="basesLoading" :disabled="busy || basesLoading" @change="startConversation">
          <template #prefix><BookOpenText :size="17" /></template>
          <el-option label="全部可访问知识库" value="all" />
          <el-option v-for="base in knowledgeBases" :key="base.id" :label="getKnowledgeBaseDisplayName(base.id, knowledgeBases)" :value="base.id" />
        </el-select>
        <div class="conversation-tools">
          <button class="icon-button bordered" aria-label="历史会话" title="历史会话" :disabled="busy" @click="showHistory"><History :size="18" /></button>
          <button class="icon-button bordered" aria-label="新建会话" title="新建会话" :disabled="busy" @click="startConversation"><Plus :size="19" /></button>
        </div>
      </header>

      <div class="answer-mode-bar">
        <span>知识库问答</span>
        <div class="model-status" role="status">
          <span>{{ capabilitiesLoading ? '正在读取模型配置…' : capabilitiesUnavailable ? '模型配置读取失败' : chatConfigured ? `已配置：${chatModel}` : '未配置外部模型' }}</span>
          <button v-if="capabilitiesUnavailable || !chatConfigured" class="text-action" :disabled="busy || capabilitiesLoading" @click="loadCapabilities">刷新配置</button>
        </div>
      </div>
      <p v-if="!capabilitiesLoading && !chatConfigured" class="mode-explanation" role="alert">{{ capabilitiesUnavailable ? '暂时无法读取模型配置，请刷新配置后重试。' : '外部模型尚未配置，暂时无法生成回答。' }}</p>

      <div ref="scrollPane" class="message-scroll" aria-label="对话消息" tabindex="0" @scroll="onScroll">
        <div v-if="basesLoading" class="chat-state" role="status">正在加载知识库…</div>
        <div v-else-if="basesError" class="chat-state" role="alert"><h2>知识库加载失败</h2><p>请检查服务连接后重试。</p><el-button @click="loadKnowledgeBases">重新加载</el-button></div>
        <div v-else-if="!knowledgeBases.length" class="chat-state"><BookOpenText :size="30" /><h2>暂无可提问的知识库</h2><p>请先上传资料、完成索引并发布文档及知识库，或联系管理员开通访问权限。</p></div>
        <div v-else-if="messages.length === 0" class="question-start">
          <div class="start-symbol"><Search :size="27" /></div>
          <h2>检索课程资料</h2>
          <p class="start-description">{{ selectedKnowledgeBase === 'all' ? '软件工程资料统一检索，有依据的回答附原文来源。' : '检索当前知识库，由模型依据资料整理回答并标出引用。' }}</p>
          <div class="suggestion-list">
            <button v-for="item in suggestions" :key="item" @click="useSuggestion(item)">
              {{ item }}
              <ArrowUp :size="16" />
            </button>
          </div>
        </div>

        <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
          <div class="answer-meta">
            <span class="message-label">{{ message.role === 'user' ? '你的问题' : message.status === 'error' ? '请求未完成' : message.answerOrigin === 'GENERAL' ? '通用知识补充 · 非知识库来源' : '知识库回答' }}</span>
            <span v-if="message.role === 'assistant'" class="answer-badge" :class="{ warning: message.status === 'refused' || message.evidenceState === 'MODEL_REPORTED_PARTIAL' || message.answerOrigin === 'GENERAL', error: message.status === 'error' }">{{ answerLabel(message) }}</span>
          </div>
          <AnswerContent :content="message.content" :plain="message.role === 'user' || message.model === 'extractive-no-llm' || message.status === 'error' || message.status === 'refused'" :citation-numbers="message.citations?.map(c => c.number)" @citation="showEvidence(message, $event)" />
          <p v-if="message.evidenceState === 'MODEL_REPORTED_COMPLETE'" class="answer-note">模型判断资料可覆盖问题；引用是否支持结论，仍需核对原文。</p>
          <p v-if="message.evidenceState === 'MODEL_REPORTED_PARTIAL'" class="answer-note partial-warning">资料仅部分覆盖问题。未覆盖的内容不能视为知识库结论。</p>
          <p v-if="message.answerOrigin === 'GENERAL'" class="answer-note partial-warning">非知识库来源，未联网核验；不计入知识库统计和检索评测。</p>
          <p v-if="message.persisted && message.status === 'refused'" class="answer-note">可尝试更具体的问法、切换知识库，或补充上传并发布相关资料。</p>
          <div v-if="message.persisted && message.role === 'assistant' && message.answerOrigin !== 'GENERAL' && message.status !== 'error'" class="fallback-action">
            <el-button :disabled="busy || !chatConfigured" @click="generalAnswer(message)">大模型回答</el-button>
            <small>{{ chatConfigured ? '不使用知识库资料，点击后单独生成。' : '配置外部模型后可选。' }}</small>
          </div>
          <div v-if="message.citations?.length" class="inline-citations">
            <button v-for="citation in message.citations" :key="citation.number" :class="{ selected: evidenceMessageId === message.id && activeCitation === citation.number }" :aria-label="`查看引用 ${citation.number}：${citation.documentTitle}`" @click="showEvidence(message, citation.number)">
              <span>[{{ citation.number }}]</span> {{ citation.documentTitle }}
            </button>
          </div>
          <div v-if="message.persisted" class="message-actions">
            <button class="message-tool" aria-label="复制回答" @click="copyAnswer(message)"><Check v-if="copiedId === message.id" :size="15" /><Copy v-else :size="15" /><span>{{ copiedId === message.id ? '已复制' : '复制' }}</span></button>
            <button v-if="message.questionId" class="message-tool" :disabled="busy || message.feedbackSaving || !chatConfigured" @click="regenerate(message)"><RotateCcw :size="15" /><span>重新回答</span></button>
            <button v-if="message.answerOrigin !== 'GENERAL' && message.status !== 'refused'" class="message-tool" @click="showEvidence(message)"><FileText :size="15" /><span>{{ message.citationsLoading ? '来源加载中…' : message.citationsError ? '重新加载来源' : '查看来源' }}</span></button>
          </div>
          <div v-if="message.persisted && message.role === 'assistant'" class="feedback-panel" aria-label="回答反馈">
            <span class="feedback-label">这条回答对你有帮助吗？</span>
            <button class="message-tool" :class="{ selected: message.helpful === true }" :aria-pressed="message.helpful === true" :disabled="busy || message.feedbackSaving" @click="feedback(message, true)"><ThumbsUp :size="15" /><span>有帮助</span></button>
            <button class="message-tool" :class="{ selected: message.helpful === false }" :aria-pressed="message.helpful === false" :disabled="busy || message.feedbackSaving" @click="feedback(message, false)"><ThumbsDown :size="15" /><span>无帮助</span></button>
            <small v-if="message.helpful !== undefined" class="feedback-status">已记录：{{ message.helpful ? '有帮助' : '无帮助' }}</small>
          </div>
          <div v-if="message.status === 'error'" class="message-actions"><button class="message-tool" :disabled="busy" @click="editFailedQuestion(message)"><RotateCcw :size="15" />编辑问题后重试</button><small>问题已保留。请求超时时，可先查历史确认是否已保存。</small></div>
        </article>

        <div v-if="sending" class="answer-loading" role="status">
          <span /><span /><span /><small>正在处理，请稍候…</small>
        </div>
      </div>

      <button v-if="messages.length && !nearBottom" class="jump-latest" @click="scrollToLatest(true)"><ArrowDown :size="15" />回到最新消息</button>
      <form class="question-composer" @submit.prevent="sendQuestion">
        <el-input
          ref="input"
          v-model="question"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 5 }"
          maxlength="2000"
          placeholder="输入课程问题，按 Ctrl + Enter 发送"
          aria-label="输入课程问题"
          :disabled="!selectedKnowledgeBase || openingId !== null"
          @keydown.ctrl.enter.prevent="sendQuestion"
          @keydown.meta.enter.prevent="sendQuestion"
        />
        <button class="send-button" type="submit" aria-label="发送问题" :disabled="!question.trim() || busy || !knowledgeBases.length || !selectedKnowledgeBase || !chatConfigured">
          <ArrowUp :size="20" />
        </button>
      </form>
    </section>

    <aside ref="evidencePane" class="evidence-pane" tabindex="-1" aria-label="回答原文来源">
      <header class="evidence-heading">
        <span>原文来源</span>
        <strong>{{ activeCitations.length }}</strong>
      </header>
      <p class="evidence-hint">点击回答中的引用，可在这里核对资料。</p>
      <EvidenceSources :citations="activeCitations" :active="activeCitation" :loading="activeAnswer?.citationsLoading" :error="activeAnswer?.citationsError" :general="activeAnswer?.answerOrigin === 'GENERAL'" @retry="loadCitations(activeAnswer)" />
    </aside>
    <el-drawer v-model="evidenceOpen" title="原文来源" direction="rtl" size="min(440px, 94vw)" append-to-body @opened="focusEvidence" @closed="restoreEvidenceFocus">
      <div ref="mobileEvidencePane" class="mobile-evidence-content" tabindex="-1">
        <EvidenceSources :citations="activeCitations" :active="activeCitation" :loading="activeAnswer?.citationsLoading" :error="activeAnswer?.citationsError" :general="activeAnswer?.answerOrigin === 'GENERAL'" @retry="loadCitations(activeAnswer)" />
      </div>
    </el-drawer>
    <el-dialog v-model="historyOpen" title="历史会话" width="min(620px, 94vw)" :close-on-click-modal="!openingId" :close-on-press-escape="!openingId" :show-close="!openingId">
      <div v-if="historyLoading" class="history-state" role="status">正在加载会话…</div>
      <div v-else-if="historyError" class="history-state" role="alert"><p>会话列表加载失败。</p><el-button @click="showHistory">重试</el-button></div>
      <div v-else-if="!conversations.length" class="history-state">还没有历史会话。提出第一个问题后，会话会自动保存。</div>
      <div v-else class="history-list">
        <button v-for="conversation in conversations" :key="conversation.id" class="history-item" :class="{ selected: conversationId === conversation.id }" :disabled="openingId !== null" @click="openConversation(conversation.id)">
          <History :size="18" /><span><strong>{{ conversation.title || '未命名会话' }}</strong><small>{{ conversation.knowledgeBaseId == null ? '全部可访问知识库' : getKnowledgeBaseDisplayName(conversation.knowledgeBaseId, knowledgeBases) }}</small></span><small>{{ openingId === conversation.id ? '加载中…' : conversationId === conversation.id ? '当前会话' : '打开' }}</small>
        </button>
      </div>
    </el-dialog>
  </div>
</template>
