<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getKnowledgeBases, getDocuments, getKnowledgeBaseDisplayName, type KnowledgeBase, type DocumentRow, type IngestionTask } from '@/api/knowledge'
import { http, getErrorMessage, type ApiResponse } from '@/api/http'
const route = useRoute(), bases = ref<KnowledgeBase[]>([]), kb = ref(''), documents = ref<DocumentRow[]>([])
const loading = ref(false), uploading = ref(false), fileInput = ref<HTMLInputElement | null>(null), task = ref<IngestionTask | null>(null)
const loadError = ref(false), basesError = ref(false), basesLoading = ref(false), taskError = ref(false)
const publishingId = ref<string | null>(null), retrying = ref(false)
const parseLabels: Record<string, string> = { PENDING: '等待处理', UPLOADED: '已上传', QUEUED: '排队中', RUNNING: '处理中', PROCESSING: '处理中', PARSING: '解析中', INDEXING: '索引中', READY: '索引就绪', SUCCEEDED: '处理完成', FAILED: '处理失败' }
const statusLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下架' }
let loadSequence = 0
let pollSequence = 0
const canManage = computed(() => bases.value.some(base => base.id === kb.value && base.manager))
const busy = computed(() => !!task.value && !['SUCCEEDED', 'READY', 'FAILED'].includes(task.value.status))
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
async function load() {
  const sequence = ++loadSequence
  documents.value = []
  loadError.value = false
  if (!kb.value) { loading.value = false; return }
  loading.value = true
  try { const rows = await getDocuments(kb.value); if (!disposed && sequence === loadSequence) documents.value = rows }
  catch (error) { if (!disposed && sequence === loadSequence) { loadError.value = true; ElMessage.error(getErrorMessage(error)) } }
  finally { if (sequence === loadSequence) loading.value = false }
}
async function poll(id: string) {
  clearTimeout(timer)
  const sequence = ++pollSequence
  taskError.value = false
  try {
    const result = await http.get<ApiResponse<IngestionTask>>(`/ingestion-tasks/${id}`)
    if (disposed || sequence !== pollSequence) return
    task.value = result.data.data
    if (busy.value) timer = setTimeout(() => void poll(id), 1200)
    else await load()
  } catch (error) { if (!disposed && sequence === pollSequence) { taskError.value = true; ElMessage.error(getErrorMessage(error)) } }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file || !kb.value || uploading.value || busy.value) return
  if (file.size > 50 * 1024 * 1024) { ElMessage.warning('文件不能超过 50 MB'); input.value = ''; return }
  uploading.value = true
  try {
    const form = new FormData(); form.append('file', file)
    const result = await http.post<ApiResponse<{ taskId: string }>>(`/knowledge-bases/${kb.value}/documents`, form, { timeout: 60_000 })
    ElMessage.success('上传成功，正在处理'); await load(); await poll(result.data.data.taskId)
  } catch (error) { ElMessage.error(getErrorMessage(error)) } finally { uploading.value = false; input.value = '' }
}
async function togglePublish(document: DocumentRow) {
  if (publishingId.value) return
  publishingId.value = document.id
  const action = document.status === 'PUBLISHED' ? 'offline' : 'publish'
  try {
    await http.post(`/knowledge-bases/${kb.value}/documents/${document.id}/${action}`)
    await load()
    ElMessage.success(action === 'publish' ? '文档已发布' : '文档已下架')
  } catch (error) { ElMessage.error(getErrorMessage(error)) }
  finally { publishingId.value = null }
}
async function viewTask(document: DocumentRow) {
  try { const result = await http.get<ApiResponse<string | null>>(`/knowledge-bases/${kb.value}/documents/${document.id}/latest-task`); if (result.data.data) await poll(result.data.data) }
  catch (error) { ElMessage.error(getErrorMessage(error)) }
}
async function retry() {
  if (!task.value || retrying.value) return
  retrying.value = true
  try { const result = await http.post<ApiResponse<{ taskId: string }>>(`/ingestion-tasks/${task.value.id}/retry`); await poll(result.data.data.taskId) }
  catch (error) { ElMessage.error(getErrorMessage(error)) }
  finally { retrying.value = false }
}
function changeBase() { clearTimeout(timer); ++pollSequence; task.value = null; taskError.value = false; void load() }
async function initialize() {
  basesLoading.value = true
  basesError.value = false
  try { bases.value = await getKnowledgeBases(); kb.value = bases.value.find(base => base.id === route.query.kb)?.id ?? bases.value[0]?.id ?? ''; await load() }
  catch (error) { basesError.value = true; ElMessage.error(getErrorMessage(error)) }
  finally { basesLoading.value = false }
}
onMounted(initialize)
onUnmounted(() => { disposed = true; clearTimeout(timer) })
</script>

<template>
  <section class="management-view">
    <header class="view-heading">
      <div><span class="eyebrow">INGESTION QUEUE</span><h2>文档处理</h2><p>支持 UTF-8 TXT、Markdown。PDF/DOCX 解析尚未接入。</p></div>
      <el-button type="primary" :disabled="!canManage || busy" :loading="uploading" @click="fileInput?.click()">上传文档</el-button>
    </header>
    <input ref="fileInput" type="file" accept=".txt,.md,.markdown" hidden @change="upload" />
    <div class="view-tools"><el-select v-model="kb" placeholder="选择知识库" aria-label="选择文档知识库" :loading="basesLoading" :disabled="uploading || (busy && !taskError) || publishingId !== null || retrying" @change="changeBase"><el-option v-for="base in bases" :key="base.id" :label="getKnowledgeBaseDisplayName(base.id, bases)" :value="base.id" /></el-select><el-button :loading="loading || basesLoading" @click="basesError ? initialize() : load()">刷新</el-button></div>
    <el-alert v-if="loadError || basesError" title="文档列表加载失败，请点击刷新重试。" type="error" :closable="false" />
    <el-alert v-if="task" :type="task.status === 'FAILED' ? 'error' : 'info'" :closable="false" :title="`任务：${parseLabels[task.status] || task.status} · ${task.progressPercent}%`" :description="task.errorMessage || '索引完成后，请发布文档，再到知识库页面发布知识库。'" />
    <el-button v-if="task?.status === 'FAILED'" :loading="retrying" @click="retry">重试失败任务</el-button>
    <el-alert v-if="taskError" title="任务状态更新中断，请重新查看任务详情。" type="warning" :closable="false" />
    <el-table v-if="!loadError && !basesError" :data="documents" v-loading="loading || basesLoading" :empty-text="kb ? '该知识库暂无文档，上传 TXT 或 Markdown 开始构建' : '暂无可访问的知识库，请先创建或联系管理员'">
      <el-table-column prop="filename" label="文档" min-width="220" /><el-table-column label="处理状态" width="130"><template #default="{ row }"><span class="status-chip" :class="row.parseStatus === 'READY' ? 'green' : row.parseStatus === 'FAILED' ? 'red' : 'amber'">{{ parseLabels[row.parseStatus] || row.parseStatus }}</span></template></el-table-column><el-table-column label="发布状态" width="130"><template #default="{ row }"><span class="status-chip" :class="row.status === 'PUBLISHED' ? 'green' : 'muted'">{{ statusLabels[row.status] || row.status }}</span></template></el-table-column><el-table-column prop="createdAt" label="上传时间" min-width="170" />
      <el-table-column v-if="canManage" label="操作" min-width="190"><template #default="{ row }"><el-button link @click="viewTask(row)">任务详情</el-button><el-button v-if="row.parseStatus === 'READY' && row.status !== 'PUBLISHED'" link :loading="publishingId === row.id" :disabled="publishingId !== null && publishingId !== row.id" @click="togglePublish(row)">发布</el-button><el-button v-else-if="row.status === 'PUBLISHED'" link :loading="publishingId === row.id" :disabled="publishingId !== null && publishingId !== row.id" @click="togglePublish(row)">下架</el-button></template></el-table-column>
    </el-table>
  </section>
</template>
