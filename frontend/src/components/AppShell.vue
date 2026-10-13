<script setup lang="ts">
import {
  BarChart3,
  BookOpenText,
  ChevronDown,
  FileStack,
  FlaskConical,
  LogOut,
  Menu,
  MessageSquareWarning,
  MessageSquareText,
  Users,
  X,
} from 'lucide-vue-next'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import axios from 'axios'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const mobileNavOpen = ref(false)
const serviceState = ref<'checking' | 'up' | 'down'>('checking')
let healthTimer: ReturnType<typeof setInterval> | undefined
let checking = false
let disposed = false

async function checkService() {
  if (checking) return
  checking = true
  try {
    const response = await axios.get('/health/live', { timeout: 5000 })
    if (!disposed) serviceState.value = response.data?.data?.status === 'UP' ? 'up' : 'down'
  } catch { if (!disposed) serviceState.value = 'down' }
  finally { checking = false }
}
onMounted(() => {
  void checkService()
  healthTimer = setInterval(() => { if (document.visibilityState === 'visible') void checkService() }, 30_000)
})
onUnmounted(() => { disposed = true; clearInterval(healthTimer) })

const pageTitle = computed(() => String(route.meta.title ?? '知问'))
const navItems = computed(() => [
  { path: '/chat', label: '知识问答', icon: MessageSquareText, visible: true },
  {
    path: '/knowledge-bases',
    label: '知识库',
    icon: BookOpenText,
    visible: auth.canManageKnowledgeBase,
  },
  {
    path: '/documents',
    label: '文档处理',
    icon: FileStack,
    visible: auth.canManageKnowledgeBase,
  },
  {
    path: '/evaluation',
    label: '效果评测',
    icon: FlaskConical,
    visible: auth.canManageKnowledgeBase,
  },
  {
    path: '/analytics',
    label: '运行统计',
    icon: BarChart3,
    visible: auth.canManageKnowledgeBase,
  },
  { path: '/admin/users', label: '用户管理', icon: Users, visible: auth.isSystemAdmin },
  { path: '/admin/feedback', label: '反馈记录', icon: MessageSquareWarning, visible: auth.isSystemAdmin },
])

async function logout() {
  await auth.logout()
  await router.replace('/login')
}

function navigate(path: string) {
  mobileNavOpen.value = false
  void router.push(path)
}
</script>

<template>
  <div class="app-layout">
    <button
      v-if="mobileNavOpen"
      class="nav-backdrop"
      aria-label="关闭导航"
      @click="mobileNavOpen = false"
    />

    <aside class="side-rail" :class="{ 'is-open': mobileNavOpen }">
      <div class="brand-lockup">
        <div class="brand-mark" aria-hidden="true">引</div>
        <div>
          <strong>知问</strong>
          <span>软件工程知识库</span>
        </div>
        <button class="icon-button mobile-close" aria-label="关闭导航" @click="mobileNavOpen = false">
          <X :size="19" />
        </button>
      </div>

      <nav class="primary-nav" aria-label="主导航">
        <button
          v-for="item in navItems.filter((entry) => entry.visible)"
          :key="item.path"
          class="nav-item"
          :class="{ active: route.path === item.path }"
          @click="navigate(item.path)"
        >
          <component :is="item.icon" :size="19" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <button class="rail-foot" :class="serviceState" title="检查业务服务连接（不代表模型调用状态）" aria-label="检查业务服务连接" @click="checkService">
        <span class="service-dot" aria-hidden="true" />
        <span>业务服务</span>
        <strong role="status">{{ serviceState === 'up' ? '可访问' : serviceState === 'down' ? '连接失败' : '检查中' }}</strong>
      </button>
    </aside>

    <div class="app-column">
      <header class="top-bar">
        <button class="icon-button mobile-menu" aria-label="打开导航" @click="mobileNavOpen = true">
          <Menu :size="20" />
        </button>
        <div class="page-identity">
          <span>工作台</span>
          <h1>{{ pageTitle }}</h1>
        </div>

        <el-dropdown trigger="click">
          <button class="user-menu">
            <span class="user-avatar">{{ auth.user?.displayName.slice(0, 1) }}</span>
            <span class="user-copy">
              <strong>{{ auth.user?.displayName }}</strong>
              <small>{{ auth.user?.username }}</small>
            </span>
            <ChevronDown :size="16" aria-hidden="true" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :icon="LogOut" @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <main class="app-main" :class="{ 'is-chat': route.name === 'chat' }">
        <RouterView />
      </main>
    </div>
  </div>
</template>
