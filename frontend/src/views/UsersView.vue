<script setup lang="ts">
import { Plus, RefreshCw, ShieldCheck, UserRound } from 'lucide-vue-next'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getErrorMessage, http, type ApiResponse } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

interface UserRow { id: string; username: string; displayName: string; status: 'ACTIVE' | 'DISABLED'; roles: string[]; lastLoginAt?: string; createdAt: string }
const auth = useAuthStore()
const users = ref<UserRow[]>([]), loading = ref(false), saving = ref(false), loadError = ref(false), dialogOpen = ref(false)
const updatingId = ref<string | null>(null), query = ref('')
const form = reactive({ username: '', displayName: '', password: '', roleCode: 'STUDENT' })
const roleLabels: Record<string, string> = { STUDENT: '普通用户', KB_ADMIN: '知识库管理员', SYS_ADMIN: '系统管理员' }
const filtered = computed(() => { const keyword = query.value.trim().toLowerCase(); return keyword ? users.value.filter(user => `${user.username} ${user.displayName}`.toLowerCase().includes(keyword)) : users.value })

async function load() { loading.value = true; loadError.value = false; try { users.value = (await http.get<ApiResponse<UserRow[]>>('/admin/users')).data.data } catch (error) { loadError.value = true; ElMessage.error(getErrorMessage(error, '用户列表加载失败')) } finally { loading.value = false } }
function openCreate() { Object.assign(form, { username: '', displayName: '', password: '', roleCode: 'STUDENT' }); dialogOpen.value = true }
async function createUser() { if (saving.value || !form.username.trim() || !form.displayName.trim() || form.password.length < 8) return; saving.value = true; try { await http.post('/admin/users', { ...form, username: form.username.trim(), displayName: form.displayName.trim() }); dialogOpen.value = false; await load(); ElMessage.success('用户已创建') } catch (error) { ElMessage.error(getErrorMessage(error, '用户创建失败')) } finally { saving.value = false } }
async function toggleStatus(user: UserRow) { if (updatingId.value) return; updatingId.value = user.id; const status = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; try { await http.patch(`/admin/users/${user.id}/status`, { status }); await load(); ElMessage.success(status === 'ACTIVE' ? '账号已启用' : '账号已停用') } catch (error) { ElMessage.error(getErrorMessage(error, '账号状态修改失败')) } finally { updatingId.value = null } }
onMounted(load)
</script>

<template>
  <section class="management-view">
    <header class="view-heading"><div><span class="eyebrow">ACCESS CONTROL</span><h2>用户管理</h2><p>创建系统账号、分配角色并管理账号状态。</p></div><el-button type="primary" :icon="Plus" @click="openCreate">新增用户</el-button></header>
    <div class="view-tools"><el-input v-model="query" placeholder="搜索账号或姓名" clearable /><span class="record-count">{{ filtered.length }} 个用户</span><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button></div>
    <el-alert v-if="loadError" title="用户列表加载失败，请点击刷新重试。" type="error" :closable="false" />
    <div v-else class="data-table user-table" v-loading="loading"><div class="table-row table-head"><span>用户</span><span>账号</span><span>角色</span><span>状态</span><span /></div><div v-for="user in filtered" :key="user.id" class="table-row"><span class="resource-name"><UserRound :size="17" />{{ user.displayName }}</span><span class="mono">{{ user.username }}</span><span class="role-label"><ShieldCheck :size="15" />{{ user.roles.map(role => roleLabels[role] || role).join('、') }}</span><span><em class="status-chip" :class="user.status === 'ACTIVE' ? 'green' : 'muted'">{{ user.status === 'ACTIVE' ? '正常' : '已停用' }}</em></span><span><el-button link :loading="updatingId === user.id" :disabled="user.id === auth.user?.id || (updatingId !== null && updatingId !== user.id)" @click="toggleStatus(user)">{{ user.status === 'ACTIVE' ? '停用' : '启用' }}</el-button></span></div></div>
    <el-empty v-if="!filtered.length && !loading && !loadError" description="没有匹配的用户" />
  </section>
  <el-dialog v-model="dialogOpen" title="新增用户" width="min(520px, 94vw)" :close-on-click-modal="!saving" :show-close="!saving"><el-form :model="form" label-position="top" @submit.prevent="createUser"><el-form-item label="账号"><el-input v-model="form.username" maxlength="64" autocomplete="off" placeholder="3–64 位字母、数字、点、横线或下划线" /></el-form-item><el-form-item label="显示名称"><el-input v-model="form.displayName" maxlength="64" /></el-form-item><el-form-item label="初始密码"><el-input v-model="form.password" type="password" show-password maxlength="128" autocomplete="new-password" placeholder="至少 8 位" /></el-form-item><el-form-item label="角色"><el-select v-model="form.roleCode"><el-option label="普通用户" value="STUDENT" /><el-option label="知识库管理员" value="KB_ADMIN" /><el-option label="系统管理员" value="SYS_ADMIN" /></el-select></el-form-item><el-button type="primary" native-type="submit" :loading="saving" :disabled="!form.username.trim() || !form.displayName.trim() || form.password.length < 8">创建用户</el-button></el-form></el-dialog>
</template>
