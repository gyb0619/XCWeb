<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  Send,
  Square,
  Brain,
  Bot,
  Paperclip,
  FileText,
  X,
  RotateCcw,
} from 'lucide-vue-next'
import { useChatStore } from '@/stores/chatStore'
import { useConfigStore } from '@/stores/configStore'
import { useQuotaStore } from '@/stores/quotaStore'

const chatStore = useChatStore()
const configStore = useConfigStore()
const quotaStore = useQuotaStore()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

// 估算当前上下文 + 输入框的累计 Token 数量
const estimatedContextTokens = computed<number>(() => {
  const historyText = chatStore.currentMessages.map((m) => m.content).join('\n')
  const ragText = chatStore.activeRagChunks
    .slice(0, 3)
    .map((c) => c.content)
    .join('\n')
  const combined = [
    configStore.systemPrompt,
    historyText,
    ragText,
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

async function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || files.length === 0) return
  for (let i = 0; i < files.length; i++) {
    await chatStore.addRagDocument(files[i])
  }
  input.value = ''
}
</script>

<template>
  <div class="w-full max-w-4xl mx-auto px-4 pb-4 pt-2 z-10">
    <!-- 无损历史重试状态横幅 (PRD 4.3) -->
    <div
      v-if="chatStore.retryingMessageId"
      class="mb-2 px-3.5 py-2 rounded-xl bg-primary/15 border border-primary/40 flex items-center justify-between text-xs text-primary-light"
    >
      <div class="flex items-center gap-2">
        <RotateCcw class="w-3.5 h-3.5 animate-spin" />
        <span>
          正在对历史提问执行<strong>无损重试</strong>：后续对话将完整保留，新回答将作为新版本分支追加
        </span>
      </div>
      <button
        type="button"
        class="text-muted hover:text-main"
        @click="chatStore.clearLosslessRetryState()"
      >
        取消重试
      </button>
    </div>

    <!-- 已挂载的端侧 RAG 本地文档胶囊列表 -->
    <div
      v-if="chatStore.ragDocuments.length > 0"
      class="flex flex-wrap items-center gap-1.5 mb-2"
    >
      <div
        v-for="doc in chatStore.ragDocuments"
        :key="doc.id"
        class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-cyber-cyan/10 border border-cyber-cyan/30 text-xs text-cyber-cyan"
      >
        <FileText class="w-3.5 h-3.5 shrink-0" />
        <span class="max-w-[160px] truncate">{{ doc.name }}</span>
        <span class="text-[10px] opacity-80">({{ doc.chunksCount }} 切片)</span>
        <button
          type="button"
          class="hover:text-white transition-colors"
          @click="chatStore.removeRagDocument(doc.id)"
        >
          <X class="w-3 h-3" />
        </button>
      </div>
    </div>

    <!-- 主输入容器：带 .chat-input-target 锚点供阶段 2 无损历史重试克隆气泡飞入定位 -->
    <div
      class="chat-input-target relative rounded-2xl glass-panel p-3 transition-all focus-within:border-primary/60 focus-within:shadow-cyber-glow"
    >
      <textarea
        ref="textareaRef"
        v-model="chatStore.inputDraft"
        rows="2"
        placeholder="输入任何问题，支持 Markdown、代码分析、Agent 任务拆解与端侧文档检索... (Enter 发送，Shift + Enter 换行)"
        class="w-full bg-transparent text-sm text-main placeholder-muted resize-none focus:outline-none leading-relaxed max-h-44"
        @keydown="handleKeydown"
      />

      <!-- 底部功能胶囊与 Token 实时估算栏 -->
      <div class="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-subtle/60">
        <!-- 左侧开关胶囊：深度思考、Agent 工具链、挂载本地 RAG 文档 -->
        <div class="flex items-center flex-wrap gap-1.5">
          <button
            type="button"
            class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs border transition-all"
            :class="
              configStore.enableThinking
                ? 'bg-cyber-cyan/15 text-cyber-cyan border-cyber-cyan/40 shadow-cyber-cyan'
                : 'bg-elevated/60 text-muted border-subtle hover:text-main'
            "
            @click="configStore.updateSettings({ enableThinking: !configStore.enableThinking })"
          >
            <Brain class="w-3.5 h-3.5" />
            <span>深度思考</span>
          </button>

          <button
            type="button"
            class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs border transition-all"
            :class="
              configStore.enableAgent
                ? 'bg-primary/20 text-primary-light border-primary/50 shadow-cyber-glow'
                : 'bg-elevated/60 text-muted border-subtle hover:text-main'
            "
            @click="configStore.updateSettings({ enableAgent: !configStore.enableAgent })"
          >
            <Bot class="w-3.5 h-3.5" />
            <span>Agent 规划</span>
          </button>

          <button
            type="button"
            class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs bg-elevated/60 text-sub border border-subtle hover:text-main hover:border-cyber-cyan/40 transition-all"
            title="挂载本地知识文档（仅在浏览器端侧分块检索）"
            @click="fileInputRef?.click()"
          >
            <Paperclip class="w-3.5 h-3.5 text-cyber-cyan" />
            <span>挂载本地文档</span>
          </button>
          <input
            ref="fileInputRef"
            type="file"
            accept=".txt,.md,.json,.csv,.ts,.js,.py,.java,.sql"
            multiple
            class="hidden"
            @change="handleFileSelect"
          />
        </div>

        <!-- 右侧：实时上下文 Token 估算 + 发送/停止按钮 -->
        <div class="flex items-center gap-3 ml-auto">
          <span
            class="text-xs font-mono transition-colors"
            :class="
              isOverContextLimit
                ? 'text-red-400 font-semibold'
                : estimatedContextTokens > quotaStore.contextTokenLimit * 0.8
                  ? 'text-amber-400'
                  : 'text-muted'
            "
          >
            <template v-if="quotaStore.unlimited">
              {{ estimatedContextTokens.toLocaleString() }} / ∞ Tokens
            </template>
            <template v-else>
              {{ estimatedContextTokens.toLocaleString() }} /
              {{ quotaStore.contextTokenLimit.toLocaleString() }} Tokens
            </template>
          </span>

          <button
            v-if="chatStore.isGenerating"
            type="button"
            class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl bg-red-500/20 border border-red-500/40 text-red-300 hover:bg-red-500/30 text-xs font-medium transition-all"
            @click="chatStore.stopGeneration()"
          >
            <Square class="w-3.5 h-3.5 fill-current" />
            <span>停止生成</span>
          </button>

          <button
            v-else
            type="button"
            :disabled="!chatStore.inputDraft.trim()"
            class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl cyber-btn-primary text-xs font-medium"
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
