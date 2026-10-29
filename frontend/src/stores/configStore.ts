import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  AdminModelInfo,
  ImportEnvResponse,
  ModelInfo,
  ModelUpdatePayload,
  ModelsListResponse,
  SyncSettingsResponse,
  ThemeMode,
  UserSettings,
} from '@/types/api'
import { apiRequest } from '@/utils/apiClient'
import { useAuthStore } from '@/stores/authStore'

const SETTINGS_STORAGE_KEY = 'xcweb_user_settings_v1'

const FALLBACK_PRESET_MODELS: ModelInfo[] = [
  {
    id: 'deepseek-ai/DeepSeek-R1-Distill-Qwen-7B',
    name: 'DeepSeek-R1-Distill-Qwen-7B',
    provider: 'SiliconFlow',
    description: '默认主力推理模型，支持深度思考思维链输出',
    supportsThinking: true,
    enabled: true,
    isDefault: true,
    isMock: false,
    keyConfigured: false,
  },
  {
    id: 'Qwen/Qwen2.5-7B-Instruct',
    name: 'Qwen2.5-7B-Instruct',
    provider: 'SiliconFlow',
    description: '硅基流动通用对话与指令遵循模型',
    supportsThinking: false,
    enabled: true,
    isDefault: false,
    isMock: false,
    keyConfigured: false,
  },
  {
    id: 'glm-4-flash',
    name: 'GLM-4-Flash',
    provider: 'BigModel',
    description: '智谱 AI 免费高速模型，长上下文分析与快速问答',
    supportsThinking: false,
    enabled: true,
    isDefault: false,
    isMock: false,
    keyConfigured: false,
  },
  {
    id: 'llama-3.3-70b-versatile',
    name: 'Llama-3.3-70B-Versatile',
    provider: 'Groq',
    description: 'Groq Cloud 超低延迟代码生成与逻辑推理',
    supportsThinking: false,
    enabled: true,
    isDefault: false,
    isMock: false,
    keyConfigured: false,
  },
  {
    id: 'mock-cognitive-core',
    name: 'XCWeb Cognitive Mock Core',
    provider: 'BuiltInMock',
    description: '无需网络与 Key，内置模拟深度思考、Agent 工具链与流式输出',
    supportsThinking: true,
    enabled: true,
    isDefault: false,
    isMock: true,
    keyConfigured: true,
  },
]

const DEFAULT_SYSTEM_PROMPT =
  '你是 XCWeb（Xtreme Cognitive Web）智能认知中枢，请以严谨、清晰、富有洞察力的结构化方式回答用户问题。'

export const useConfigStore = defineStore('config', () => {
  const saved = loadLocalSettings()

  const theme = ref<ThemeMode>(saved.theme || 'dark')
  const previousThemeBeforeIncognito = ref<'dark' | 'light'>(
    saved.theme === 'light' ? 'light' : 'dark',
  )
  const models = ref<ModelInfo[]>(FALLBACK_PRESET_MODELS)
  const allExternalKeysMissing = ref<boolean>(false)
  const selectedModel = ref<string>(
    saved.defaultModel || 'deepseek-ai/DeepSeek-R1-Distill-Qwen-7B',
  )
  const systemPrompt = ref<string>(saved.systemPrompt || DEFAULT_SYSTEM_PROMPT)
  const enableThinking = ref<boolean>(saved.enableThinking ?? true)
  const enableAgent = ref<boolean>(saved.enableAgent ?? false)
  const settingsUpdatedAt = ref<number>(saved.updatedAt || Date.now())
  const isSyncingSettings = ref<boolean>(false)

  // 系统级弹窗控制状态
  const showSettingsModal = ref<boolean>(false)
  const showAdminModal = ref<boolean>(false)
  const showDevConsoleModal = ref<boolean>(false)
  const showModelConfigModal = ref<boolean>(false)
  const isSidebarCollapsed = ref<boolean>(false)

  const allManageableModels = ref<AdminModelInfo[]>([])
  const isImportingEnv = ref<boolean>(false)

  // 预留给阶段 2 (@Motion-FX-Engineer) 对角线扫屏动效钩子
  const customThemeSweepHandler = ref<
    ((targetTheme: ThemeMode, triggerEl?: HTMLElement | null) => Promise<void> | void) | null
  >(null)

  function loadLocalSettings(): Partial<UserSettings> {
    try {
      const raw = localStorage.getItem(SETTINGS_STORAGE_KEY)
      if (!raw) return {}
      return JSON.parse(raw) as Partial<UserSettings>
    } catch {
      return {}
    }
  }

  function saveLocalSettings() {
    try {
      const payload: UserSettings = {
        systemPrompt: systemPrompt.value,
        theme: theme.value,
        defaultModel: selectedModel.value,
        enableThinking: enableThinking.value,
        enableAgent: enableAgent.value,
        updatedAt: settingsUpdatedAt.value,
      }
      localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(payload))
    } catch {
      // ignore
    }
  }

  function applyDomTheme(target: ThemeMode) {
    if (typeof document !== 'undefined') {
      document.documentElement.setAttribute('data-theme', target)
    }
  }

  // 初始化时立即应用主题属性
  applyDomTheme(theme.value)

  const enabledModels = computed<ModelInfo[]>(() => {
    const active = models.value.filter((m) => m.enabled)
    return active.length > 0 ? active : FALLBACK_PRESET_MODELS
  })

  const currentModelInfo = computed<ModelInfo>(() => {
    return (
      enabledModels.value.find((m) => m.id === selectedModel.value) ||
      enabledModels.value.find((m) => m.isDefault) ||
      enabledModels.value[0] ||
      FALLBACK_PRESET_MODELS[0]
    )
  })

  async function setTheme(
    nextTheme: ThemeMode,
    triggerEl?: HTMLElement | null,
  ): Promise<void> {
    if (nextTheme !== 'incognito') {
      previousThemeBeforeIncognito.value = nextTheme
    }

    if (customThemeSweepHandler.value && nextTheme !== theme.value) {
      await customThemeSweepHandler.value(nextTheme, triggerEl)
    }

    theme.value = nextTheme
    applyDomTheme(nextTheme)
    settingsUpdatedAt.value = Date.now()
    saveLocalSettings()
  }

  function toggleLightDarkTheme(triggerEl?: HTMLElement | null) {
    if (theme.value === 'incognito') {
      // 无痕模式下保持 incognito 视觉，或者切换回明暗
      const next: ThemeMode =
        previousThemeBeforeIncognito.value === 'dark' ? 'light' : 'dark'
      previousThemeBeforeIncognito.value = next
      void setTheme(next, triggerEl)
      return
    }
    const next: ThemeMode = theme.value === 'dark' ? 'light' : 'dark'
    void setTheme(next, triggerEl)
  }

  function registerThemeSweepHandler(
    handler: (targetTheme: ThemeMode, triggerEl?: HTMLElement | null) => Promise<void> | void,
  ) {
    customThemeSweepHandler.value = handler
  }

  async function fetchModels(): Promise<ModelInfo[]> {
    try {
      const res = await apiRequest<ModelsListResponse>('/api/models')
      if (Array.isArray(res.models) && res.models.length > 0) {
        models.value = res.models
        allExternalKeysMissing.value = Boolean(res.allExternalKeysMissing)
        const currentExists = res.models.some(
          (m) => m.id === selectedModel.value && m.enabled,
        )
        if (!currentExists) {
          selectedModel.value =
            res.defaultModel ||
            res.models.find((m) => m.isDefault)?.id ||
            res.models[0].id
        }
      }
      return models.value
    } catch {
      models.value = FALLBACK_PRESET_MODELS
      allExternalKeysMissing.value = true
      return models.value
    }
  }

  function selectModel(modelId: string) {
    const target = enabledModels.value.find((m) => m.id === modelId)
    if (!target) return
    selectedModel.value = target.id
    if (target.supportsThinking) {
      enableThinking.value = true
    }
    settingsUpdatedAt.value = Date.now()
    saveLocalSettings()
    void pushCloudSettings()
  }

  async function updateSettings(partial: Partial<UserSettings>): Promise<void> {
    if (partial.systemPrompt !== undefined) {
      systemPrompt.value = partial.systemPrompt
    }
    if (partial.theme !== undefined) {
      await setTheme(partial.theme)
    }
    if (partial.defaultModel !== undefined) {
      selectedModel.value = partial.defaultModel
    }
    if (partial.enableThinking !== undefined) {
      enableThinking.value = partial.enableThinking
    }
    if (partial.enableAgent !== undefined) {
      enableAgent.value = partial.enableAgent
    }
    settingsUpdatedAt.value = Date.now()
    saveLocalSettings()
    await pushCloudSettings()
  }

  async function pullCloudSettings(): Promise<void> {
    const authStore = useAuthStore()
    if (authStore.isGuest || !authStore.token) return

    isSyncingSettings.value = true
    try {
      const res = await apiRequest<SyncSettingsResponse>('/api/sync/settings')
      if (res && res.settingsJson) {
        const parsed = JSON.parse(res.settingsJson) as Partial<UserSettings>
        if (!res.updatedAt || res.updatedAt >= settingsUpdatedAt.value) {
          if (parsed.systemPrompt !== undefined) systemPrompt.value = parsed.systemPrompt
          if (parsed.defaultModel !== undefined) selectedModel.value = parsed.defaultModel
          if (parsed.enableThinking !== undefined)
            enableThinking.value = parsed.enableThinking
          if (parsed.enableAgent !== undefined) enableAgent.value = parsed.enableAgent
          if (parsed.theme && parsed.theme !== 'incognito') {
            theme.value = parsed.theme
            applyDomTheme(parsed.theme)
          }
          settingsUpdatedAt.value = res.updatedAt || Date.now()
          saveLocalSettings()
        }
      }
    } catch {
      // ignore offline or unconfigured sync endpoint
    } finally {
      isSyncingSettings.value = false
    }
  }

  async function pushCloudSettings(): Promise<void> {
    const authStore = useAuthStore()
    if (authStore.isGuest || !authStore.token) return

    isSyncingSettings.value = true
    try {
      const settingsPayload: UserSettings = {
        systemPrompt: systemPrompt.value,
        theme: theme.value === 'incognito' ? previousThemeBeforeIncognito.value : theme.value,
        defaultModel: selectedModel.value,
        enableThinking: enableThinking.value,
        enableAgent: enableAgent.value,
        updatedAt: settingsUpdatedAt.value,
      }
      await apiRequest<{ status?: string }>('/api/sync/settings', {
        method: 'PUT',
        body: JSON.stringify({
          settingsJson: JSON.stringify(settingsPayload),
          updatedAt: settingsUpdatedAt.value,
        }),
      })
    } catch {
      // ignore offline error
    } finally {
      isSyncingSettings.value = false
    }
  }

  async function fetchManageableModels(): Promise<AdminModelInfo[]> {
    try {
      const res = await apiRequest<{ models?: AdminModelInfo[] } | AdminModelInfo[]>('/api/models/manage')
      const list = Array.isArray(res) ? res : (res.models ?? [])
      allManageableModels.value = list
      return list
    } catch {
      allManageableModels.value = models.value.map((m) => ({
        ...m,
        keyConfigured: Boolean(m.keyConfigured ?? m.isMock),
      }))
      return allManageableModels.value
    }
  }

  async function addModel(payload: ModelUpdatePayload): Promise<AdminModelInfo | null> {
    try {
      const added = await apiRequest<AdminModelInfo>('/api/models', {
        method: 'POST',
        body: JSON.stringify(payload),
      })
      await fetchModels()
      await fetchManageableModels()
      return added
    } catch (e) {
      throw e
    }
  }

  async function updateModelConfig(payload: ModelUpdatePayload): Promise<AdminModelInfo | null> {
    try {
      const updated = await apiRequest<AdminModelInfo>('/api/models', {
        method: 'PUT',
        body: JSON.stringify(payload),
      })
      await fetchModels()
      await fetchManageableModels()
      return updated
    } catch (e) {
      throw e
    }
  }

  async function deleteModel(modelId: string): Promise<boolean> {
    try {
      await apiRequest<{ success?: boolean }>(
        `/api/models?id=${encodeURIComponent(modelId)}`,
        {
          method: 'DELETE',
          body: JSON.stringify({ id: modelId }),
        },
      )
      await fetchModels()
      await fetchManageableModels()
      return true
    } catch (e) {
      throw e
    }
  }

  async function restorePresetModels(): Promise<AdminModelInfo[]> {
    try {
      await apiRequest('/api/models/restore-presets', { method: 'POST' })
      await fetchModels()
      return await fetchManageableModels()
    } catch (e) {
      throw e
    }
  }

  async function importEnvConfig(consent: boolean): Promise<ImportEnvResponse> {
    isImportingEnv.value = true
    try {
      const res = await apiRequest<ImportEnvResponse>('/api/models/import-env', {
        method: 'POST',
        body: JSON.stringify({ consent }),
      })
      if (res && Array.isArray(res.models)) {
        allManageableModels.value = res.models
      }
      await fetchModels()
      return res
    } finally {
      isImportingEnv.value = false
    }
  }

  return {
    theme,
    previousThemeBeforeIncognito,
    models,
    enabledModels,
    currentModelInfo,
    allExternalKeysMissing,
    selectedModel,
    systemPrompt,
    enableThinking,
    enableAgent,
    settingsUpdatedAt,
    isSyncingSettings,
    showSettingsModal,
    showAdminModal,
    showDevConsoleModal,
    showModelConfigModal,
    isSidebarCollapsed,
    allManageableModels,
    isImportingEnv,
    setTheme,
    toggleLightDarkTheme,
    registerThemeSweepHandler,
    fetchModels,
    fetchManageableModels,
    addModel,
    updateModelConfig,
    deleteModel,
    restorePresetModels,
    importEnvConfig,
    selectModel,
    updateSettings,
    pullCloudSettings,
    pushCloudSettings,
  }
})
