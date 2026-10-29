<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import {
  Sparkles,
  RotateCcw,
  Edit3,
  Check,
  X,
} from 'lucide-vue-next'
import VersionSwitcher from '@/components/chat/VersionSwitcher.vue'
import ThinkingBlock from '@/components/chat/ThinkingBlock.vue'
import AgentTimeline from '@/components/chat/AgentTimeline.vue'
import GenerativeUIRenderer from '@/components/chat/GenerativeUIRenderer.vue'
import { useLosslessRetry } from '@/composables/useLosslessRetry'
import { useInlineEdit } from '@/composables/useInlineEdit'
import { useChatStore } from '@/stores/chatStore'
import { useConfigStore } from '@/stores/configStore'
import type { Message } from '@/types/api'

const chatStore = useChatStore()
const configStore = useConfigStore()
const { isFlying, handleLosslessRetry } = useLosslessRetry()
const {
  editingMessageId,
  editingDraft,
  handleInlineEdit,
  cancelInlineEdit,
  saveInlineEdit,
} = useInlineEdit()

const scrollContainerRef = ref<HTMLElement | null>(null)
// 记录每条消息在深度思考完成时的滑升动画重播 key (PRD 4.4: 600ms slideUp)
const answerSlideAnimKey = ref<Record<string, number>>({})

const quickSuggestions = [
  '请分析 2026 年 AI Native Web 架构趋势并给出对比表格',
  '对比 DeepSeek-R1 蒸馏模型与传统指令模型的推理机制差异',
  '演示四级角色配额防刷体系（游客三重校验 + 自然日重置）设计方案',
]

function renderMarkdown(raw: string): string {
  if (!raw) return ''
  const parsed = marked.parse(raw, { async: false })
  return typeof parsed === 'string' ? parsed : ''
}

function handleThinkingComplete(messageId: string) {
  // 思考结束时触发正文重播 600ms 自下而上滑升动效 (PRD 4.4)
  answerSlideAnimKey.value[messageId] = Date.now()
}

function onStartInlineEdit(msg: Message) {
  const el = document.querySelector(`[data-message-id="${msg.id}"] .message-user-bubble`)
  void handleInlineEdit(msg, el as HTMLElement | null)
}

function onCancelInlineEdit(msg: Message) {
  const el = document.querySelector(`[data-message-id="${msg.id}"] .message-user-bubble`)
  void cancelInlineEdit(msg, el as HTMLElement | null)
}

function onSaveInlineEdit(msg: Message) {
  const el = document.querySelector(`[data-message-id="${msg.id}"] .message-user-bubble`)
  void saveInlineEdit(msg, el as HTMLElement | null)
}

function onLosslessRetryClick(msg: Message) {
  const bubbleEl = document.querySelector(
    `[data-message-id="${msg.id}"] .message-user-bubble`,
  ) as HTMLElement | null

  void handleLosslessRetry(msg.id, bubbleEl)
}

watch(
  () => chatStore.currentMessages.length,
  () => {
    void nextTick(() => {
      if (scrollContainerRef.value) {
        scrollContainerRef.value.scrollTop = scrollContainerRef.value.scrollHeight
      }
    })
  },
)
</script>

<template>
  <main
    ref="scrollContainerRef"
    class="flex-1 overflow-y-auto px-4 py-6 scroll-smooth"
  >
    <div class="max-w-4xl mx-auto space-y-6">
      <!-- 空状态欢迎面板 (简洁精致) -->
      <div
        v-if="chatStore.currentMessages.length === 0"
        class="py-16 flex flex-col items-center text-center space-y-6 animate-slide-up"
      >
        <div
          class="w-14 h-14 rounded-2xl cyber-btn-primary flex items-center justify-center shadow-cyber-glow"
        >
          <Sparkles class="w-7 h-7 text-white" />
        </div>

        <div class="space-y-2 max-w-lg">
          <h1 class="text-2xl font-bold text-main tracking-tight">
            智启未来 · XCWeb
          </h1>
          <p class="text-xs text-sub leading-relaxed">
            当前挂载：
            <span class="font-mono text-primary-light font-semibold">
              {{ configStore.currentModelInfo.name }}
            </span>
            · 支持深度思考思维链、Agent 决策时序与无损多版本分支
          </p>
        </div>

        <!-- 快速灵感建议卡片 -->
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3 w-full max-w-2xl pt-2">
          <button
            v-for="(item, index) in quickSuggestions"
            :key="index"
            type="button"
            class="p-3.5 rounded-2xl glass-panel text-left text-xs text-sub hover:text-main hover:border-primary/50 transition-all leading-relaxed"
            @click="chatStore.sendMessage(item)"
          >
            {{ item }}
          </button>
        </div>
      </div>

      <!-- 消息列表流 -->
      <div
        v-for="msg in chatStore.currentMessages"
        :key="msg.id"
        :data-message-id="msg.id"
        class="group relative"
      >
        <!-- 用户提问气泡 -->
        <div v-if="msg.role === 'user'" class="flex flex-col items-end space-y-1.5">
          <div
            class="message-user-bubble max-w-2xl rounded-2xl px-4 py-3 text-sm leading-relaxed transition-all duration-500"
            :class="[
              msg.isRetrying
                ? '-translate-x-[150px] opacity-45 border border-primary/50 bg-primary/10 shadow-cyber-glow'
                : 'cyber-btn-primary',
              editingMessageId === msg.id
                ? 'w-full max-w-none bg-card border border-primary/50 shadow-cyber-glow'
                : '',
            ]"
          >
            <!-- 动效 4：自适应内联编辑态 (PRD 4.3) -->
            <div v-if="editingMessageId === msg.id" class="space-y-2.5">
              <textarea
                v-model="editingDraft"
                rows="3"
                class="w-full rounded-xl bg-elevated/80 border border-subtle p-2.5 text-xs text-main focus:outline-none focus:border-primary/60 resize-none"
                placeholder="修改历史提问内容..."
                @keydown.esc.stop="onCancelInlineEdit(msg)"
              />
              <div class="flex items-center justify-end gap-2">
                <button
                  type="button"
                  class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-elevated text-sub hover:text-main text-xs transition-colors"
                  @click="onCancelInlineEdit(msg)"
                >
                  <X class="w-3.5 h-3.5" />
                  <span>取消</span>
                </button>
                <button
                  type="button"
                  class="inline-flex items-center gap-1 px-3 py-1 rounded-lg cyber-btn-primary text-xs font-medium"
                  @click="onSaveInlineEdit(msg)"
                >
                  <Check class="w-3.5 h-3.5" />
                  <span>保存并生成新分支</span>
                </button>
              </div>
            </div>

            <div v-else class="whitespace-pre-wrap break-words">
              {{ msg.content }}
            </div>
          </div>

          <!-- 用户消息操作栏：自适应内联编辑 & 无损历史重试与飞移 -->
          <div
            v-if="editingMessageId !== msg.id"
            class="flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity text-[11px] text-muted"
          >
            <button
              type="button"
              data-motion-anchor="inline-edit-trigger"
              class="inline-flex items-center gap-1 px-2 py-0.5 rounded hover:bg-elevated hover:text-main transition-colors"
              @click="onStartInlineEdit(msg)"
            >
              <Edit3 class="w-3 h-3" />
              <span>编辑</span>
            </button>

            <button
              type="button"
              data-motion-anchor="lossless-retry-trigger"
              :disabled="chatStore.isGenerating || isFlying"
              class="inline-flex items-center gap-1 px-2 py-0.5 rounded hover:bg-elevated hover:text-primary-light disabled:opacity-40 transition-colors"
              @click="onLosslessRetryClick(msg)"
            >
              <RotateCcw class="w-3 h-3" />
              <span>无损重试</span>
            </button>
          </div>
        </div>

        <!-- AI 回答卡片 -->
        <div v-else class="flex items-start gap-3">
          <div
            class="w-8 h-8 rounded-xl bg-primary/15 border border-primary/35 flex items-center justify-center text-primary-light shrink-0 mt-0.5"
          >
            <Sparkles class="w-4 h-4" />
          </div>

          <div class="flex-1 min-w-0 space-y-3">
            <!-- 元信息栏：模型标识 + Mock 降级可感知标签 (PRD 3.2) -->
            <div class="flex items-center flex-wrap gap-2 text-[11px]">
              <span class="font-semibold text-main font-mono">
                {{ msg.model || configStore.selectedModel }}
              </span>
              <span
                v-if="msg.isMock"
                class="px-2 py-0.5 rounded-md bg-amber-500/15 text-amber-400 border border-amber-500/30 font-mono"
              >
                Mock 降级响应
              </span>
              <span v-if="msg.usage" class="text-muted font-mono">
                · {{ msg.usage.totalTokens }} Tokens
              </span>
            </div>

            <!-- 动效 3：视差版本翻页容器与底部持久控制栏 (PRD 4.3) -->
            <VersionSwitcher :message="msg">
              <div class="space-y-3">
                <!-- 1. Agent 可视化思考时间轴组件 (PRD 6.1) -->
                <AgentTimeline
                  v-if="msg.agentSteps && msg.agentSteps.length > 0"
                  :steps="msg.agentSteps"
                />

                <!-- 2. 深度思考独立抽屉组件 (PRD 4.4) -->
                <ThinkingBlock
                  v-if="msg.thinkingContent !== null && msg.thinkingContent !== undefined"
                  :message-id="msg.id"
                  :thinking-content="msg.thinkingContent"
                  :is-thinking="Boolean(msg.isThinking)"
                  :duration-ms="msg.thinkingDurationMs"
                  @thinking-complete="handleThinkingComplete"
                />

                <!-- 3. 正文区域：思考结束后触发 600ms slideUp (PRD 4.4) -->
                <div
                  v-if="msg.content"
                  :key="answerSlideAnimKey[msg.id] || msg.id"
                  class="glass-panel rounded-2xl p-4 text-sm text-main leading-relaxed answer-slide-up space-y-2 prose prose-invert max-w-none"
                  v-html="renderMarkdown(msg.content)"
                />

                <!-- 4. 生成式交互 UI 卡片：当回答包含 Markdown 对比表格时动态渲染筛选表与科技蓝 ECharts 图表 (PRD 6.3) -->
                <GenerativeUIRenderer
                  v-if="msg.content"
                  :content="msg.content"
                  :message-id="msg.id"
                  :hide-markdown="true"
                />
              </div>
            </VersionSwitcher>
          </div>
        </div>
      </div>
    </div>
  </main>
</template>