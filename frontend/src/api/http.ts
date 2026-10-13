import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'

export interface ApiErrorBody {
  success: false
  error: {
    code: string
    message: string
  }
  traceId?: string
}

export interface ApiResponse<T> {
  success: true
  data: T
  traceId?: string
}

interface RetryConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

let accessToken: string | null = null
let refreshPromise: Promise<string | null> | null = null

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 40_000,
  withCredentials: true,
})

const refreshClient = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  withCredentials: true,
})

export function setAccessToken(token: string | null) {
  accessToken = token
}

export async function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = refreshClient
      .post<ApiResponse<{ accessToken: string }>>('/auth/refresh')
      .then((response) => {
        const token = response.data.data.accessToken
        setAccessToken(token)
        return token
      })
      .catch(() => {
        setAccessToken(null)
        return null
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

http.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiErrorBody>) => {
    const original = error.config as RetryConfig | undefined
    const isAuthEndpoint = original?.url?.startsWith('/auth/')
    if (error.response?.status === 401 && original && !original._retry && !isAuthEndpoint) {
      original._retry = true
      const token = await refreshAccessToken()
      if (token) {
        original.headers.Authorization = `Bearer ${token}`
        return http(original)
      }
    }
    return Promise.reject(error)
  },
)

export function getErrorMessage(error: unknown, fallback = '请求失败，请稍后重试') {
  if (axios.isAxiosError<ApiErrorBody>(error)) {
    return error.response?.data?.error?.message ?? fallback
  }
  return fallback
}
