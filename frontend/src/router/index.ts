import { createRouter, createWebHistory } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { public: true, title: '注册' },
    },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', redirect: '/chat' },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('@/views/ChatView.vue'),
          meta: { title: '知识问答' },
        },
        {
          path: 'knowledge-bases',
          name: 'knowledge-bases',
          component: () => import('@/views/KnowledgeBasesView.vue'),
          meta: { title: '知识库', manager: true },
        },
        {
          path: 'documents',
          name: 'documents',
          component: () => import('@/views/DocumentsView.vue'),
          meta: { title: '文档处理', manager: true },
        },
        {
          path: 'evaluation',
          name: 'evaluation',
          component: () => import('@/views/EvaluationView.vue'),
          meta: { title: '效果评测', manager: true },
        },
        {
          path: 'analytics',
          name: 'analytics',
          component: () => import('@/views/AnalyticsView.vue'),
          meta: { title: '运行统计', manager: true },
        },
        {
          path: 'admin/users',
          name: 'users',
          component: () => import('@/views/UsersView.vue'),
          meta: { title: '用户管理', systemAdmin: true },
        },
        {
          path: 'admin/feedback',
          name: 'feedback-records',
          component: () => import('@/views/FeedbackRecordsView.vue'),
          meta: { title: '反馈记录', systemAdmin: true },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/chat' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.initialize()

  if (to.meta.public) {
    return (to.name === 'login' || to.name === 'register') && auth.isAuthenticated ? '/chat' : true
  }
  if (!auth.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.systemAdmin && !auth.isSystemAdmin) return '/chat'
  if (to.meta.manager && !auth.canManageKnowledgeBase) return '/chat'
  return true
})

export default router
