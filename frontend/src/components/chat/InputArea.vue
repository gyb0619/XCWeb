<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  Send,
  Square,
  Brain,
  Bot,
  RotateCcw,
  Sliders,
  ChevronUp,
} from 'lucide-vue-next'
import { useChatStore } from '@/stores/chatStore'
import { useConfigStore } from '@/stores/configStore'
import { useQuotaStore } from '@/stores/quotaStore'

const chatStore = useChatStore()
const configStore = useConfigStore()
const quotaStore = useQuotaStore()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const isCapabilityPopoverOpen = ref<boolean>(false)
const capabilityContainerRef = ref<HTMLElement | null>(null)

// 估算当前上下文 + 输入框的累计 Token 数量
const estimatedContextTokens = computed<number>(() => {
  const historyText = chatStore.currentMessages.map((m) => m.content).join('\n')
  const combined = [
    configStore.systemPrompt,
    historyText,
    chatStore.inputDraft,
  ]
    .filter(Boolean)
    .join('\n')
  return chatStore.inputDraft.trim() || chatStore.currentMessages.length > 0
    ? quotaStore.estimateTokens(combined)
    : 0
})

const isOverContextLimit = computed<boolean>(() => {
  if (quotaStore.unlimited) return false
  return estimatedContextTokens.value > quotaStore.contextTokenLimit
})

function adjustTextareaHeight() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(180, Math.max(48, el.scrollHeight))}px`
}

watch(
  () => chatStore.inputDraft,
  () => {
    void nextTick(adjustTextareaHeight)
  },
)

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void handleSend()
  }
}

async function handleSend() {
  if (!chatStore.inputDraft.trim() || chatStore.isGenerating) return
  await chatStore.sendMessage(chatStore.inputDraft)
  await nextTick(adjustTextareaHeight)
}

function handleClickOutside(e: MouseEvent) {
  if (
    capabilityContainerRef.value &&
    !capabilityContainerRef.value.contains(e.target as Node)
  ) {
    isCapabilityPopoverOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div class="w-full max-w-4xl mx-auto px-4 pb-4 pt-1 z-10">
    <!-- 无损历史重试状态微横幅 -->
    <div
      v-if="chatStore.retryingMessageId"
      class="mb-2 px-3 py-1.5 rounded-xl bg-primary/15 border border-primary/40 flex items-center justify-between text-xs text-primary-light"
    >
      <div class="flex items-center gap-2">
        <RotateCcw class="w-3.5 h-3.5 animate-spin" />
        <span>正在无损重试：后续对话将保留，新回答作为新版本分支生成</span>
      </div>
      <button
        type="button"
        class="text-muted hover:text-main"
        @click="chatStore.clearLosslessRetryState()"
      >
        取消
      </button>
    </div>

    <!-- 主输入容器：带 .chat-input-target 锚点供阶段 2 无损历史重试克隆气泡飞入定位 -->
    <div
      class="chat-input-target relative rounded-2xl glass-panel p-3 transition-all focus-within:border-primary/60 focus-within:shadow-cyber-glow"
    >
      <textarea
        ref="textareaRef"
        v-model="chatStore.inputDraft"
        rows="2"
        placeholder="输入任何问题，Shift + Enter 换行，Enter 发送..."
        class="w-full bg-transparent text-sm text-main placeholder-muted resize-none focus:outline-none leading-relaxed max-h-44"
        @keydown="handleKeydown"
      />

      <!-- 底部精简工具栏 -->
      <div class="flex items-center justify-between gap-2 pt-2 border-t border-subtle/60">
        <!-- 左侧：二级模式配置浮层入口 + 快速状态微胶囊 -->
        <div ref="capabilityContainerRef" class="relative flex items-center gap-1.5">
          <!-- 二级配置唤起按钮 -->
          <button
            type="button"
            class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-xl text-xs transition-all border"
            :class="
              isCapabilityPopoverOpen
                ? 'bg-primary/20 text-primary-light border-primary/50'
                : 'bg-elevated/70 text-sub border-subtle hover:text-main hover:border-primary/40'
            "
            title="展开二级模式配置"
            @click="isCapabilityPopoverOpen = !isCapabilityPopoverOpen"
          >
            <Sliders class="w-3.5 h-3.5 text-primary-light" />
            <span>模式配置</span>
            <ChevronUp
              class="w-3 h-3 text-muted transition-transform"
              :class="isCapabilityPopoverOpen ? 'rotate-180' : ''"
            />
          </button>

          <!-- 快速状态微指示标签 (可直接点击微调) -->
          <button
            type="button"
            class="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-[11px] border transition-all"
            :class="
              configStore.enableThinking
                ? 'bg-cyber-cyan/15 text-cyber-cyan border-cyber-cyan/35'
                : 'bg-elevated/40 text-muted border-transparent hover:text-sub'
            "
            :title="`深度思考: ${configStore.enableThinking ? '开启' : '关闭'}`"
            @click="configStore.updateSettings({ enableThinking: !configStore.enableThinking })"
          >
            <Brain class="w-3 h-3" />
            <span>思考</span>
          </button>

          <button
            type="button"
            class="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-[11px] border transition-all"
            :class="
              configStore.enableAgent
                ? 'bg-primary/20 text-primary-light border-primary/40'
                : 'bg-elevated/40 text-muted border-transparent hover:text-sub'
            "
            :title="`Agent 规划: ${configStore.enableAgent ? '开启' : '关闭'}`"
            @click="configStore.updateSettings({ enableAgent: !configStore.enableAgent })"
          >
            <Bot class="w-3 h-3" />
            <span>Agent</span>
          </button>

          <!-- 二级模式配置浮层面板 (Capability Popover) -->
          <div
            v-if="isCapabilityPopoverOpen"
            class="absolute left-0 bottom-10 w-72 p-3 rounded-2xl glass-modal shadow-2xl border border-subtle space-y-3 z-50 text-xs animate-slide-up"
          >
            <div class="flex items-center justify-between text-xs font-semibold text-main pb-1 border-b border-subtle">
              <span>模型推理与协同能力</span>
              <span class="text-[10px] text-muted font-normal">二级配置项</span>
            </div>

            <!-- 深度思考开关 -->
            <div
              class="flex items-center justify-between p-2 rounded-xl bg-elevated/50 border border-subtle cursor-pointer hover:border-primary/40 transition-colors"
              @click="configStore.updateSettings({ enableThinking: !configStore.enableThinking })"
            >
              <div class="space-y-0.5 min-w-0 pr-2">
                <div class="flex items-center gap-1.5 font-medium text-main">
                  <Brain class="w-3.5 h-3.5 text-cyber-cyan" />
                  <span>深度思考思维链</span>
                </div>
                <p class="text-[10px] text-muted leading-tight">
                  呈现模型反思推理过程与跳动计时器
                </p>
              </div>
              <div
                class="w-9 h-5 rounded-full transition-colors relative flex items-center p-0.5 shrink-0"
                :class="configStore.enableThinking ? 'bg-cyber-cyan' : 'bg-elevated border border-subtle'"
              >
                <div
                  class="w-4 h-4 rounded-full bg-white transition-transform"
                  :class="configStore.enableThinking ? 'translate-x-4' : 'translate-x-0'"
                />
              </div>
            </div>

            <!-- Agent 规划开关 -->
            <div
              class="flex items-center justify-between p-2 rounded-xl bg-elevated/50 border border-subtle cursor-pointer hover:border-primary/40 transition-colors"
              @click="configStore.updateSettings({ enableAgent: !configStore.enableAgent })"
            >
              <div class="space-y-0.5 min-w-0 pr-2">
                <div class="flex items-center gap-1.5 font-medium text-main">
                  <Bot class="w-3.5 h-3.5 text-primary-light" />
                  <span>Agent 规划与工具链</span>
                </div>
                <p class="text-[10px] text-muted leading-tight">
                  多阶段决策（规划、工具调用、数据总结）
                </p>
              </div>
              <div
                class="w-9 h-5 rounded-full transition-colors relative flex items-center p-0.5 shrink-0"
                :class="configStore.enableAgent ? 'bg-primary' : 'bg-elevated border border-subtle'"
              >
                <div
                  class="w-4 h-4 rounded-full bg-white transition-transform"
                  :class="configStore.enableAgent ? 'translate-x-4' : 'translate-x-0'"
                />
              </div>
            </div>

            <!-- 上下文 Token 详情 -->
            <div class="p-2 rounded-xl bg-elevated/30 border border-subtle text-[11px] space-y-1">
              <div class="flex items-center justify-between text-muted">
                <span>上下文占用估算</span>
                <span class="font-mono text-main">
                  {{ estimatedContextTokens.toLocaleString() }}
                  /
                  {{ quotaStore.unlimited ? '∞' : `${quotaStore.contextTokenLimit / 1000}K` }}
                </span>
              </div>
              <div
                v-if="!quotaStore.unlimited"
                class="w-full h-1 rounded-full bg-elevated overflow-hidden"
              >
                <div
                  class="h-full rounded-full transition-all"
                  :class="isOverContextLimit ? 'bg-red-500' : 'bg-primary-light'"
                  :style="{
                    width: `${Math.min(100, (estimatedContextTokens / quotaStore.contextTokenLimit) * 100)}%`,
                  }"
                />
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧：简洁 Token 计数 + 发送/停止按钮 -->
        <div class="flex items-center gap-2.5 ml-auto">
          <span
            class="text-[11px] font-mono text-muted"
            :class="isOverContextLimit ? 'text-red-400 font-semibold' : ''"
          >
            {{ estimatedContextTokens.toLocaleString() }} Tokens
          </span>

          <button
            v-if="chatStore.isGenerating"
            type="button"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-red-500/20 border border-red-500/40 text-red-300 hover:bg-red-500/30 text-xs font-medium transition-all"
            @click="chatStore.stopGeneration()"
          >
            <Square class="w-3.5 h-3.5 fill-current" />
            <span>停止</span>
          </button>

          <button
            v-else
            type="button"
            :disabled="!chatStore.inputDraft.trim()"
            class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl cyber-btn-primary text-xs font-medium"
            @click="handleSend"
          >
            <Send class="w-3.5 h-3.5" />
            <span>发送</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>