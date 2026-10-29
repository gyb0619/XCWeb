<script setup lang="ts">
import { ref } from 'vue'
import {
  Cpu,
  Sun,
  Moon,
  EyeOff,
  ChevronDown,
  Check,
  Zap,
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
    class="relative h-14 px-4 border-b border-subtle bg-card/75 backdrop-blur-md flex items-center justify-between gap-3 z-20 shrink-0"
  >
    <!-- 左侧：二级预置模型下拉选择器 -->
    <div class="relative flex items-center gap-2.5">
      <button
        type="button"
        class="flex items-center gap-2 px-3 py-1.5 rounded-xl glass-panel hover:border-primary/50 transition-all text-left"
        @click="isModelDropdownOpen = !isModelDropdownOpen"
      >
        <Cpu class="w-4 h-4 text-primary-light shrink-0" />
        <span class="text-xs font-semibold text-main truncate max-w-[200px] sm:max-w-[280px]">
          {{ configStore.currentModelInfo.name }}
        </span>
        <ChevronDown
          class="w-3.5 h-3.5 text-muted transition-transform"
          :class="isModelDropdownOpen ? 'rotate-180' : ''"
        />
      </button>

      <!-- 预置模型二级下拉面板 -->
      <div
        v-if="isModelDropdownOpen"
        class="absolute left-0 top-12 w-80 sm:w-96 p-2 rounded-2xl glass-modal shadow-2xl border border-subtle space-y-1 z-50 animate-slide-up"
      >
        <div
          class="px-2.5 py-1.5 flex items-center justify-between text-[11px] text-muted border-b border-subtle"
        >
          <span>预置模型矩阵（统一代理托管）</span>
          <span class="font-mono text-cyber-cyan">{{ configStore.enabledModels.length }} 可用</span>
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
              <span class="text-[10px] px-1.5 py-0.2 rounded bg-elevated text-sub font-mono">
                {{ model.provider }}
              </span>
              <span
                v-if="model.supportsThinking"
                class="text-[10px] px-1.5 py-0.2 rounded bg-cyber-cyan/15 text-cyber-cyan"
              >
                深度思考
              </span>
              <span
                v-if="model.isMock"
                class="text-[10px] px-1.5 py-0.2 rounded bg-amber-500/15 text-amber-400"
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

      <!-- 无痕状态轻量指示胶囊 -->
      <div
        v-if="chatStore.isIncognito"
        class="hidden sm:flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-indigo-500/15 border border-indigo-400/30 text-indigo-300 text-xs"
      >
        <EyeOff class="w-3.5 h-3.5" />
        <span>无痕会话</span>
      </div>
    </div>

    <!-- 右侧：简洁状态与主题切换 -->
    <div class="flex items-center gap-2">
      <!-- 极简配额胶囊 -->
      <div
        class="hidden md:flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-elevated/60 border border-subtle text-xs text-sub"
      >
        <Zap class="w-3.5 h-3.5 text-cyber-cyan" />
        <span class="font-mono text-main font-medium">
          <template v-if="quotaStore.unlimited">无限额</template>
          <template v-else>{{ quotaStore.used }}/{{ quotaStore.limit }} 次</template>
        </span>
      </div>

      <!-- 主题切换按钮 (包含 135° 对角线扫屏动效锚点) -->
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

      <!-- 游客登录快捷按钮 -->
      <button
        v-if="authStore.isGuest"
        type="button"
        class="hidden sm:inline-flex px-3 py-1.5 rounded-xl cyber-btn-primary text-xs font-medium"
        @click="authStore.openAuthOverlay('login')"
      >
        登录
      </button>
    </div>
  </header>
</template>