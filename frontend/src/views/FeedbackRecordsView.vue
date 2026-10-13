<script setup lang="ts">
import { MessageSquareWarning, RefreshCw, Search, ThumbsDown, ThumbsUp } from 'lucide-vue-next'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getErrorMessage, http, type ApiResponse } from '@/api/http'
import { getKnowledgeBaseDisplayName } from '@/api/knowledge'

interface FeedbackRow {
  id: string
  messageId: string
  knowledgeBaseId?: string | null
  knowledgeBaseName?: string | null
  userId: string
  username: string
  displayName: string
  helpful: boolean
  citationError: boolean
  incomplete: boolean
  suspectedError: boolean
  comment?: string | null
  question?: string | null
  answer: string
  answerStatus?: string | null
  answerOrigin?: string | null
  submittedAt: string
}

const rows = ref<FeedbackRow[]>([])
const route = useRoute()
const loading = ref(false)
const loadError = ref(false)
const query = ref(String(route.query.q ?? ''))
const days = ref(30)
const helpfulFilter = ref<'all' | 'helpful' | 'unhelpful'>('all')
const detail = ref<FeedbackRow | null>(null)
const detailOpen = ref(false)

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return rows.value
  return rows.value.filter((row) => [
    row.username,
    row.displayName,
    row.question,
    row.answer,
    knowledgeBaseName(row),
  ].some((value) => String(value ?? '').toLowerCase().includes(keyword)))
})
const helpfulCount = computed(() => rows.value.filter((row) => row.helpful).length)
const unhelpfulCount = computed(() => rows.value.length - helpfulCount.value)

function knowledgeBaseName(row: FeedbackRow) {
  if (!row.knowledgeBaseId || !row.knowledgeBaseName) return '全部知识库'
  return getKnowledgeBaseDisplayName(row.knowledgeBaseId, [{ id: row.knowledgeBaseId, name: row.knowledgeBaseName }])
}
function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
function excerpt(value?: string | null, length = 60) {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim()
  return text.length > length ? text.slice(0, length) + '…' : text || '未记录'
}
function showDetail(row: FeedbackRow) {
  detail.value = row
  detailOpen.value = true
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const params: { days: number; limit: number; helpful?: boolean } = { days: days.value, limit: 500 }
    if (helpfulFilter.value !== 'all') params.helpful = helpfulFilter.value === 'helpful'
    rows.value = (await http.get<ApiResponse<FeedbackRow[]>>('/admin/feedback', { params })).data.data
  } catch (error) {
    loadError.value = true
    ElMessage.error(getErrorMessage(error, '反馈记录加载失败'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="management-view">
    <header class="view-heading">
      <div><span class="eyebrow">ANSWER REVIEW</span><h2>反馈记录</h2><p>核对用户对系统回答提交的评价及对应问题。</p></div>
      <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
    </header>

    <div class="metric-strip feedback-metrics">
      <div><MessageSquareWarning :size="18" /><span>反馈总数</span><strong>{{ rows.length }}</strong><small>最近 {{ days }} 天</small></div>
      <div><ThumbsUp :size="18" /><span>有帮助</span><strong>{{ helpfulCount }}</strong><small>用户正向评价</small></div>
      <div><ThumbsDown :size="18" /><span>无帮助</span><strong>{{ unhelpfulCount }}</strong><small>建议优先核对</small></div>
    </div>

    <div class="view-tools feedback-tools">
      <el-input v-model="query" placeholder="搜索用户、问题或回答" clearable><template #prefix><Search :size="17" /></template></el-input>
      <el-select v-model="helpfulFilter" aria-label="评价类型" @change="load"><el-option label="全部评价" value="all" /><el-option label="有帮助" value="helpful" /><el-option label="无帮助" value="unhelpful" /></el-select>
      <el-select v-model="days" aria-label="反馈时间范围" @change="load"><el-option :value="7" label="最近 7 天" /><el-option :value="30" label="最近 30 天" /><el-option :value="90" label="最近 90 天" /><el-option :value="365" label="最近一年" /></el-select>
      <span class="record-count">显示 {{ filtered.length }} 条</span>
    </div>

    <el-alert v-if="loadError" title="反馈记录加载失败，请点击刷新重试。" type="error" :closable="false" />
    <el-table v-else :data="filtered" v-loading="loading" empty-text="当前筛选范围内暂无反馈记录" row-key="id">
      <el-table-column label="评价" width="105"><template #default="{ row }"><span class="status-chip" :class="row.helpful ? 'green' : 'red'">{{ row.helpful ? '有帮助' : '无帮助' }}</span></template></el-table-column>
      <el-table-column label="用户" min-width="140"><template #default="{ row }"><strong>{{ row.displayName }}</strong><small class="table-subline">{{ row.username }}</small></template></el-table-column>
      <el-table-column label="回答状态" width="105"><template #default="{ row }"><span class="status-chip" :class="row.answerStatus === 'REFUSED' ? 'amber' : 'muted'">{{ row.answerStatus === 'REFUSED' ? '无答案' : '已回答' }}</span></template></el-table-column>
      <el-table-column label="知识库" min-width="180"><template #default="{ row }">{{ knowledgeBaseName(row) }}</template></el-table-column>
      <el-table-column label="对应问题" min-width="260"><template #default="{ row }">{{ excerpt(row.question, 72) }}</template></el-table-column>
      <el-table-column label="提交时间" width="180"><template #default="{ row }">{{ formatTime(row.submittedAt) }}</template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link @click="showDetail(row)">查看</el-button></template></el-table-column>
    </el-table>
  </section>

  <el-dialog v-model="detailOpen" title="反馈详情" width="min(760px, 94vw)">
    <div v-if="detail" class="feedback-detail">
      <dl class="feedback-meta">
        <div><dt>评价</dt><dd><span class="status-chip" :class="detail.helpful ? 'green' : 'red'">{{ detail.helpful ? '有帮助' : '无帮助' }}</span></dd></div>
        <div><dt>提交用户</dt><dd>{{ detail.displayName }}（{{ detail.username }}）</dd></div>
        <div><dt>知识库</dt><dd>{{ knowledgeBaseName(detail) }}</dd></div>
        <div><dt>提交时间</dt><dd>{{ formatTime(detail.submittedAt) }}</dd></div>
      </dl>
      <section><h3>用户问题</h3><p>{{ detail.question || '未记录对应问题' }}</p></section>
      <section><h3>系统回答</h3><p>{{ detail.answer }}</p></section>
      <section v-if="detail.comment"><h3>补充说明</h3><p>{{ detail.comment }}</p></section>
    </div>
  </el-dialog>
</template>
