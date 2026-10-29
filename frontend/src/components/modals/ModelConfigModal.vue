<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  X,
  Cpu,
  Key,
  Globe,
  CheckCircle2,
  AlertCircle,
  ShieldCheck,
  Star,
  RefreshCw,
  Eye,
  EyeOff,
  Sparkles,
  HelpCircle,
  Save,
  Plus,
  Trash2,
} from 'lucide-vue-next'
import { useConfigStore } from '@/stores/configStore'
import type { AdminModelInfo, ImportEnvResponse } from '@/types/api'

const configStore = useConfigStore()

const isLoading = ref<boolean>(false)
const showConsentConfirm = ref<boolean>(false)
const envFeedback = ref<{
  type: 'success' | 'info' | 'error'
  message: string
  variables?: string[]
} | null>(null)

// 新增模型表单状态
const isAddingModel = ref<boolean>(false)
const newModel = ref({
  id: '',
  name: '',
  provider: 'SiliconFlow',
  baseUrl: '',
  apiKey: '',
  description: '',
  supportsThinking: false,
  enabled: true,
  isDefault: false,
  showKeyPlain: false,
  isSubmitting: false,
  error: null as string | null,
})

// 删除模型二次确认状态
const modelToDelete = ref<AdminModelInfo | null>(null)
const isDeleting = ref<boolean>(false)

// 编辑中的模型草稿缓存，key 为 modelId
interface EditFormState {
  name: string
  provider: string
  baseUrl: string
  apiKey: string
  description: string
  supportsThinking: boolean
  enabled: boolean
  isDefault: boolean
  showKeyPlain: boolean
  isSaving: boolean
  saveMessage: string | null
}

const editDrafts = ref<Record<string, EditFormState>>({})

function initDrafts(models: AdminModelInfo[]) {
  for (const m of models) {
    if (!editDrafts.value[m.id]) {
      editDrafts.value[m.id] = {
        name: m.name || m.id,
        provider: m.provider || 'Custom',
        baseUrl: m.baseUrl || '',
        apiKey: m.apiKey || '',
        description: m.description || '',
        supportsThinking: Boolean(m.supportsThinking),
        enabled: Boolean(m.enabled),
        isDefault: Boolean(m.isDefault),
        showKeyPlain: false,
        isSaving: false,
        saveMessage: null,
      }
    }
  }
}

async function loadData() {
  isLoading.value = true
  try {
    const list = await configStore.fetchManageableModels()
    initDrafts(list)
  } finally {
    isLoading.value = false
  }
}

onMounted(() => {
  void loadData()
})

// 触发读取系统环境变量确认弹窗
function handleRequestImportEnv() {
  showConsentConfirm.value = true
}

// 用户明确确认授权读取环境变量
async function handleConfirmImportEnv() {
  showConsentConfirm.value = false
  isLoading.value = true
  envFeedback.value = null

  try {
    const res: ImportEnvResponse = await configStore.importEnvConfig(true)
    if (res.success) {
      envFeedback.value = {
        type: res.detectedVariables.length > 0 ? 'success' : 'info',
        message: res.message,
        variables: res.detectedVariables,
      }
      initDrafts(configStore.allManageableModels)
    } else {
      envFeedback.value = {
        type: 'error',
        message: res.message || '读取系统环境变量失败',
      }
    }
  } catch (err: unknown) {
    const error = err as Error
    envFeedback.value = {
      type: 'error',
      message: error.message || '授权读取环境变量时出现异常',
    }
  } finally {
    isLoading.value = false
  }
}

// 新增模型
async function handleCreateModel() {
  const targetId = newModel.value.id.trim()
  if (!targetId) {
    newModel.value.error = '模型标识 (ID) 不能为空'
    return
  }

  newModel.value.isSubmitting = true
  newModel.value.error = null

  try {
    const payload = {
      id: targetId,
      name: newModel.value.name.trim() || targetId,
      provider: newModel.value.provider.trim() || 'Custom',
      baseUrl: newModel.value.baseUrl.trim(),
      apiKey: newModel.value.apiKey.trim(),
      description: newModel.value.description.trim(),
      supportsThinking: newModel.value.supportsThinking,
      enabled: newModel.value.enabled,
      isDefault: newModel.value.isDefault,
    }

    const added = await configStore.addModel(payload)
    if (added) {
      editDrafts.value[added.id] = {
        name: added.name || added.id,
        provider: added.provider || 'Custom',
        baseUrl: added.baseUrl || '',
        apiKey: added.apiKey || '',
        description: added.description || '',
        supportsThinking: Boolean(added.supportsThinking),
        enabled: Boolean(added.enabled),
        isDefault: Boolean(added.isDefault),
        showKeyPlain: false,
        isSaving: false,
        saveMessage: null,
      }
    }

    isAddingModel.value = false
    newModel.value = {
      id: '',
      name: '',
      provider: 'SiliconFlow',
      baseUrl: '',
      apiKey: '',
      description: '',
      supportsThinking: false,
      enabled: true,
      isDefault: false,
      showKeyPlain: false,
      isSubmitting: false,
      error: null,
    }
  } catch (err: unknown) {
    const error = err as Error
    newModel.value.error = error.message || '新增模型失败'
  } finally {
    newModel.value.isSubmitting = false
  }
}

// 触发删除确认
function requestDeleteModel(model: AdminModelInfo) {
  modelToDelete.value = model
}

// 确认删除模型
async function handleConfirmDelete() {
  if (!modelToDelete.value) return
  isDeleting.value = true
  const id = modelToDelete.value.id

  try {
    await configStore.deleteModel(id)
    delete editDrafts.value[id]
    modelToDelete.value = null
  } catch (err: unknown) {
    const error = err as Error
    alert(`删除模型失败: ${error.message || '网络异常'}`)
  } finally {
    isDeleting.value = false
  }
}

// 保存单个模型的自定义配置
async function handleSaveModel(model: AdminModelInfo) {
  const draft = editDrafts.value[model.id]
  if (!draft) return

  draft.isSaving = true
  draft.saveMessage = null

  try {
    const payload = {
      id: model.id,
      modelId: model.id,
      name: draft.name,
      provider: draft.provider,
      baseUrl: draft.baseUrl,
      apiKey: draft.apiKey,
      description: draft.description,
      supportsThinking: draft.supportsThinking,
      enabled: draft.enabled,
      isDefault: draft.isDefault,
    }

    await configStore.updateModelConfig(payload)
    draft.saveMessage = '保存成功！'
    setTimeout(() => {
      if (draft) draft.saveMessage = null
    }, 2500)
  } catch (err: unknown) {
    const error = err as Error
    draft.saveMessage = `保存失败: ${error.message || '网络异常'}`
  } finally {
    draft.isSaving = false
  }
}

// 快速切换启用/停用
async function handleToggleEnabled(model: AdminModelInfo) {
  const draft = editDrafts.value[model.id]
  if (!draft) return
  draft.enabled = !draft.enabled
  await handleSaveModel(model)
}

// 快速设为默认模型
async function handleSetDefault(model: AdminModelInfo) {
  const draft = editDrafts.value[model.id]
  if (!draft) return
  draft.isDefault = true
  draft.enabled = true
  for (const [key, d] of Object.entries(editDrafts.value)) {
    if (key !== model.id) {
      d.isDefault = false
    }
  }
  await handleSaveModel(model)
}
</script>

<template>
  <div
    class="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 bg-black/75 backdrop-blur-md animate-fade-in"
    @click.self="configStore.showModelConfigModal = false"
  >
    <div
      class="relative w-full max-w-4xl max-h-[90vh] flex flex-col rounded-2xl glass-modal border border-subtle shadow-2xl overflow-hidden bg-card/95"
    >
      <!-- 弹窗顶栏 -->
      <div
        class="flex items-center justify-between px-6 py-4 border-b border-subtle bg-elevated/40 shrink-0"
      >
        <div class="flex items-center gap-3">
          <div
            class="w-10 h-10 rounded-xl bg-primary/20 border border-primary/40 flex items-center justify-center shadow-cyber-glow"
          >
            <Cpu class="w-5 h-5 text-primary-light" />
          </div>
          <div>
            <h2 class="text-base font-bold text-main flex items-center gap-2">
              <span>AI 模型配置与管理</span>
              <span
                class="text-[10px] font-mono px-2 py-0.5 rounded-full bg-cyber-cyan/15 text-cyber-cyan border border-cyber-cyan/30"
              >
                支持新增/删除/修改
              </span>
            </h2>
            <p class="text-xs text-muted">
              自由新增模型、删除模型、配置 API Key 与接口端点，或经授权自动读取本地系统环境变量
            </p>
          </div>
        </div>

        <button
          type="button"
          class="p-2 rounded-xl glass-panel text-muted hover:text-main hover:bg-elevated transition-colors"
          @click="configStore.showModelConfigModal = false"
        >
          <X class="w-5 h-5" />
        </button>
      </div>

      <!-- 弹窗主体内容滚动区 -->
      <div class="flex-1 overflow-y-auto p-4 sm:p-6 space-y-6">
        <!-- 环境变量一键读取授权卡片 -->
        <div
          class="p-4 sm:p-5 rounded-2xl bg-gradient-to-r from-primary/10 via-cyber-cyan/10 to-indigo-500/10 border border-primary/30 relative overflow-hidden space-y-3"
        >
          <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
            <div class="space-y-1">
              <div class="flex items-center gap-2 text-sm font-semibold text-main">
                <Sparkles class="w-4 h-4 text-cyber-cyan" />
                <span>从系统环境变量读取 AI 配置</span>
                <span
                  class="text-[10px] px-2 py-0.5 rounded bg-amber-500/15 text-amber-300 border border-amber-500/30 font-medium"
                >
                  需明确授权
                </span>
              </div>
              <p class="text-xs text-muted leading-relaxed">
                自动探测本地系统变量（如
                <code class="font-mono text-cyber-cyan">SILICONFLOW_API_KEY</code>、
                <code class="font-mono text-cyber-cyan">ZHIPU_API_KEY</code>、
                <code class="font-mono text-cyber-cyan">GROQ_API_KEY</code>、
                <code class="font-mono text-cyber-cyan">OPENAI_API_KEY</code>、
                <code class="font-mono text-cyber-cyan">DEEPSEEK_API_KEY</code>
                等）并注入对应模型，省去手工复制粘贴。
              </p>
            </div>

            <button
              type="button"
              class="cyber-btn-primary px-4 py-2 rounded-xl text-xs font-semibold shrink-0 flex items-center gap-1.5 shadow-cyber-glow"
              :disabled="configStore.isImportingEnv || isLoading"
              @click="handleRequestImportEnv"
            >
              <RefreshCw
                class="w-3.5 h-3.5"
                :class="configStore.isImportingEnv ? 'animate-spin' : ''"
              />
              <span>授权并读取系统变量</span>
            </button>
          </div>

          <!-- 授权确认警示浮层/提示 -->
          <div
            v-if="showConsentConfirm"
            class="p-3.5 rounded-xl bg-card border border-amber-500/40 shadow-xl space-y-2.5 animate-slide-up"
          >
            <div class="flex items-start gap-2 text-amber-400 text-xs">
              <ShieldCheck class="w-4 h-4 shrink-0 mt-0.5" />
              <div class="space-y-1">
                <p class="font-semibold">【安全授权确认】请确认是否允许读取系统环境变量？</p>
                <p class="text-muted leading-relaxed">
                  系统将读取本地宿主机的系统环境变量中的大模型 API 密钥与端点配置，并保存至后端 SQLite 数据库供本应用调用。系统绝不会向未知的第三方服务器泄露您的密钥。
                </p>
              </div>
            </div>
            <div class="flex items-center justify-end gap-2 pt-1">
              <button
                type="button"
                class="px-3 py-1.5 rounded-lg border border-subtle text-xs text-sub hover:text-main hover:bg-elevated transition-colors"
                @click="showConsentConfirm = false"
              >
                取消
              </button>
              <button
                type="button"
                class="cyber-btn-primary px-3.5 py-1.5 rounded-lg text-xs font-semibold shadow-cyber-glow"
                @click="handleConfirmImportEnv"
              >
                确认授权并立即读取
              </button>
            </div>
          </div>

          <!-- 环境变量导入结果反馈 -->
          <div
            v-if="envFeedback"
            class="p-3 rounded-xl border text-xs flex items-start gap-2 animate-fade-in"
            :class="
              envFeedback.type === 'success'
                ? 'bg-cyber-cyan/15 border-cyber-cyan/40 text-cyber-cyan'
                : envFeedback.type === 'error'
                  ? 'bg-red-500/15 border-red-500/40 text-red-400'
                  : 'bg-elevated border-subtle text-sub'
            "
          >
            <CheckCircle2
              v-if="envFeedback.type === 'success'"
              class="w-4 h-4 shrink-0 mt-0.5"
            />
            <AlertCircle v-else class="w-4 h-4 shrink-0 mt-0.5" />
            <div class="space-y-1">
              <p class="font-medium">{{ envFeedback.message }}</p>
              <div
                v-if="envFeedback.variables && envFeedback.variables.length > 0"
                class="flex flex-wrap gap-1.5 pt-1"
              >
                <span
                  v-for="v in envFeedback.variables"
                  :key="v"
                  class="px-2 py-0.5 rounded font-mono text-[10px] bg-cyber-cyan/20 border border-cyber-cyan/40 text-cyber-cyan"
                >
                  ✓ {{ v }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <!-- 新增模型卡片展开区域 -->
        <div
          v-if="isAddingModel"
          class="p-4 sm:p-5 rounded-2xl bg-card border border-primary/50 shadow-2xl space-y-4 animate-slide-up"
        >
          <div class="flex items-center justify-between pb-2 border-b border-subtle">
            <h4 class="text-xs font-bold text-main flex items-center gap-1.5">
              <Plus class="w-4 h-4 text-primary-light" />
              <span>新增模型配置</span>
            </h4>
            <button
              type="button"
              class="text-muted hover:text-main"
              @click="isAddingModel = false"
            >
              <X class="w-4 h-4" />
            </button>
          </div>

          <div
            v-if="newModel.error"
            class="p-2.5 rounded-xl bg-red-500/15 border border-red-500/40 text-red-400 text-xs"
          >
            {{ newModel.error }}
          </div>

          <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
            <div class="space-y-1">
              <label class="text-sub font-medium">模型标识 (Model ID / 模型代号) *</label>
              <input
                v-model="newModel.id"
                type="text"
                placeholder="例如：gpt-4o 或 deepseek-chat"
                class="w-full px-3 py-2 rounded-xl bg-elevated border border-subtle text-main focus:border-primary/60 focus:outline-none font-mono text-xs"
              />
            </div>
            <div class="space-y-1">
              <label class="text-sub font-medium">显示名称 (Name)</label>
              <input
                v-model="newModel.name"
                type="text"
                placeholder="例如：GPT-4o 旗舰模型"
                class="w-full px-3 py-2 rounded-xl bg-elevated border border-subtle text-main focus:border-primary/60 focus:outline-none"
              />
            </div>
            <div class="space-y-1">
              <label class="text-sub font-medium">服务商 (Provider)</label>
              <input
                v-model="newModel.provider"
                type="text"
                placeholder="OpenAI, SiliconFlow, DeepSeek, ZhipuAI, Groq, Custom..."
                class="w-full px-3 py-2 rounded-xl bg-elevated border border-subtle text-main focus:border-primary/60 focus:outline-none"
              />
            </div>
            <div class="space-y-1">
              <label class="text-sub font-medium">接口地址 (Base URL)</label>
              <input
                v-model="newModel.baseUrl"
                type="text"
                placeholder="https://api.openai.com/v1"
                class="w-full px-3 py-2 rounded-xl bg-elevated border border-subtle text-main focus:border-primary/60 focus:outline-none font-mono text-xs"
              />
            </div>
            <div class="space-y-1 sm:col-span-2">
              <label class="text-sub font-medium">API 密钥 (API Key)</label>
              <div class="relative flex items-center">
                <input
                  v-model="newModel.apiKey"
                  :type="newModel.showKeyPlain ? 'text' : 'password'"
                  placeholder="请输入 sk- 开头的 API Key (可选)"
                  class="w-full pl-3 pr-10 py-2 rounded-xl bg-elevated border border-subtle text-main focus:border-primary/60 focus:outline-none font-mono text-xs"
                />
                <button
                  type="button"
                  class="absolute right-2.5 p-1 text-muted hover:text-main"
                  @click="newModel.showKeyPlain = !newModel.showKeyPlain"
                >
                  <EyeOff v-if="newModel.showKeyPlain" class="w-4 h-4" />
                  <Eye v-else class="w-4 h-4" />
                </button>
              </div>
            </div>
            <div class="space-y-1 sm:col-span-2">
              <label class="text-sub font-medium">模型描述 (Description)</label>
              <input
                v-model="newModel.description"
                type="text"
                placeholder="请输入模型简要说明..."
                class="w-full px-3 py-2 rounded-xl bg-elevated border border-subtle text-main focus:border-primary/60 focus:outline-none"
              />
            </div>
            <div class="sm:col-span-2 flex items-center justify-between pt-1">
              <div class="flex items-center gap-4">
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    v-model="newModel.supportsThinking"
                    type="checkbox"
                    class="rounded bg-elevated border-subtle text-primary focus:ring-primary w-4 h-4"
                  />
                  <span>支持深度思考思维链</span>
                </label>
                <label class="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    v-model="newModel.isDefault"
                    type="checkbox"
                    class="rounded bg-elevated border-subtle text-primary focus:ring-primary w-4 h-4"
                  />
                  <span>设为默认模型</span>
                </label>
              </div>
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  class="px-3 py-1.5 rounded-xl border border-subtle hover:bg-elevated text-sub hover:text-main"
                  @click="isAddingModel = false"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="cyber-btn-primary px-4 py-1.5 rounded-xl font-semibold shadow-cyber-glow flex items-center gap-1.5"
                  :disabled="newModel.isSubmitting"
                  @click="handleCreateModel"
                >
                  <RefreshCw v-if="newModel.isSubmitting" class="w-3.5 h-3.5 animate-spin" />
                  <span>确认添加</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 删除二次确认弹窗 -->
        <div
          v-if="modelToDelete"
          class="p-4 rounded-2xl bg-card border border-red-500/50 shadow-2xl space-y-3 animate-slide-up"
        >
          <div class="flex items-start gap-2.5 text-red-400">
            <Trash2 class="w-5 h-5 shrink-0 mt-0.5" />
            <div class="space-y-1 text-xs">
              <p class="font-bold text-sm">确认删除模型「{{ modelToDelete.name }}」？</p>
              <p class="text-muted leading-relaxed">
                模型标识：<code class="font-mono text-main">{{ modelToDelete.id }}</code>。删除后将从模型列表与对话选择器中完全移除。如需重新使用可在稍后重新添加。
              </p>
            </div>
          </div>
          <div class="flex items-center justify-end gap-2 pt-1">
            <button
              type="button"
              class="px-3 py-1.5 rounded-lg border border-subtle text-xs text-sub hover:text-main hover:bg-elevated transition-colors"
              @click="modelToDelete = null"
            >
              取消
            </button>
            <button
              type="button"
              class="px-3.5 py-1.5 rounded-lg text-xs font-semibold bg-red-600 hover:bg-red-500 text-white transition-colors shadow-lg"
              :disabled="isDeleting"
              @click="handleConfirmDelete"
            >
              {{ isDeleting ? '正在删除...' : '确认删除' }}
            </button>
          </div>
        </div>

        <!-- 模型列表配置表格/卡片集 -->
        <div class="space-y-4">
          <div class="flex items-center justify-between">
            <h3 class="text-sm font-bold text-main flex items-center gap-2">
              <span>模型库列表</span>
              <span class="text-xs font-mono text-muted">
                ({{ configStore.allManageableModels.length }} 个模型)
              </span>
            </h3>
            <div class="flex items-center gap-2">
              <button
                type="button"
                class="cyber-btn-primary px-3 py-1.5 rounded-xl text-xs font-semibold flex items-center gap-1.5 shadow-cyber-glow"
                @click="isAddingModel = !isAddingModel"
              >
                <Plus class="w-3.5 h-3.5" />
                <span>新增模型</span>
              </button>
              <button
                type="button"
                class="text-xs text-sub hover:text-main flex items-center gap-1 px-2.5 py-1.5 rounded-xl glass-panel"
                @click="loadData"
              >
                <RefreshCw class="w-3 h-3" :class="isLoading ? 'animate-spin' : ''" />
                <span>刷新</span>
              </button>
            </div>
          </div>

          <div class="space-y-4">
            <div
              v-for="model in configStore.allManageableModels"
              :key="model.id"
              class="p-4 sm:p-5 rounded-2xl glass-panel border border-subtle hover:border-primary/40 transition-all space-y-4"
              :class="editDrafts[model.id]?.isDefault ? 'ring-1 ring-primary/40 bg-primary/5' : ''"
            >
              <!-- 顶部模型基本信息与状态条 -->
              <div
                class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 pb-3 border-b border-subtle/60"
              >
                <div class="flex items-center gap-2.5 flex-wrap">
                  <span class="font-bold text-sm text-main">
                    {{ editDrafts[model.id]?.name || model.name }}
                  </span>
                  <span
                    class="font-mono text-[11px] px-2 py-0.5 rounded bg-elevated text-sub border border-subtle"
                  >
                    {{ model.id }}
                  </span>
                  <span
                    v-if="model.isMock"
                    class="text-[10px] px-2 py-0.5 rounded bg-amber-500/15 text-amber-300 border border-amber-500/30"
                  >
                    本地 Mock
                  </span>
                  <span
                    v-if="model.hasApiKey || model.keyConfigured"
                    class="text-[10px] px-2 py-0.5 rounded bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 flex items-center gap-1"
                  >
                    <CheckCircle2 class="w-3 h-3" />
                    已配置 Key
                  </span>
                  <span
                    v-else-if="!model.isMock"
                    class="text-[10px] px-2 py-0.5 rounded bg-red-500/15 text-red-400 border border-red-500/30"
                  >
                    未配置 Key
                  </span>
                </div>

                <div class="flex items-center gap-2 shrink-0">
                  <!-- 设为默认模型按钮 -->
                  <button
                    type="button"
                    class="px-2.5 py-1 rounded-lg text-xs font-medium flex items-center gap-1 transition-colors"
                    :class="
                      editDrafts[model.id]?.isDefault
                        ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                        : 'glass-panel text-muted hover:text-main'
                    "
                    @click="handleSetDefault(model)"
                  >
                    <Star class="w-3 h-3" :class="editDrafts[model.id]?.isDefault ? 'fill-current' : ''" />
                    <span>{{ editDrafts[model.id]?.isDefault ? '默认模型' : '设为默认' }}</span>
                  </button>

                  <!-- 启用 / 停用切换开关 -->
                  <button
                    type="button"
                    class="px-2.5 py-1 rounded-lg text-xs font-medium transition-colors"
                    :class="
                      editDrafts[model.id]?.enabled
                        ? 'bg-primary/20 text-primary-light border border-primary/40'
                        : 'bg-elevated text-muted border border-subtle'
                    "
                    @click="handleToggleEnabled(model)"
                  >
                    {{ editDrafts[model.id]?.enabled ? '已启用' : '已停用' }}
                  </button>

                  <!-- 删除模型按钮 -->
                  <button
                    type="button"
                    class="p-1.5 rounded-lg text-muted hover:text-red-400 hover:bg-red-500/10 transition-colors"
                    title="删除此模型"
                    @click="requestDeleteModel(model)"
                  >
                    <Trash2 class="w-4 h-4" />
                  </button>
                </div>
              </div>

              <!-- 表单可编辑配置项 -->
              <div v-if="editDrafts[model.id]" class="grid grid-cols-1 sm:grid-cols-2 gap-3.5 text-xs">
                <!-- 模型显示名称 -->
                <div class="space-y-1">
                  <label class="text-sub font-medium flex items-center gap-1">
                    <span>模型显示名称</span>
                  </label>
                  <input
                    v-model="editDrafts[model.id].name"
                    type="text"
                    placeholder="例如：DeepSeek-R1"
                    class="w-full px-3 py-2 rounded-xl bg-card border border-subtle text-main focus:border-primary/60 focus:outline-none font-sans"
                  />
                </div>

                <!-- 提供商 -->
                <div class="space-y-1">
                  <label class="text-sub font-medium flex items-center gap-1">
                    <span>服务提供商 (Provider)</span>
                  </label>
                  <input
                    v-model="editDrafts[model.id].provider"
                    type="text"
                    placeholder="SiliconFlow, ZhipuAI, Groq, OpenAI..."
                    class="w-full px-3 py-2 rounded-xl bg-card border border-subtle text-main focus:border-primary/60 focus:outline-none font-sans"
                  />
                </div>

                <!-- 自定义 Base URL 端点 -->
                <div class="space-y-1 sm:col-span-2">
                  <label class="text-sub font-medium flex items-center justify-between">
                    <span class="flex items-center gap-1">
                      <Globe class="w-3.5 h-3.5 text-muted" />
                      <span>API 端点地址 (Base URL)</span>
                    </span>
                    <span class="text-[11px] text-muted font-normal">留空则使用默认官方接口</span>
                  </label>
                  <input
                    v-model="editDrafts[model.id].baseUrl"
                    type="text"
                    placeholder="https://api.siliconflow.cn/v1 或 https://api.deepseek.com/v1"
                    class="w-full px-3 py-2 rounded-xl bg-card border border-subtle text-main focus:border-primary/60 focus:outline-none font-mono text-xs"
                  />
                </div>

                <!-- API Key 输入框 (支持掩码与明文切换) -->
                <div class="space-y-1 sm:col-span-2">
                  <label class="text-sub font-medium flex items-center justify-between">
                    <span class="flex items-center gap-1">
                      <Key class="w-3.5 h-3.5 text-muted" />
                      <span>API 密钥 (API Key)</span>
                    </span>
                    <span class="text-[11px] text-muted font-normal">
                      {{
                        model.hasApiKey
                          ? '已配置脱敏密钥；若需更换请输入新 Key 并保存'
                          : '尚未配置 Key，调用将自动降级为内置 Cognitive Mock Core'
                      }}
                    </span>
                  </label>
                  <div class="relative flex items-center">
                    <input
                      v-model="editDrafts[model.id].apiKey"
                      :type="editDrafts[model.id].showKeyPlain ? 'text' : 'password'"
                      :placeholder="model.hasApiKey ? '保持当前已配置密钥（如需更换请在此输入新 Key）' : '请输入 sk- 开头的 API Key'"
                      class="w-full pl-3 pr-10 py-2 rounded-xl bg-card border border-subtle text-main focus:border-primary/60 focus:outline-none font-mono text-xs"
                    />
                    <button
                      type="button"
                      class="absolute right-2.5 p-1 text-muted hover:text-main transition-colors"
                      :title="editDrafts[model.id].showKeyPlain ? '隐藏密钥' : '显示明文'"
                      @click="editDrafts[model.id].showKeyPlain = !editDrafts[model.id].showKeyPlain"
                    >
                      <EyeOff v-if="editDrafts[model.id].showKeyPlain" class="w-4 h-4" />
                      <Eye v-else class="w-4 h-4" />
                    </button>
                  </div>
                </div>

                <!-- 深度思考支持开关 & 简介 -->
                <div class="sm:col-span-2 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 pt-1">
                  <label class="flex items-center gap-2 cursor-pointer select-none">
                    <input
                      v-model="editDrafts[model.id].supportsThinking"
                      type="checkbox"
                      class="rounded bg-card border-subtle text-primary focus:ring-primary w-4 h-4"
                    />
                    <span class="text-xs text-main">支持深度思考 (思维链 Reasoning Content 流式输出)</span>
                  </label>

                  <!-- 保存与反馈区域 -->
                  <div class="flex items-center gap-2 self-end sm:self-auto">
                    <span
                      v-if="editDrafts[model.id].saveMessage"
                      class="text-xs"
                      :class="
                        editDrafts[model.id].saveMessage?.includes('成功')
                          ? 'text-emerald-400'
                          : 'text-red-400'
                      "
                    >
                      {{ editDrafts[model.id].saveMessage }}
                    </span>
                    <button
                      type="button"
                      class="cyber-btn-primary px-3.5 py-1.5 rounded-xl text-xs font-medium flex items-center gap-1.5 shadow-cyber-glow"
                      :disabled="editDrafts[model.id].isSaving"
                      @click="handleSaveModel(model)"
                    >
                      <RefreshCw
                        v-if="editDrafts[model.id].isSaving"
                        class="w-3.5 h-3.5 animate-spin"
                      />
                      <Save v-else class="w-3.5 h-3.5" />
                      <span>保存此模型</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 弹窗底部操作条 -->
      <div
        class="flex items-center justify-between px-6 py-3.5 border-t border-subtle bg-elevated/40 text-xs text-muted shrink-0"
      >
        <span class="flex items-center gap-1.5">
          <HelpCircle class="w-3.5 h-3.5 text-cyber-cyan" />
          <span>修改即时生效，所有用户（普通用户/游客/管理员）均可自由配置与覆盖</span>
        </span>
        <button
          type="button"
          class="px-4 py-1.5 rounded-xl glass-panel text-main hover:bg-elevated transition-colors font-medium"
          @click="configStore.showModelConfigModal = false"
        >
          完成
        </button>
      </div>
    </div>
  </div>
</template>
