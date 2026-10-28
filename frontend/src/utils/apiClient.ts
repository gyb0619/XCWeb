import { getBrowserFingerprint } from '@/utils/fingerprint'
import { useAuthStore } from '@/stores/authStore'
import { useQuotaStore } from '@/stores/quotaStore'
import type { ContextTooLongError, QuotaExceededError } from '@/types/api'

export class ApiHttpError extends Error {
  status: number
  code: string
  data: Record<string, unknown>

  constructor(status: number, code: string, message: string, data: Record<string, unknown> = {}) {
    super(message)
    this.name = 'ApiHttpError'
    this.status = status
    this.code = code
    this.data = data
  }
}

export async function buildAuthHeaders(
  extraHeaders: Record<string, string> = {},
): Promise<Record<string, string>> {
  const authStore = useAuthStore()
  const fp = authStore.fingerprintHash || (await getBrowserFingerprint())
  if (!authStore.fingerprintHash) {
    authStore.fingerprintHash = fp
  }

  const headers: Record<string, string> = {
    'X-Fingerprint': fp,
    ...extraHeaders,
  }

  if (authStore.token) {
    headers['Authorization'] = `Bearer ${authStore.token}`
  }

  return headers
}

export async function handleHttpErrorResponse(response: Response): Promise<never> {
  const quotaStore = useQuotaStore()
  const authStore = useAuthStore()

  let payload: Record<string, unknown> = {}
  try {
    payload = (await response.json()) as Record<string, unknown>
  } catch {
    payload = { message: response.statusText || `HTTP Error ${response.status}` }
  }

  const code = typeof payload.code === 'string' ? payload.code : `HTTP_${response.status}`
  const message =
    typeof payload.message === 'string'
      ? payload.message
      : `请求失败 (${response.status})`

  if (response.status === 429 || code === 'QUOTA_EXCEEDED') {
    const quotaError: QuotaExceededError = {
      code: 'QUOTA_EXCEEDED',
      role:
        payload.role === 'guest' ||
        payload.role === 'user' ||
        payload.role === 'admin' ||
        payload.role === 'dev'
          ? payload.role
          : authStore.role,
      used: typeof payload.used === 'number' ? payload.used : quotaStore.used,
      limit: typeof payload.limit === 'number' ? payload.limit : quotaStore.limit,
      resetAt: typeof payload.resetAt === 'string' ? payload.resetAt : quotaStore.resetAt,
      message,
    }
    quotaStore.triggerQuotaExceeded(quotaError)
  } else if (response.status === 413 || code === 'CONTEXT_TOO_LONG') {
    const ctxError: ContextTooLongError = {
      code: 'CONTEXT_TOO_LONG',
      role:
        payload.role === 'guest' ||
        payload.role === 'user' ||
        payload.role === 'admin' ||
        payload.role === 'dev'
          ? payload.role
          : authStore.role,
      estimatedTokens:
        typeof payload.estimatedTokens === 'number' ? payload.estimatedTokens : 0,
      limit:
        typeof payload.limit === 'number' ? payload.limit : quotaStore.contextTokenLimit,
      message,
    }
    quotaStore.triggerContextTooLong(ctxError)
  } else if (response.status === 401 || response.status === 403) {
    authStore.handleUnauthorized(message)
  }

  throw new ApiHttpError(response.status, code, message, payload)
}

export interface ApiRequestOptions extends Omit<RequestInit, 'headers'> {
  headers?: Record<string, string>
  skipErrorToast?: boolean
}

export async function apiRequest<T>(
  url: string,
  options: ApiRequestOptions = {},
): Promise<T> {
  const { headers: customHeaders = {}, ...restOptions } = options

  const defaultHeaders: Record<string, string> = {}
  if (restOptions.body && typeof restOptions.body === 'string') {
    defaultHeaders['Content-Type'] = 'application/json'
  }

  const headers = await buildAuthHeaders({
    ...defaultHeaders,
    ...customHeaders,
  })

  const response = await fetch(url, {
    ...restOptions,
    headers,
  })

  if (!response.ok) {
    await handleHttpErrorResponse(response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}
