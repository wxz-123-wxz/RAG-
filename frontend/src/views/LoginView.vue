<script setup lang="ts">
import { ArrowRight, BookOpen, KeyRound, UserRound } from 'lucide-vue-next'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const errorMessage = ref('')

const topics = ['需求分析与建模', '软件架构与设计', '编码规范与代码审查', '软件测试与质量保障', '项目管理与团队协作']

async function submit() {
  errorMessage.value = ''
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/chat'
    await router.replace(redirect)
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '登录失败，请检查账号和密码')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="catalog-panel" aria-label="软件工程知识库">
      <div class="catalog-brand">
        <div class="brand-mark">引</div>
        <span>知问</span>
      </div>
      <div class="catalog-heading">
        <span>SOFTWARE ENGINEERING DESK</span>
        <h1>软件工程<br />知识库</h1>
      </div>
      <div class="catalog-index">
        <div v-for="(topic, index) in topics" :key="topic" class="catalog-row">
          <span>{{ String(index + 1).padStart(2, '0') }}</span>
          <strong>{{ topic }}</strong>
          <BookOpen :size="18" aria-hidden="true" />
        </div>
      </div>
      <p class="catalog-foot">公开参考资料 · 原文引用 · 检索评测</p>
    </section>

    <section class="login-panel">
      <form class="login-form" @submit.prevent="submit">
        <div class="form-heading">
          <span>账户认证</span>
          <h2>登录工作台</h2>
        </div>

        <label class="field-label" for="username">账号</label>
        <el-input id="username" v-model="form.username" size="large" autocomplete="username">
          <template #prefix><UserRound :size="18" /></template>
        </el-input>

        <label class="field-label" for="password">密码</label>
        <el-input
          id="password"
          v-model="form.password"
          size="large"
          type="password"
          show-password
          autocomplete="current-password"
        >
          <template #prefix><KeyRound :size="18" /></template>
        </el-input>

        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

        <el-button
          class="login-button"
          type="primary"
          native-type="submit"
          size="large"
          :loading="loading"
          :disabled="!form.username || !form.password"
        >
          登录
          <ArrowRight v-if="!loading" :size="18" />
        </el-button>
        <p class="auth-switch">还没有账号？<RouterLink to="/register">创建账号</RouterLink></p>
      </form>
    </section>
  </main>
</template>
