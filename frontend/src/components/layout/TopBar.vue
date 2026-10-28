<script setup lang="ts">
import { ref } from 'vue'
import {
  Cpu,
  Sun,
  Moon,
  EyeOff,
  Brain,
  ChevronDown,
  Zap,
  Check,
  Sparkles,
} from 'lucide-vue-next'
import { useConfigStore } from '@/stores/configStore'
import { useChatStore } from '@/stores/chatStore'
import { useQuotaStore } from '@/stores/quotaStore'
import { useAuthStore } from '@/stores/authStore'

const configStore = useConfigStore()
const chatStore = useChatStore()
const quotaStore = useQuotaStore()
const authStore = useAuthStore()

const isModelDropdownOpen = ref<boolean>(false)
const themeSweepBtnRef = ref<HTMLElement | null>(null)

function handleSelectModel(modelId: string) {
  configStore.selectModel(modelId)
  isModelDropdownOpen.value = false
}

function handleToggleTheme() {
  configStore.toggleLightDarkTheme(themeSweepBtnRef.value)
}
</script>

<template>
  <header
    class="relative h-14 px-4 border-b border-subtle bg-card/75 backdrop-blur-md flex items-center justify-between gap-3 z-20"
  >
    <!-- 左侧：预置模型选择器（仅渲染 /api/models 返回的预置模型，严禁自定义接口或 Key） -->
    <div class="relative flex items-center gap-2.5">
      <button
        type="button"
        class="flex items-center gap-2.5 px-3 py-1.5 rounded-xl glass-panel hover:border-primary/50 transition-all text-left"
        @click="isModelDropdownOpen = !isModelDropdownOpen"
      >
        <Cpu class="w-4 h-4 text-primary-light shrink-0" />
        <div class="flex items-center gap-2">
          <span class="text-xs font-semibold text-main">
            {{ configStore.currentModelInfo.name }}
          </span>
          <span
            class="text-[10px] px-1.5 py-0.5 rounded bg-elevated text-sub font-mono"
          >
            {{ configStore.currentModelInfo.provider }}
          </span>
          <span
            v-if="configStore.currentModelInfo.supportsThinking"
            class="hidden sm:inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded bg-cyber-cyan/15 text-cyber-cyan border border-cyber-cyan/30"
          >
            <Brain class="w-3 h-3" />
            深度思考
          </span>
          <span
            v-if="configStore.currentModelInfo.isMock || configStore.allExternalKeysMissing"
            class="hidden md:inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-400 border border-amber-500/30"
          >
            <Sparkles class="w-3 h-3" />
            Mock 兜底就绪
          </span>
        </div>
        <ChevronDown
          class="w-3.5 h-3.5 text-muted transition-transform"
          :class="isModelDropdownOpen ? 'rotate-180' : ''"
        />
      </button>

      <!-- 预置模型下拉面板 -->
      <div
        v-if="isModelDropdownOpen"
        class="absolute left-0 top-12 w-80 sm:w-96 p-2 rounded-2xl glass-modal space-y-1 z-50"
      >
        <div
          class="px-2.5 py-1.5 flex items-center justify-between text-[11px] text-muted border-b border-subtle"
        >
          <span>服务端预置模型矩阵（统一托管鉴权）</span>
          <span class="font-mono">{{ configStore.enabledModels.length }} 可用</span>
        </div>

        <button
          v-for="model in configStore.enabledModels"
          :key="model.id"
          type="button"
          class="w-full flex items-start justify-between gap-2 p-2.5 rounded-xl text-left transition-all"
          :class="
            configStore.selectedModel === model.id
              ? 'bg-primary/15 border border-primary/40'
              : 'hover:bg-elevated/70 border border-transparent'
          "
          @click="handleSelectModel(model.id)"
        >
          <div class="space-y-1 min-w-0">
            <div class="flex items-center gap-1.5 flex-wrap">
              <span class="text-xs font-semibold text-main">{{ model.name }}</span>
              <span class="text-[10px] px-1.5 py-0.5 rounded bg-elevated text-sub font-mono">
                {{ model.provider }}
              </span>
              <span
                v-if="model.supportsThinking"
                class="text-[10px] px-1.5 py-0.5 rounded bg-cyber-cyan/15 text-cyber-cyan"
              >
                深度思考
              </span>
              <span
                v-if="model.isMock"
                class="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-400"
              >
                内置 Mock
              </span>
            </div>
            <p class="text-[11px] text-muted leading-snug">{{ model.description }}</p>
          </div>
          <Check
            v-if="configStore.selectedModel === model.id"
            class="w-4 h-4 text-primary-light shrink-0 mt-1"
          />
        </button>
      </div>

      <!-- 无痕状态指示器 -->
      <div
        v-if="chatStore.isIncognito"
        class="hidden md:flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-indigo-500/20 border border-indigo-400/40 text-indigo-300 text-xs"
      >
        <EyeOff class="w-3.5 h-3.5" />
        <span>无痕会话中 · 不写入本地与云端</span>
      </div>
    </div>

    <!-- 右侧：当前配额与 Token 状态胶囊 + 主题对角线扫屏切换按钮 -->
    <div class="flex items-center gap-2.5">
      <!-- 配额与 Token 状态胶囊 -->
      <div
        class="hidden sm:flex items-center gap-2 px-3 py-1 rounded-full bg-elevated/70 border border-subtle text-xs"
      >
        <Zap class="w-3.5 h-3.5 text-cyber-cyan" />
        <span class="text-sub">
          配额:
          <strong class="font-mono text-main">
            {{
              quotaStore.unlimited
                ? '∞'
                : `${quotaStore.used}/${quotaStore.limit}`
            }}
          </strong>
        </span>
        <span class="text-muted">|</span>
        <span class="text-sub">
          今日 Token:
          <strong class="font-mono text-main">
            {{ quotaStore.tokensUsedToday.toLocaleString() }}
          </strong>
        </span>
      </div>

      <!-- 主题切换按钮（预留给阶段 2 @Motion-FX-Engineer 的对角线扫屏动效触发锚点） -->
      <button
        ref="themeSweepBtnRef"
        type="button"
        id="theme-sweep-trigger"
        data-motion-anchor="theme-sweep-trigger"
        class="p-2 rounded-xl glass-panel text-sub hover:text-main hover:border-primary/50 transition-all"
        :title="`当前主题: ${configStore.theme}（点击触发对角线扫屏切换）`"
        @click="handleToggleTheme"
      >
        <Sun v-if="configStore.theme === 'light'" class="w-4 h-4 text-amber-500" />
        <EyeOff
          v-else-if="configStore.theme === 'incognito'"
          class="w-4 h-4 text-indigo-400"
        />
        <Moon v-else class="w-4 h-4 text-cyber-cyan" />
      </button>

      <!-- 游客快速登录入口按钮 -->
      <button
        v-if="authStore.isGuest"
        type="button"
        class="px-3 py-1.5 rounded-xl cyber-btn-primary text-xs font-medium"
        @click="authStore.openAuthOverlay('login')"
      >
        登录领 50 次额度
      </button>
    </div>
  </header>
</template>
