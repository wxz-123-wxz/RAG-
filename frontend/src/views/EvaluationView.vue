<script setup lang="ts">
import { ArrowUpRight, FlaskConical, Play, Plus, RefreshCw } from 'lucide-vue-next'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createEvaluationRun, getEvaluationRuns, getEvaluationResults, type EvaluationRun, type EvaluationResult } from '../api/evaluations'
import { getErrorMessage, http, type ApiResponse } from '../api/http'
import { getKnowledgeBases, getKnowledgeBaseDisplayName, type KnowledgeBase } from '../api/knowledge'

const ALL_BASES = 'all'
const knowledgeBaseId = ref<string>(ALL_BASES)
const runs = ref<EvaluationRun[]>([])
const loading = ref(false)
const creating = ref(false)
const loadError = ref(false), basesError = ref(false), basesLoading = ref(false)
const resultsLoading = ref(false), resultsError = ref(false), questionsLoading = ref(false), questionsError = ref(false), savingQuestion = ref(false)
const runLabels: Record<string, string> = { PENDING: '待执行', RUNNING: '执行中', COMPLETED: '已完成', FAILED: '失败' }
let loadSequence = 0
const newName = ref('')
const bases = ref<KnowledgeBase[]>([]), results = ref<EvaluationResult[]>([]), resultsOpen = ref(false), questionsOpen = ref(false)
const questions = ref<Array<{ id: string; questionCode: string; question: string; shouldRefuse: boolean }>>([])
const draft = ref({ questionCode: '', question: '', referenceDocument: '', evidenceText: '', questionType: 'CONCEPT', shouldRefuse: false })

function baseName(id: string) {
  return getKnowledgeBaseDisplayName(id, bases.value)
}

async function showResults(run: EvaluationRun) {
  if (resultsLoading.value) return
  resultsOpen.value = true; resultsLoading.value = true; resultsError.value = false; results.value = []
  try { results.value = await getEvaluationResults(run.knowledgeBaseId, run.id) }
  catch (error) { resultsError.value = true; ElMessage.error(getErrorMessage(error)) }
  finally { resultsLoading.value = false }
}

async function showQuestions() {
  if (knowledgeBaseId.value === ALL_BASES || questionsLoading.value) return
  questionsOpen.value = true; questionsLoading.value = true; questionsError.value = false
  try { questions.value = (await http.get<ApiResponse<typeof questions.value>>('/knowledge-bases/' + knowledgeBaseId.value + '/evaluation-questions')).data.data }
  catch (error) { questionsError.value = true; ElMessage.error(getErrorMessage(error)) }
  finally { questionsLoading.value = false }
}

async function addQuestion() {
  if (knowledgeBaseId.value === ALL_BASES || savingQuestion.value || !draft.value.questionCode.trim() || !draft.value.question.trim()) return
  savingQuestion.value = true
  try { await http.post('/knowledge-bases/' + knowledgeBaseId.value + '/evaluation-questions', draft.value); draft.value = { questionCode: '', question: '', referenceDocument: '', evidenceText: '', questionType: 'CONCEPT', shouldRefuse: false }; await showQuestions(); ElMessage.success('题目已保存') }
  catch (error) { ElMessage.error(getErrorMessage(error)) }
  finally { savingQuestion.value = false }
}

function recall(run: EvaluationRun) {
  if (!run.metricsJson) return '—'
  try { return JSON.parse(run.metricsJson).recallAt5 ?? '—' } catch { return '—' }
}

async function load() {
  const sequence = ++loadSequence
  runs.value = []; loadError.value = false
  if (!bases.value.length) { loading.value = false; return }
  loading.value = true
  try {
    const rows = knowledgeBaseId.value === ALL_BASES
      ? (await Promise.all(bases.value.map((base) => getEvaluationRuns(base.id)))).flat()
      : await getEvaluationRuns(knowledgeBaseId.value)
    rows.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
    if (sequence === loadSequence) runs.value = rows
  } catch (error) {
    if (sequence === loadSequence) { loadError.value = true; ElMessage.error(getErrorMessage(error, '评测运行加载失败')) }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function createRun() {
  if (creating.value) return
  if (knowledgeBaseId.value === ALL_BASES || !newName.value.trim()) { ElMessage.warning('请选择具体知识库并填写评测名称'); return }
  creating.value = true
  try {
    const run = await createEvaluationRun(knowledgeBaseId.value, { name: newName.value.trim(), retrievalMethod: 'HYBRID', configSnapshotJson: JSON.stringify({ topK: 5, answerMode: 'extractive' }) })
    runs.value.unshift(run); newName.value = ''
    if (run.status === 'FAILED') ElMessage.error('评测失败，请查看逐题结果')
    else ElMessage.success('评测记录已保存，请查看逐题结果')
  } catch (error) { ElMessage.error(getErrorMessage(error, '评测启动失败')) }
  finally { creating.value = false }
}

async function initialize() {
  basesLoading.value = true; basesError.value = false
  try {
    bases.value = (await getKnowledgeBases()).filter((base) => base.manager)
    const stored = localStorage.getItem('ragqa.analytics.knowledgeBaseId')
    knowledgeBaseId.value = stored && bases.value.some((base) => base.id === stored) ? stored : ALL_BASES
    if (bases.value.length) await load()
  } catch (error) { basesError.value = true; ElMessage.error(getErrorMessage(error)) }
  finally { basesLoading.value = false }
}
onMounted(initialize)
</script>

<template>
  <section class="management-view">
    <header class="view-heading"><div><span class="eyebrow">EVALUATION LAB</span><h2>效果评测</h2><p>汇总当前账号可管理知识库的评测运行，选择具体知识库后可新建评测和维护题集。</p></div><el-button :icon="RefreshCw" :loading="loading || basesLoading" :disabled="creating" @click="basesError ? initialize() : load()">刷新</el-button></header>
    <div class="analytics-controls"><el-select v-model="knowledgeBaseId" placeholder="选择知识库" aria-label="选择评测知识库" :loading="basesLoading" :disabled="creating || resultsLoading || questionsLoading" @change="load"><el-option v-if="bases.length" label="全部知识库" :value="ALL_BASES" /><el-option v-for="base in bases" :key="base.id" :label="getKnowledgeBaseDisplayName(base.id, bases)" :value="base.id" /></el-select><el-input v-model="newName" placeholder="选择具体知识库后填写评测名称" aria-label="新评测名称" maxlength="128" :disabled="creating || knowledgeBaseId === ALL_BASES" /><el-button type="primary" :icon="Plus" :loading="creating" :disabled="knowledgeBaseId === ALL_BASES || !newName.trim() || loading" @click="createRun">新建评测</el-button></div>
    <el-alert v-if="knowledgeBaseId === ALL_BASES" title="当前显示全部知识库的评测运行；新建评测和题集维护请先选择具体知识库。" type="info" :closable="false" />
    <el-alert v-if="loadError || basesError" title="评测数据加载失败，请点击刷新重试。" type="error" :closable="false" />
    <div v-if="!loadError && !basesError && !loading && !basesLoading && bases.length" class="metric-strip"><div><span>最近运行题数</span><strong>{{ runs.length ? runs[0].questionCount : '—' }}</strong><small>以最近运行记录为准</small></div><div><span>已完成运行</span><strong>{{ runs.filter((run) => run.status === 'COMPLETED').length }}</strong><small>当前汇总列表中的真实记录</small></div><div><span>评测方案</span><strong>HYBRID</strong><small>当前可用检索路径</small></div></div>
    <el-alert title="当前指标仅用于开发基线检查：Recall@5/MRR 基于文档名或原文词法匹配，引用指标只检查结构，不代表真实语义准确率。" type="warning" :closable="false" />
    <div class="section-label"><span>评测运行</span><el-button link :icon="ArrowUpRight" :disabled="knowledgeBaseId === ALL_BASES || creating" @click="showQuestions">查看题集</el-button></div>
    <div v-if="loading || basesLoading" class="page-loading" role="status">正在加载评测记录…</div>
    <div v-if="!loadError && !basesError && bases.length" class="data-table evaluation-table" tabindex="0" role="region" aria-label="评测运行列表，可横向滚动">
      <div class="table-row table-head"><span>运行名称</span><span>知识库</span><span>方案</span><span>题目</span><span>Recall@5</span><span>状态</span><span /></div>
      <div v-for="run in runs" :key="run.id" class="table-row"><span class="resource-name"><FlaskConical :size="17" />{{ run.name }}</span><span>{{ baseName(run.knowledgeBaseId) }}</span><span class="mono">{{ run.retrievalMethod }}</span><span>{{ run.questionCount }}</span><span class="mono">{{ recall(run) }}</span><span><em class="status-chip" :class="run.status === 'COMPLETED' ? 'green' : run.status === 'FAILED' ? 'red' : 'amber'">{{ runLabels[run.status] || run.status }}</em></span><button class="row-action" :disabled="resultsLoading" aria-label="查看评测结果" @click="showResults(run)"><Play :size="15" />查看</button></div>
    </div>
    <el-empty v-if="!bases.length && !loading && !basesLoading && !loadError && !basesError" description="暂无可管理的知识库，请先创建或联系管理员" />
    <el-empty v-else-if="!runs.length && !loading && !basesLoading && !loadError && !basesError" description="暂无评测运行，请先选择知识库准备题集并新建评测" />
    <el-dialog v-model="resultsOpen" title="逐题评测结果" width="min(1000px, 95vw)"><el-alert v-if="resultsError" title="结果加载失败，请关闭后重新点击查看。" type="error" :closable="false" /><el-table v-else :data="results" v-loading="resultsLoading" empty-text="本次运行暂无逐题结果"><el-table-column prop="evaluationQuestionId" label="题目 ID" min-width="170" /><el-table-column prop="status" label="状态" width="110" /><el-table-column prop="generatedAnswer" label="回答" min-width="250" /><el-table-column prop="errorCode" label="错误码" min-width="180" /><el-table-column prop="latencyMs" label="耗时 ms" width="90" /></el-table></el-dialog>
    <el-dialog v-model="questionsOpen" title="评测题集" width="min(800px, 95vw)" :close-on-click-modal="!savingQuestion" :close-on-press-escape="!savingQuestion" :show-close="!savingQuestion"><el-alert v-if="questionsError" title="题集加载失败，请关闭后重试。" type="error" :closable="false" /><el-table v-else :data="questions" v-loading="questionsLoading" empty-text="暂无题目，可在下方添加"><el-table-column prop="questionCode" label="题号" /><el-table-column prop="question" label="问题" /><el-table-column label="应拒答"><template #default="{ row }">{{ row.shouldRefuse ? '是' : '否' }}</template></el-table-column></el-table><el-form label-position="top" :disabled="savingQuestion" @submit.prevent="addQuestion"><el-form-item label="题号"><el-input v-model="draft.questionCode" maxlength="32" /></el-form-item><el-form-item label="问题"><el-input v-model="draft.question" type="textarea" maxlength="2000" /></el-form-item><el-form-item label="参考文档名"><el-input v-model="draft.referenceDocument" /></el-form-item><el-form-item label="参考原文"><el-input v-model="draft.evidenceText" type="textarea" /></el-form-item><el-checkbox v-model="draft.shouldRefuse">应拒答</el-checkbox><el-button type="primary" native-type="submit" :loading="savingQuestion" :disabled="!draft.questionCode.trim() || !draft.question.trim()">添加题目</el-button></el-form></el-dialog>
  </section>
</template>
