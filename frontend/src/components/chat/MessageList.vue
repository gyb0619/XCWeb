<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import {
  Sparkles,
  RotateCcw,
  FileText,
  Edit3,
  Check,
  X,
  BookOpen,
} from 'lucide-vue-next'
import VersionSwitcher from '@/components/chat/VersionSwitcher.vue'
import ThinkingBlock from '@/components/chat/ThinkingBlock.vue'
import AgentTimeline from '@/components/chat/AgentTimeline.vue'
import GenerativeUIRenderer from '@/components/chat/GenerativeUIRenderer.vue'
import { useLosslessRetry } from '@/composables/useLosslessRetry'
import { useInlineEdit } from '@/composables/useInlineEdit'
import { useClientRAG } from '@/composables/useClientRAG'
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
const {
  hasBuiltinWhitepaper,
  loadSampleWhitepaper,
  openCitationPreview,
  openCitationByIndex,
  openKnowledgeBaseModal,
} = useClientRAG()

const scrollContainerRef = ref<HTMLElement | null>(null)
// 记录每条消息在深度思考完成时的滑升动画重播 key (PRD 4.4: 600ms slideUp)
const answerSlideAnimKey = ref<Record<string, number>>({})

const quickSuggestions = [
  '请分析 2026 年 AI Native Web 架构趋势并给出对比表格',
  '对比 DeepSeek-R1 蒸馏模型与传统指令模型的推理机制差异',
  '演示四级角色配额防刷体系（游客三重校验 + 自然日重置）设计方案',
]

/**
 * 渲染 Markdown 并将正文中的【引用来源 [1]】/【引用来源 [1] [2]】转换为可点击的高亮按钮
 */
function renderMarkdownWithCitations(raw: string): string {
  if (!raw) return ''
  const parsed = marked.parse(raw, { async: false })
  const html = typeof parsed === 'string' ? parsed : ''

  // 将【引用来源 [1]】或【引用来源 [1] [2]】替换为带 data-rag-cite-index 的可点击徽章
  return html.replace(
    /【引用来源\s*((?:\[\d+\]\s*)+)】/g,
    (_fullMatch, groupStr: string) => {
      const indices = Array.from(groupStr.matchAll(/\[(\d+)\]/g)).map((m) =>
        Number(m[1]),
      )
      if (indices.length === 0) return _fullMatch
      const buttons = indices
        .map(
          (idx) =>
            `<button type="button" data-rag-cite-index="${idx}" class="rag-inline-cite-btn inline-flex items-center gap-0.5 mx-0.5 px-1.5 py-0.5 rounded-md bg-cyber-cyan/15 hover:bg-cyber-cyan/30 text-cyber-cyan border border-cyber-cyan/40 font-mono text-[11px] leading-none transition-all cursor-pointer align-baseline">【引用来源 [${idx}]】</button>`,
        )
        .join('')
      return buttons
    },
  )
}

function handleMarkdownContainerClick(event: MouseEvent, msg: Message) {
  const target = event.target as HTMLElement | null
  if (!target) return
  const citeBtn = target.closest<HTMLElement>('[data-rag-cite-index]')
  if (!citeBtn) return

  const rawIdx = citeBtn.getAttribute('data-rag-cite-index')
  const citeIndex = rawIdx ? Number.parseInt(rawIdx, 10) : 1
  if (!Number.isNaN(citeIndex)) {
    openCitationByIndex(msg, citeIndex)
  }
}

function handleThinkingComplete(messageId: string) {
  answerSlideAnimKey.value[messageId] = Date.now()
}

function handleMountSampleWhitepaper() {
  loadSampleWhitepaper()
  openKnowledgeBaseModal()
}

async function onStartInlineEdit(msg: Message) {
  await handleInlineEdit(msg)
}

async function onCancelInlineEdit(msg: Message) {
  await cancelInlineEdit(msg)
}

async function onSaveInlineEdit(msg: Message) {
  await saveInlineEdit(msg)
}

async function onLosslessRetryClick(msg: Message) {
  await handleLosslessRetry(msg.id)
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
      <!-- 空状态欢迎面板 -->
      <div
        v-if="chatStore.currentMessages.length === 0"
        class="py-12 flex flex-col items-center text-center space-y-6 animate-slide-up"
      >
        <div
          class="w-14 h-14 rounded-2xl cyber-btn-primary flex items-center justify-center shadow-cyber-glow"
        >
          <Sparkles class="w-7 h-7 text-white" />
        </div>

        <div class="space-y-2 max-w-xl">
          <h1 class="text-2xl font-bold text-main tracking-tight">
            XCWeb · 智启未来 AI 对话与智能体平台
          </h1>
          <p class="text-xs text-sub leading-relaxed">
            当前挂载模型：
            <span class="font-mono text-primary-light font-semibold">
              {{ configStore.currentModelInfo.name }}
            </span>
            · 支持深度思考思维链抽屉、Agent 决策时间轴、端侧轻量 RAG 与无损多版本分支重试
          </p>
        </div>

        <!-- 端侧轻量 RAG 快捷体验入口 -->
        <div class="flex flex-wrap items-center justify-center gap-2">
          <button
            v-if="!hasBuiltinWhitepaper"
            type="button"
            class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-xs bg-cyber-cyan/15 hover:bg-cyber-cyan/25 text-cyber-cyan border border-cyber-cyan/40 transition-all shadow-cyber-cyan"
            @click="handleMountSampleWhitepaper"
          >
            <BookOpen class="w-3.5 h-3.5" />
            <span>一键挂载内置《XCWeb 2026 技术白皮书》（体验端侧 RAG 检索与原文高亮）</span>
          </button>
          <button
            v-else
            type="button"
            class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-xs bg-emerald-500/15 hover:bg-emerald-500/25 text-emerald-400 border border-emerald-500/40 transition-all"
            @click="openKnowledgeBaseModal"
          >
            <BookOpen class="w-3.5 h-3.5" />
            <span>
              已挂载端侧 RAG 知识库 ({{ chatStore.ragDocuments.length }} 篇 ·
              {{ chatStore.activeRagChunks.length }} 语义分块) · 点击预览
            </span>
          </button>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3 w-full max-w-3xl pt-1">
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

      <!-- 消息列表流（集成四项核心微动效锚点 + Agent 时间轴 + 深度思考抽屉 + 端侧 RAG 引用高亮） -->
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
              msg.isRetrying && editingMessageId !== msg.id
                ? 'is-retrying-shifted -translate-x-[150px] opacity-45 border border-primary/50 bg-primary/10'
                : editingMessageId === msg.id
                  ? 'bg-card border border-primary/60 shadow-cyber-glow'
                  : 'cyber-btn-primary',
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
              <span>编辑提问</span>
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
                Mock 降级响应 (mock = true)
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

                <!-- 3. 正文区域：思考结束后触发 600ms slideUp，且内联【引用来源 [1]】可点击打开原文高亮 (PRD 4.4 & 6.2) -->
                <div
                  v-if="msg.content"
                  :key="answerSlideAnimKey[msg.id] || msg.id"
                  class="glass-panel rounded-2xl p-4 text-sm text-main leading-relaxed answer-slide-up space-y-2 prose prose-invert max-w-none"
                  @click="handleMarkdownContainerClick($event, msg)"
                  v-html="renderMarkdownWithCitations(msg.content)"
                />

                <!-- 4. 生成式交互 UI 卡片：当回答包含 Markdown 对比表格时动态渲染筛选表与科技蓝 ECharts 图表 (PRD 6.3) -->
                <GenerativeUIRenderer
                  v-if="msg.content"
                  :content="msg.content"
                  :message-id="msg.id"
                  :hide-markdown="true"
                />

                <!-- 5. 端侧 RAG 引用来源底部徽章列表（点击弹出 RagPreviewModal 跳转并高亮原文位置） (PRD 6.2) -->
                <div
                  v-if="msg.ragCitations && msg.ragCitations.length > 0"
                  class="flex items-center flex-wrap gap-1.5 pt-1"
                >
                  <span class="text-[11px] text-muted flex items-center gap-1">
                    <FileText class="w-3 h-3 text-cyber-cyan" />
                    端侧 RAG 引用来源（点击高亮原文）：
                  </span>
                  <button
                    v-for="cite in msg.ragCitations"
                    :key="cite.chunkId"
                    type="button"
                    class="text-[11px] px-2.5 py-0.5 rounded-md bg-cyber-cyan/15 text-cyber-cyan border border-cyber-cyan/35 hover:bg-cyber-cyan/30 hover:shadow-cyber-cyan transition-all font-mono"
                    :title="cite.content"
                    @click="openCitationPreview(cite)"
                  >
                    【引用来源 [{{ cite.index }}]】 {{ cite.docName }} ({{
                      Math.round((cite.score || 0.92) * 100)
                    }}%)
                  </button>
                </div>
              </div>
            </VersionSwitcher>
          </div>
        </div>
      </div>
    </div>
  </main>
</template>
