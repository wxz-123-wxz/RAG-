<script setup lang="ts">
import { BookOpenText, Plus, Search } from 'lucide-vue-next'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getKnowledgeBases, getKnowledgeBaseDisplayName, type KnowledgeBase } from '@/api/knowledge'
import { http, getErrorMessage } from '@/api/http'

const query = ref('')
const router = useRouter(), bases = ref<KnowledgeBase[]>([]), loading = ref(false), dialog = ref(false), saving = ref(false)
const loadError = ref(false)
const updatingId = ref<string | null>(null)
const form = reactive({ code: '', name: '', courseName: '软件工程', description: '', visibility: 'RESTRICTED' })
const filtered = computed(() => bases.value.filter(base => {
  const term = query.value.trim()
  return !term || getKnowledgeBaseDisplayName(base.id, bases.value).includes(term)
}))
const labels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下架' }
async function load() {
  loading.value = true
  loadError.value = false
  try { bases.value = await getKnowledgeBases() } catch (error) { loadError.value = true; ElMessage.error(getErrorMessage(error)) }
  finally { loading.value = false }
}
async function create() {
  if (saving.value) return
  if (!/^[a-z0-9-]{3,64}$/.test(form.code) || !form.name.trim()) { ElMessage.warning('请填写有效标识和知识库名称'); return }
  saving.value = true
  try { await http.post('/knowledge-bases', form); dialog.value = false; await load(); ElMessage.success('知识库已创建') }
  catch (error) { ElMessage.error(getErrorMessage(error)) } finally { saving.value = false }
}
async function changeStatus(base: KnowledgeBase) {
  if (updatingId.value) return
  updatingId.value = base.id
  try { await http.post(`/knowledge-bases/${base.id}/${base.status === 'PUBLISHED' ? 'offline' : 'publish'}`); await load() }
  catch (error) { ElMessage.error(getErrorMessage(error)) }
  finally { updatingId.value = null }
}
onMounted(load)
</script>

<template>
  <section class="management-view" v-loading="loading">
    <header class="view-heading">
      <div>
        <span class="eyebrow">KNOWLEDGE INDEX</span>
        <h2>知识库</h2>
        <p>先上传并发布处理完成的文档，再发布知识库。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="dialog = true">新建知识库</el-button>
    </header>
    <div class="view-tools">
      <el-input v-model="query" placeholder="搜索知识库" clearable>
        <template #prefix><Search :size="17" /></template>
      </el-input>
      <span class="record-count">{{ filtered.length }} 个知识库</span><el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <el-alert v-if="loadError" title="知识库加载失败，请点击刷新重试。" type="error" :closable="false" />
    <div v-else class="data-table knowledge-table" tabindex="0" role="region" aria-label="知识库列表，可横向滚动">
      <div class="table-row table-head"><span>知识库</span><span>领域</span><span>文档</span><span>访问范围</span><span>状态</span><span /></div>
      <div v-for="base in filtered" :key="base.id" class="table-row">
        <span class="resource-name"><BookOpenText :size="17" />{{ getKnowledgeBaseDisplayName(base.id, bases) }}</span>
        <span>软件工程</span><span>{{ base.documentCount }}</span><span>{{ base.visibility === 'PUBLIC' ? '公开' : '受限' }}</span>
        <span><em class="status-chip" :class="base.status === 'PUBLISHED' ? 'green' : 'muted'">{{ labels[base.status] || base.status }}</em></span>
        <span class="table-actions"><el-button link @click="router.push({ path: '/documents', query: { kb: base.id } })">文档</el-button><el-button v-if="base.manager" link :loading="updatingId === base.id" :disabled="updatingId !== null && updatingId !== base.id" @click="changeStatus(base)">{{ base.status === 'PUBLISHED' ? '下架' : '发布' }}</el-button></span>
      </div>
    </div>
    <el-empty v-if="!filtered.length && !loading && !loadError" :description="query.trim() ? '没有匹配的知识库，请调整搜索词' : '暂无知识库，请先创建'" />
    <el-dialog v-model="dialog" title="新建知识库" width="min(520px, 94vw)">
      <el-form :model="form" label-position="top" @submit.prevent="create">
        <el-form-item label="标识（3–64 位小写字母、数字或短横线）"><el-input v-model="form.code" maxlength="64" /></el-form-item>
        <el-form-item label="知识库名称"><el-input v-model="form.name" maxlength="128" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" maxlength="2000" /></el-form-item>
        <el-form-item label="访问范围"><el-select v-model="form.visibility"><el-option label="受限" value="RESTRICTED" /><el-option label="公开" value="PUBLIC" /></el-select></el-form-item>
        <el-button type="primary" native-type="submit" :loading="saving">创建</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
