<script setup lang="ts">
import { Activity, Clock3, FileText, MessageSquareText, RefreshCw } from 'lucide-vue-next'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAdminAnalytics, getAnalytics, getAnalyticsForKnowledgeBases, type AnalyticsSnapshot } from '../api/analytics'
import { getErrorMessage } from '../api/http'
import { getKnowledgeBases, getKnowledgeBaseDisplayName, type KnowledgeBase } from '../api/knowledge'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const ALL_BASES = 'all'
const auth = useAuthStore()
const router = useRouter()
const knowledgeBaseId = ref<string>(ALL_BASES)
const bases = ref<KnowledgeBase[]>([])
const days = ref(30)
const loading = ref(false)
const loadError = ref(false)
const basesLoading = ref(false)
const basesError = ref(false)
let loadSequence = 0
const data = ref<AnalyticsSnapshot | null>(null)
const activeSection = ref<'popular' | 'refused' | 'documents'>('popular')
const sections = computed(() => [
  { key: 'popular' as const, label: '热门问题', rows: data.value?.popularQuestions ?? [] },
  { key: 'refused' as const, label: '无答案问题', rows: data.value?.refusedQuestions ?? [] },
  { key: 'documents' as const, label: '常引用文档', rows: data.value?.citedDocuments ?? [] },
])
const currentRows = computed(() => sections.value.find((section) => section.key === activeSection.value)?.rows ?? [])
function openFeedback(question: string) {
  void router.push({ path: '/admin/feedback', query: { q: question } })
}

async function load() {
  const sequence = ++loadSequence
  data.value = null
  loadError.value = false
  if (!knowledgeBaseId.value || !bases.value.length) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const snapshot = knowledgeBaseId.value === ALL_BASES
      ? auth.isSystemAdmin
        ? await getAdminAnalytics(days.value)
        : await getAnalyticsForKnowledgeBases(bases.value.map((base) => base.id), days.value)
      : await getAnalytics(knowledgeBaseId.value, days.value)
    if (sequence === loadSequence) {
      data.value = snapshot
      localStorage.setItem('ragqa.analytics.knowledgeBaseId', knowledgeBaseId.value)
    }
  } catch (error) {
    if (sequence === loadSequence) {
      loadError.value = true
      ElMessage.error(getErrorMessage(error, '运行统计加载失败'))
    }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function initialize() {
  basesLoading.value = true
  basesError.value = false
  try {
    bases.value = (await getKnowledgeBases()).filter((base) => base.manager)
    const stored = localStorage.getItem('ragqa.analytics.knowledgeBaseId')
    knowledgeBaseId.value = stored && bases.value.some((base) => base.id === stored) ? stored : ALL_BASES
    if (bases.value.length) await load()
  } catch (error) {
    basesError.value = true
    ElMessage.error(getErrorMessage(error))
  } finally {
    basesLoading.value = false
  }
}
onMounted(initialize)
</script>

<template>
  <section class="management-view">
    <header class="view-heading"><div><span class="eyebrow">SYSTEM SIGNALS</span><h2>运行统计</h2><p>汇总当前账号可管理知识库的真实问答与用户反馈记录。</p></div><el-button :icon="RefreshCw" :loading="loading || basesLoading" @click="basesError ? initialize() : load()">刷新</el-button></header>
    <div class="analytics-controls"><el-select v-model="knowledgeBaseId" placeholder="选择知识库" aria-label="选择统计知识库" :loading="basesLoading" @change="load"><el-option v-if="bases.length" label="全部知识库" :value="ALL_BASES" /><el-option v-for="base in bases" :key="base.id" :label="getKnowledgeBaseDisplayName(base.id, bases)" :value="base.id" /></el-select><el-select v-model="days" aria-label="统计时间范围" @change="load"><el-option :value="7" label="最近 7 天" /><el-option :value="30" label="最近 30 天" /><el-option :value="90" label="最近 90 天" /></el-select></div>
    <el-alert :title="auth.isSystemAdmin ? '全部知识库模式显示系统全局数据，包含默认跨库问答和对应反馈；通用模型回答仍不计入知识库问答统计。' : '全部知识库模式会合并当前账号可管理知识库的数据；跨库会话不计入单库统计。'" type="info" :closable="false" />
    <el-alert v-if="loadError || basesError" title="统计数据加载失败，请点击刷新重试。" type="error" :closable="false" />
    <div v-if="loading || basesLoading" class="page-loading" role="status">正在加载统计数据…</div>
    <div v-if="data" class="metric-strip analytics-metrics"><div><MessageSquareText :size="18" /><span>回答次数</span><strong>{{ data.answerCount }}</strong><small>最近 {{ data.days }} 天</small></div><div><Activity :size="18" /><span>拒答次数</span><strong>{{ data.refusalCount }}</strong><small>占回答 {{ data.answerCount ? ((data.refusalCount / data.answerCount) * 100).toFixed(1) : '0.0' }}%</small></div><div><Clock3 :size="18" /><span>平均回答耗时</span><strong>{{ data.meanLatencyMs }} ms</strong><small>按已记录回答计算</small></div><div><FileText :size="18" /><span>引用文档数</span><strong>{{ data.citedDocuments.length }}</strong><small>有引用的文档</small></div></div>
    <div v-if="!bases.length && !basesLoading && !basesError" class="analytics-empty"><Activity :size="28" /><h3>暂无可管理的知识库</h3><p>请先创建知识库或联系系统管理员。</p></div>
    <div v-else-if="data" class="analytics-results">
      <nav class="analytics-tabs" aria-label="统计类别"><button v-for="section in sections" :key="section.key" :class="{ active: activeSection === section.key }" @click="activeSection = section.key">{{ section.label }}</button></nav>
      <div v-if="currentRows.length" class="analytics-table">
        <div class="analytics-row analytics-table-head" :class="{ 'refused-row': activeSection === 'refused' }"><span>{{ activeSection === 'documents' ? '文档' : '问题' }}</span><span>{{ activeSection === 'refused' ? '拒答' : '次数' }}</span><span v-if="activeSection === 'refused'">反馈</span></div>
        <div v-for="(row, index) in currentRows" :key="'documentTitle' in row ? row.documentTitle : row.question" class="analytics-row" :class="{ 'refused-row': activeSection === 'refused' }"><span><i>{{ index + 1 }}</i>{{ 'documentTitle' in row ? row.documentTitle : row.question }}</span><strong>{{ row.count }}</strong><span v-if="activeSection === 'refused' && 'question' in row"><el-button v-if="auth.isSystemAdmin && (row.feedbackCount ?? 0)" link @click="openFeedback(row.question)">{{ row.feedbackCount }}</el-button><strong v-else>{{ row.feedbackCount ?? 0 }}</strong></span></div>
      </div>
      <div v-else class="analytics-empty"><Activity :size="28" /><h3>当前时间范围内暂无记录</h3></div>
    </div>
  </section>
</template>
