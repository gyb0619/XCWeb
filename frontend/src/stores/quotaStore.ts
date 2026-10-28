import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  ContextTooLongError,
  QuotaDimensions,
  QuotaExceededError,
  QuotaStatus,
  UserRole,
} from '@/types/api'
import { apiRequest } from '@/utils/apiClient'
import { useAuthStore } from '@/stores/authStore'

function getNextMidnightIso(): string {
  const now = new Date()
  const tomorrow = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() + 1,
    0,
    0,
    0,
  )
  return tomorrow.toISOString()
}

function getSecondsUntilMidnight(): number {
  const now = new Date()
  const tomorrow = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() + 1,
    0,
    0,
    0,
  )
  return Math.max(1, Math.floor((tomorrow.getTime() - now.getTime()) / 1000))
}

export const useQuotaStore = defineStore('quota', () => {
  const used = ref<number>(0)
  const limit = ref<number>(10)
  const unlimited = ref<boolean>(false)
  const tokensUsedToday = ref<number>(0)
  const contextTokenLimit = ref<number>(4000)
  const resetAt = ref<string>(getNextMidnightIso())
  const resetInSeconds = ref<number>(getSecondsUntilMidnight())
  const dimensions = ref<QuotaDimensions | undefined>(undefined)

  // 游客超额：科技蓝玻璃拟态卡片引导弹窗 (PRD 2.3)
  const showGuestUpgradeModal = ref<boolean>(false)
  // 普通用户超额：气泡提示 (PRD 2.3)
  const showUserQuotaToast = ref<boolean>(false)
  const quotaExceededDetails = ref<QuotaExceededError | null>(null)

  // 413 CONTEXT_TOO_LONG 提示状态
  const contextOverflowAlert = ref<ContextTooLongError | null>(null)

  const usagePercentage = computed<number>(() => {
    if (unlimited.value || limit.value <= 0) return 0
    return Math.min(100, Math.round((used.value / limit.value) * 100))
  })

  const isQuotaExhausted = computed<boolean>(() => {
    if (unlimited.value) return false
    return used.value >= limit.value
  })

  const remainingCalls = computed<number>(() => {
    if (unlimited.value) return Infinity
    return Math.max(0, limit.value - used.value)
  })

  function applyRoleDefaults(role: UserRole) {
    if (role === 'guest') {
      limit.value = 10
      unlimited.value = false
      contextTokenLimit.value = 4000
    } else if (role === 'user') {
      limit.value = 50
      unlimited.value = false
      contextTokenLimit.value = 16000
    } else {
      limit.value = -1
      unlimited.value = true
      contextTokenLimit.value = 128000
    }
    resetAt.value = getNextMidnightIso()
    resetInSeconds.value = getSecondsUntilMidnight()
  }

  function applyQuotaStatus(status: QuotaStatus) {
    used.value = status.used
    limit.value = status.limit
    unlimited.value = status.unlimited
    tokensUsedToday.value = status.tokensUsedToday
    contextTokenLimit.value = status.contextTokenLimit
    resetAt.value = status.resetAt
    resetInSeconds.value = status.resetInSeconds
    dimensions.value = status.dimensions
  }

  async function fetchQuotaStatus(): Promise<QuotaStatus> {
    const authStore = useAuthStore()
    try {
      const status = await apiRequest<QuotaStatus>('/api/quota/status')
      applyQuotaStatus(status)
      return status
    } catch {
      applyRoleDefaults(authStore.role)
      return {
        role: authStore.role,
        used: used.value,
        limit: limit.value,
        unlimited: unlimited.value,
        tokensUsedToday: tokensUsedToday.value,
        contextTokenLimit: contextTokenLimit.value,
        resetAt: resetAt.value,
        resetInSeconds: resetInSeconds.value,
        dimensions: dimensions.value,
      }
    }
  }

  function triggerQuotaExceeded(error?: Partial<QuotaExceededError>) {
    const authStore = useAuthStore()
    const currentRole = error?.role ?? authStore.role
    const currentLimit = error?.limit ?? (currentRole === 'guest' ? 10 : 50)
    const currentUsed = error?.used ?? currentLimit

    used.value = currentUsed
    limit.value = currentLimit

    const defaultMessage =
      currentRole === 'guest'
        ? `今日游客体验额度已用尽（${currentUsed}/${currentLimit} 次）。注册登录即可获得 5 倍额度，并开启云端存档。`
        : `今日免费对话配额已达上限（${currentUsed}/${currentLimit} 次），将于明日 00:00 自动重置。`

    quotaExceededDetails.value = {
      code: 'QUOTA_EXCEEDED',
      role: currentRole,
      used: currentUsed,
      limit: currentLimit,
      resetAt: error?.resetAt ?? resetAt.value,
      message: error?.message || defaultMessage,
    }

    if (currentRole === 'guest') {
      showGuestUpgradeModal.value = true
      showUserQuotaToast.value = false
    } else {
      showUserQuotaToast.value = true
      showGuestUpgradeModal.value = false
    }
  }

  function triggerContextTooLong(error: Partial<ContextTooLongError>) {
    const authStore = useAuthStore()
    const limitVal = error.limit ?? contextTokenLimit.value
    contextOverflowAlert.value = {
      code: 'CONTEXT_TOO_LONG',
      role: error.role ?? authStore.role,
      estimatedTokens: error.estimatedTokens ?? limitVal + 100,
      limit: limitVal,
      message:
        error.message ||
        `单次提交上下文超出当前角色上限（${limitVal.toLocaleString()} Tokens），不消耗对话次数。`,
    }
  }

  function dismissModals() {
    showGuestUpgradeModal.value = false
    showUserQuotaToast.value = false
    quotaExceededDetails.value = null
  }

  function dismissContextAlert() {
    contextOverflowAlert.value = null
  }

  function recordCallAndTokens(tokensDelta = 0, isMock = false) {
    if (!unlimited.value) {
      used.value = Math.min(limit.value, used.value + 1)
    } else {
      used.value += 1
    }
    if (!isMock && tokensDelta > 0) {
      tokensUsedToday.value += tokensDelta
    }
  }

  /**
   * 估算输入与上下文的 Token 数
   * 中文字符约 0.65 token/字，英文单词/代码约 0.3 token/字符
   */
  function estimateTokens(text: string): number {
    if (!text) return 0
    let cjkCount = 0
    let otherCount = 0
    for (let i = 0; i < text.length; i++) {
      const code = text.charCodeAt(i)
      if (code >= 0x4e00 && code <= 0x9fff) {
        cjkCount++
      } else {
        otherCount++
      }
    }
    return Math.max(1, Math.ceil(cjkCount * 0.7 + otherCount * 0.3))
  }

  return {
    used,
    limit,
    unlimited,
    tokensUsedToday,
    contextTokenLimit,
    resetAt,
    resetInSeconds,
    dimensions,
    usagePercentage,
    isQuotaExhausted,
    remainingCalls,
    showGuestUpgradeModal,
    showUserQuotaToast,
    quotaExceededDetails,
    contextOverflowAlert,
    applyRoleDefaults,
    applyQuotaStatus,
    fetchQuotaStatus,
    triggerQuotaExceeded,
    triggerContextTooLong,
    dismissModals,
    dismissContextAlert,
    recordCallAndTokens,
    estimateTokens,
  }
})
