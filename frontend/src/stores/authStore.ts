import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  AuthResponse,
  GuestSession,
  MeResponse,
  UserInfo,
  UserRole,
} from '@/types/api'
import { getBrowserFingerprint } from '@/utils/fingerprint'
import { apiRequest } from '@/utils/apiClient'
import { useQuotaStore } from '@/stores/quotaStore'
import { useConfigStore } from '@/stores/configStore'
import { useChatStore } from '@/stores/chatStore'

const TOKEN_STORAGE_KEY = 'xcweb_auth_token'
const USER_STORAGE_KEY = 'xcweb_auth_user'
const GUEST_ID_STORAGE_KEY = 'xcweb_guest_id'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_STORAGE_KEY) || '')
  const fingerprintHash = ref<string>('')
  const guestId = ref<string>(localStorage.getItem(GUEST_ID_STORAGE_KEY) || '')
  const user = ref<UserInfo | null>(loadStoredUser())
  const isInitialized = ref<boolean>(false)
  const isLoading = ref<boolean>(false)
  const authError = ref<string | null>(null)
  const showAuthOverlay = ref<boolean>(!localStorage.getItem(TOKEN_STORAGE_KEY))
  const authOverlayInitialTab = ref<'login' | 'register' | 'guest'>('login')

  let isRecoveringAuth = false
  let inFlightGuestLogin: Promise<GuestSession> | null = null

  function loadStoredUser(): UserInfo | null {
    try {
      const raw = localStorage.getItem(USER_STORAGE_KEY)
      if (!raw) return null
      return JSON.parse(raw) as UserInfo
    } catch {
      return null
    }
  }

  const role = computed<UserRole>(() => {
    return user.value?.role ?? 'guest'
  })

  const isLoggedIn = computed<boolean>(() => {
    return role.value !== 'guest' && Boolean(token.value)
  })

  const isGuest = computed<boolean>(() => role.value === 'guest')
  const isAdmin = computed<boolean>(() => role.value === 'admin')
  const isDev = computed<boolean>(() => role.value === 'dev')

  const displayName = computed<string>(() => {
    if (user.value?.username) return user.value.username
    if (guestId.value) return `游客 #${guestId.value.slice(-4)}`
    return '匿名访客'
  })

  function persistAuthState(newToken: string, newUser: UserInfo | null, newGuestId?: string) {
    token.value = newToken
    user.value = newUser
    if (newGuestId !== undefined) {
      guestId.value = newGuestId
    }
    try {
      if (newToken) {
        localStorage.setItem(TOKEN_STORAGE_KEY, newToken)
      } else {
        localStorage.removeItem(TOKEN_STORAGE_KEY)
      }

      if (newUser) {
        localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(newUser))
      } else {
        localStorage.removeItem(USER_STORAGE_KEY)
      }

      if (newGuestId) {
        localStorage.setItem(GUEST_ID_STORAGE_KEY, newGuestId)
      }
    } catch {
      // ignore storage quota errors
    }
  }

  async function initAuth(): Promise<void> {
    if (isInitialized.value) return
    isLoading.value = true
    authError.value = null

    try {
      fingerprintHash.value = await getBrowserFingerprint()

      if (token.value && user.value && user.value.role !== 'guest') {
        try {
          const me = await apiRequest<MeResponse>('/api/auth/me')
          persistAuthState(token.value, me.user)
          const quotaStore = useQuotaStore()
          quotaStore.applyQuotaStatus(me.quota)
        } catch {
          persistAuthState('', null)
          await loginAsGuest(true)
        }
      } else {
        await loginAsGuest(true)
      }
    } finally {
      isInitialized.value = true
      isLoading.value = false
    }
  }

  async function loginAsGuest(silent = false): Promise<GuestSession> {
    if (inFlightGuestLogin) {
      return inFlightGuestLogin
    }

    inFlightGuestLogin = (async () => {
      isLoading.value = true
      authError.value = null
      try {
        if (!fingerprintHash.value) {
          fingerprintHash.value = await getBrowserFingerprint()
        }

        let session: GuestSession
        let isFallback = false
        try {
          session = await apiRequest<GuestSession>('/api/auth/guest', {
            method: 'POST',
            body: JSON.stringify({ fingerprintHash: fingerprintHash.value }),
          })
        } catch {
          // 离线或后端未启动时的本地兜底匿名会话
          isFallback = true
          const fallbackId =
            guestId.value || `guest_${fingerprintHash.value.slice(0, 10)}`
          session = {
            token: `guest_offline_${fingerprintHash.value.slice(0, 24)}`,
            guestId: fallbackId,
            fingerprintHash: fingerprintHash.value,
            role: 'guest',
            expiresAt: Date.now() + 30 * 24 * 3600 * 1000,
          }
        }

        persistAuthState(session.token, null, session.guestId)

        const configStore = useConfigStore()
        if (configStore.theme === 'incognito') {
          configStore.setTheme('dark')
        }

        const quotaStore = useQuotaStore()
        if (isFallback) {
          quotaStore.applyRoleDefaults('guest')
        } else {
          try {
            await quotaStore.fetchQuotaStatus()
          } catch {
            quotaStore.applyRoleDefaults('guest')
          }
        }

        const chatStore = useChatStore()
        chatStore.setIncognito(false)
        try {
          await chatStore.loadConversations()
        } catch {
          // offline conversations ignore
        }

        if (!silent) {
          showAuthOverlay.value = false
        }
        return session
      } finally {
        isLoading.value = false
        inFlightGuestLogin = null
      }
    })()

    return inFlightGuestLogin
  }

  async function login(username: string, password: string): Promise<UserInfo> {
    isLoading.value = true
    authError.value = null
    try {
      const res = await apiRequest<AuthResponse>('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      })

      persistAuthState(res.token, res.user)
      showAuthOverlay.value = false

      const quotaStore = useQuotaStore()
      quotaStore.dismissModals()
      await quotaStore.fetchQuotaStatus()

      const configStore = useConfigStore()
      await configStore.pullCloudSettings()

      const chatStore = useChatStore()
      await chatStore.loadConversations()
      await chatStore.syncConversationsWithCloud()

      return res.user
    } catch (err) {
      const msg = err instanceof Error ? err.message : '登录失败，请检查账号或密码'
      authError.value = msg
      throw err
    } finally {
      isLoading.value = false
    }
  }

  async function register(
    username: string,
    email: string,
    password: string,
  ): Promise<UserInfo> {
    isLoading.value = true
    authError.value = null
    try {
      const res = await apiRequest<AuthResponse>('/api/auth/register', {
        method: 'POST',
        body: JSON.stringify({ username, email, password }),
      })

      persistAuthState(res.token, res.user)
      showAuthOverlay.value = false

      const quotaStore = useQuotaStore()
      quotaStore.dismissModals()
      await quotaStore.fetchQuotaStatus()

      const configStore = useConfigStore()
      await configStore.pushCloudSettings()

      const chatStore = useChatStore()
      await chatStore.loadConversations()
      await chatStore.syncConversationsWithCloud()

      return res.user
    } catch (err) {
      const msg = err instanceof Error ? err.message : '注册失败，请检查输入信息'
      authError.value = msg
      throw err
    } finally {
      isLoading.value = false
    }
  }

  async function logout(): Promise<void> {
    persistAuthState('', null)
    const configStore = useConfigStore()
    if (configStore.theme === 'incognito') {
      configStore.setTheme('dark')
    }
    const chatStore = useChatStore()
    chatStore.setIncognito(false)
    await loginAsGuest(true)
  }

  /**
   * 演示与测试用快捷角色切换辅助方法
   * 支持在 Guest / User / Admin / Dev 四级角色间一键切换体验权限与配额策略
   */
  async function switchDemoRole(targetRole: UserRole): Promise<void> {
    const quotaStore = useQuotaStore()
    const chatStore = useChatStore()
    const configStore = useConfigStore()

    quotaStore.dismissModals()

    if (targetRole === 'guest') {
      await logout()
      return
    }

    const demoUsers: Record<Exclude<UserRole, 'guest'>, UserInfo> = {
      user: {
        id: 101,
        username: 'demo_user',
        email: 'user@xcweb.ai',
        role: 'user',
        createdAt: new Date().toISOString(),
      },
      admin: {
        id: 1,
        username: 'admin',
        email: 'admin@xcweb.ai',
        role: 'admin',
        createdAt: new Date().toISOString(),
      },
      dev: {
        id: 2,
        username: 'developer',
        email: 'dev@xcweb.ai',
        role: 'dev',
        createdAt: new Date().toISOString(),
      },
    }

    const targetUser = demoUsers[targetRole]
    persistAuthState(`demo_jwt_${targetRole}_${Date.now()}`, targetUser)

    quotaStore.applyRoleDefaults(targetRole)
    await quotaStore.fetchQuotaStatus()
    await configStore.fetchModels()
    await chatStore.loadConversations()
  }

  function openAuthOverlay(tab: 'login' | 'register' | 'guest' = 'login') {
    authOverlayInitialTab.value = tab
    authError.value = null
    showAuthOverlay.value = true
  }

  function closeAuthOverlay() {
    showAuthOverlay.value = false
    authError.value = null
  }

  function handleUnauthorized(reason?: string) {
    if (isRecoveringAuth) return
    isRecoveringAuth = true
    authError.value = reason || '登录状态已失效，请重新认证'

    const hadValidToken = Boolean(
      token.value &&
      !token.value.startsWith('guest_offline_') &&
      !token.value.startsWith('demo_')
    )

    persistAuthState('', null)

    if (hadValidToken) {
      void loginAsGuest(true).finally(() => {
        setTimeout(() => {
          isRecoveringAuth = false
        }, 1500)
      })
    } else {
      setTimeout(() => {
        isRecoveringAuth = false
      }, 1000)
    }
  }

  return {
    token,
    fingerprintHash,
    guestId,
    user,
    role,
    isLoggedIn,
    isGuest,
    isAdmin,
    isDev,
    displayName,
    isInitialized,
    isLoading,
    authError,
    showAuthOverlay,
    authOverlayInitialTab,
    initAuth,
    loginAsGuest,
    login,
    register,
    logout,
    switchDemoRole,
    openAuthOverlay,
    closeAuthOverlay,
    handleUnauthorized,
  }
})
