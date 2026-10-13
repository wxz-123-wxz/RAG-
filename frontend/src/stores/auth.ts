import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { http, refreshAccessToken, setAccessToken, type ApiResponse } from '@/api/http'

export interface CurrentUser {
  id: string
  username: string
  displayName: string
  roles: string[]
}

interface AuthResult {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: CurrentUser
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const initialized = ref(false)
  const isAuthenticated = computed(() => user.value !== null)
  const isSystemAdmin = computed(() => user.value?.roles.includes('SYS_ADMIN') ?? false)
  const canManageKnowledgeBase = computed(
    () => isSystemAdmin.value || (user.value?.roles.includes('KB_ADMIN') ?? false),
  )

  async function login(username: string, password: string) {
    const response = await http.post<ApiResponse<AuthResult>>('/auth/login', { username, password })
    setAccessToken(response.data.data.accessToken)
    user.value = response.data.data.user
  }

  async function register(username: string, displayName: string, password: string) {
    const response = await http.post<ApiResponse<AuthResult>>('/auth/register', { username, displayName, password })
    setAccessToken(response.data.data.accessToken)
    user.value = response.data.data.user
  }

  async function initialize() {
    if (initialized.value) return
    const token = await refreshAccessToken()
    if (token) {
      try {
        const response = await http.get<ApiResponse<CurrentUser>>('/users/me')
        user.value = response.data.data
      } catch {
        setAccessToken(null)
        user.value = null
      }
    }
    initialized.value = true
  }

  async function logout() {
    try {
      await http.post('/auth/logout')
    } finally {
      setAccessToken(null)
      user.value = null
    }
  }

  return {
    user,
    initialized,
    isAuthenticated,
    isSystemAdmin,
    canManageKnowledgeBase,
    login,
    register,
    initialize,
    logout,
  }
})
