<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  BookOpen,
  CheckCircle2,
  FileCode,
  FileText,
  Layers,
  ShieldCheck,
  Sparkles,
  Trash2,
  Upload,
  X,
} from 'lucide-vue-next'
import { useChatStore } from '@/stores/chatStore'
import {
  chunkDocumentSemantics,
  useClientRAG,
  type RagChunkMeta,
} from '@/composables/useClientRAG'
import type { Attachment, RagChunk } from '@/types/api'

const chatStore = useChatStore()
const {
  isPreviewModalOpen,
  activePreviewDocName,
  lastQueryTerms,
  hasBuiltinWhitepaper,
  uploadDocument,
  loadSampleWhitepaper,
  removeDocument,
  closeCitationPreview,
} = useClientRAG()

const fileInputRef = ref<HTMLInputElement | null>(null)
const chunksScrollRef = ref<HTMLElement | null>(null)
const viewMode = ref<'chunks' | 'fulltext'>('chunks')

const highlightedChunk = computed<RagChunkMeta | null>(() => {
  return (chatStore.highlightedRagChunk as RagChunkMeta | null) ?? null
})

const selectedDoc = computed<Attachment | null>(() => {
  if (highlightedChunk.value) {
    const matched = chatStore.ragDocuments.find(
      (d) => d.name === highlightedChunk.value?.docName,
    )
    if (matched) return matched
  }
  if (activePreviewDocName.value) {
    const byName = chatStore.ragDocuments.find(
      (d) => d.name === activePreviewDocName.value,
    )
    if (byName) return byName
  }
  return chatStore.ragDocuments[0] ?? null
})

const documentChunks = computed<RagChunkMeta[]>(() => {
  const doc = selectedDoc.value
  if (!doc) {
    // 若仅传入了临时 highlightedChunk，仍确保能展示该分块
    return highlightedChunk.value ? [highlightedChunk.value] : []
  }

  const fromActive = (chatStore.activeRagChunks as RagChunkMeta[]).filter(
    (c) => c.docName === doc.name,
  )
  if (fromActive.length > 0) return fromActive

  if (doc.content) {
    return chunkDocumentSemantics(doc.name, doc.content, 0)
  }
  return highlightedChunk.value ? [highlightedChunk.value] : []
})

function isTargetChunk(chunk: RagChunkMeta): boolean {
  const target = highlightedChunk.value
  if (!target) return false
  if (target.chunkId && chunk.chunkId === target.chunkId) return true
  if (
    target.docName === chunk.docName &&
    chunk.content.includes(target.content.slice(0, 40))
  ) {
    return true
  }
  return false
}

function escapeHtml(raw: string): string {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 在分块文本中高亮检索命中的关键词
 */
function renderHighlightedChunkText(text: string, isTarget: boolean): string {
  let html = escapeHtml(text)
  const terms = lastQueryTerms.value.filter((t) => t.length >= 2)
  if (!isTarget || terms.length === 0) return html

  for (const term of terms.slice(0, 8)) {
    const escapedTerm = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const regex = new RegExp(`(${escapedTerm})`, 'gi')
    html = html.replace(
      regex,
      '<mark class="px-1 py-0.5 rounded bg-cyber-cyan/25 text-cyber-cyan font-semibold">$1</mark>',
    )
  }
  return html
}

/**
 * 在完整文档原文中定位并高亮当前选中的引用段落
 */
const renderedFullTextWithHighlight = computed<string>(() => {
  const doc = selectedDoc.value
  if (!doc || !doc.content) {
    return escapeHtml(highlightedChunk.value?.content || '暂无文档内容')
  }

  const fullRaw = doc.content
  const target = highlightedChunk.value
  if (!target) return escapeHtml(fullRaw)

  // 取目标分块中具代表性的子串在全文中定位
  const snippet = target.content.trim()
  const anchor = snippet.length > 80 ? snippet.slice(0, 80) : snippet
  const idx = fullRaw.indexOf(anchor)

  if (idx < 0) {
    return escapeHtml(fullRaw)
  }

  const matchEnd = Math.min(fullRaw.length, idx + snippet.length)
  const before = escapeHtml(fullRaw.slice(0, idx))
  const middle = escapeHtml(fullRaw.slice(idx, matchEnd))
  const after = escapeHtml(fullRaw.slice(matchEnd))

  return `${before}<mark id="rag-fulltext-highlight-anchor" class="block my-2 p-3 rounded-xl bg-cyber-cyan/20 border-l-4 border-cyber-cyan text-main shadow-cyber-cyan">${middle}</mark>${after}`
})

async function scrollToHighlightedPassage() {
  await nextTick()
  const container = chunksScrollRef.value
  if (!container) return

  if (viewMode.value === 'chunks') {
    const targetEl = container.querySelector<HTMLElement>(
      '[data-rag-highlighted="true"]',
    )
    if (targetEl) {
      targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  } else {
    const anchorEl = container.querySelector<HTMLElement>(
      '#rag-fulltext-highlight-anchor',
    )
    if (anchorEl) {
      anchorEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }
}

watch(
  [isPreviewModalOpen, () => chatStore.highlightedRagChunk, viewMode],
  ([open]) => {
    if (open) {
      if (chatStore.highlightedRagChunk) {
        activePreviewDocName.value = chatStore.highlightedRagChunk.docName
      }
      void scrollToHighlightedPassage()
    }
  },
)

function selectChunkToHighlight(chunk: RagChunk) {
  chatStore.highlightedRagChunk = chunk
  void scrollToHighlightedPassage()
}

function selectDocument(docName: string) {
  activePreviewDocName.value = docName
  const firstChunk = (chatStore.activeRagChunks as RagChunkMeta[]).find(
    (c) => c.docName === docName,
  )
  if (firstChunk) {
    chatStore.highlightedRagChunk = firstChunk
  }
}

async function handleFileUpload(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || files.length === 0) return

  for (let i = 0; i < files.length; i++) {
    const doc = await uploadDocument(files[i])
    activePreviewDocName.value = doc.name
  }
  input.value = ''
}

function handleLoadBuiltinWhitepaper() {
  const doc = loadSampleWhitepaper()
  activePreviewDocName.value = doc.name
  const first = (chatStore.activeRagChunks as RagChunkMeta[]).find(
    (c) => c.docName === doc.name,
  )
  if (first) {
    chatStore.highlightedRagChunk = first
    void scrollToHighlightedPassage()
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0 scale-95"
      enter-to-class="opacity-100 scale-100"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="opacity-100 scale-100"
      leave-to-class="opacity-0 scale-95"
    >
      <div
        v-if="isPreviewModalOpen"
        class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/65 backdrop-blur-md"
        @click.self="closeCitationPreview"
      >
        <div
          class="glass-modal w-full max-w-4xl max-h-[86vh] rounded-2xl border border-cyber-cyan/35 flex flex-col overflow-hidden shadow-cyber-glow"
        >
          <!-- 顶部标题栏 -->
          <div
            class="flex items-center justify-between gap-3 px-5 py-3.5 border-b border-subtle bg-elevated/60"
          >
            <div class="flex items-center gap-2.5 min-w-0">
              <div
                class="w-8 h-8 rounded-xl bg-cyber-cyan/15 border border-cyber-cyan/40 flex items-center justify-center text-cyber-cyan shrink-0"
              >
                <BookOpen class="w-4 h-4" />
              </div>
              <div class="min-w-0">
                <div class="flex items-center gap-2">
                  <h2 class="text-sm font-bold text-main truncate">
                    端侧轻量 RAG 知识库与引用原文定位
                  </h2>
                  <span
                    class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-mono bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 shrink-0"
                  >
                    <ShieldCheck class="w-3 h-3" />
                    <span>浏览器本地内存索引 · 零上传服务器</span>
                  </span>
                </div>
                <p class="text-[11px] text-muted truncate">
                  滑动窗口语义分块 + BM25 / 词向量余弦相似度混合重排 · 点击回答中的【引用来源 [1] [2]】自动高亮对应段落
                </p>
              </div>
            </div>

            <button
              type="button"
              class="p-1.5 rounded-xl bg-card/80 hover:bg-elevated text-sub hover:text-main border border-subtle transition-colors"
              @click="closeCitationPreview"
            >
              <X class="w-4 h-4" />
            </button>
          </div>

          <!-- 工具栏：文档切换 + 上传新文档 + 一键加载示例技术白皮书 + 视图模式切换 -->
          <div
            class="flex flex-wrap items-center justify-between gap-2 px-5 py-2.5 bg-card/60 border-b border-subtle text-xs"
          >
            <div class="flex items-center flex-wrap gap-1.5">
              <!-- 已加载的本地文档胶囊 -->
              <div
                v-for="doc in chatStore.ragDocuments"
                :key="doc.id"
                class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg border transition-all cursor-pointer"
                :class="
                  selectedDoc?.id === doc.id
                    ? 'bg-cyber-cyan/15 text-cyber-cyan border-cyber-cyan/45 shadow-cyber-cyan'
                    : 'bg-elevated/60 text-sub border-subtle hover:text-main'
                "
                @click="selectDocument(doc.name)"
              >
                <FileCode
                  v-if="/\.(ts|js|py|java|sql|json)$/i.test(doc.name)"
                  class="w-3.5 h-3.5 shrink-0"
                />
                <FileText v-else class="w-3.5 h-3.5 shrink-0" />
                <span class="max-w-[180px] truncate font-medium">{{ doc.name }}</span>
                <span class="text-[10px] font-mono opacity-80">
                  ({{ doc.chunksCount || 0 }} 块)
                </span>
                <button
                  type="button"
                  class="ml-0.5 text-muted hover:text-red-400 transition-colors"
                  title="移除该本地文档"
                  @click.stop="removeDocument(doc.id)"
                >
                  <Trash2 class="w-3 h-3" />
                </button>
              </div>

              <!-- 上传本地文档按钮 -->
              <button
                type="button"
                class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-elevated hover:bg-primary/20 text-sub hover:text-primary-light border border-subtle transition-colors"
                @click="fileInputRef?.click()"
              >
                <Upload class="w-3.5 h-3.5" />
                <span>上传本地文档</span>
              </button>
              <input
                ref="fileInputRef"
                type="file"
                accept=".txt,.md,.json,.csv,.ts,.js,.py,.java,.sql"
                multiple
                class="hidden"
                @change="handleFileUpload"
              />

              <!-- 一键加载内置示例技术白皮书 -->
              <button
                v-if="!hasBuiltinWhitepaper"
                type="button"
                class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-primary/15 hover:bg-primary/25 text-primary-light border border-primary/40 transition-colors"
                @click="handleLoadBuiltinWhitepaper"
              >
                <Sparkles class="w-3.5 h-3.5" />
                <span>一键加载内置技术白皮书</span>
              </button>
            </div>

            <!-- 视图切换：语义分块定位视图 vs 完整原文高亮视图 -->
            <div class="flex items-center gap-1 p-1 rounded-xl bg-elevated/80 border border-subtle">
              <button
                type="button"
                class="px-2.5 py-1 rounded-lg text-[11px] transition-all"
                :class="
                  viewMode === 'chunks'
                    ? 'cyber-btn-primary text-white font-medium'
                    : 'text-sub hover:text-main'
                "
                @click="viewMode = 'chunks'"
              >
                语义分块视图 ({{ documentChunks.length }})
              </button>
              <button
                type="button"
                class="px-2.5 py-1 rounded-lg text-[11px] transition-all"
                :class="
                  viewMode === 'fulltext'
                    ? 'cyber-btn-primary text-white font-medium'
                    : 'text-sub hover:text-main'
                "
                @click="viewMode = 'fulltext'"
              >
                全文原文高亮
              </button>
            </div>
          </div>

          <!-- 当前命中引用横幅提示 -->
          <div
            v-if="highlightedChunk"
            class="px-5 py-2 bg-cyber-cyan/10 border-b border-cyber-cyan/30 flex flex-wrap items-center justify-between gap-2 text-xs"
          >
            <div class="flex items-center gap-2 text-cyber-cyan">
              <CheckCircle2 class="w-4 h-4 shrink-0" />
              <span>
                已精准定位至
                <strong class="font-mono">
                  【引用来源 [{{ highlightedChunk.index }}]】
                </strong>
                · 所属文档：
                <span class="font-mono underline">{{ highlightedChunk.docName }}</span>
              </span>
            </div>
            <div class="flex items-center gap-3 font-mono text-[11px] text-sub">
              <span>
                混合检索匹配度：
                <strong class="text-cyber-cyan">
                  {{ Math.round((highlightedChunk.score || 0.92) * 100) }}%
                </strong>
              </span>
              <span v-if="highlightedChunk.bm25Score !== undefined">
                BM25: {{ highlightedChunk.bm25Score }}
              </span>
              <span v-if="highlightedChunk.vectorScore !== undefined">
                Cosine: {{ highlightedChunk.vectorScore }}
              </span>
            </div>
          </div>

          <!-- 主体内容滚动区 -->
          <div
            ref="chunksScrollRef"
            class="flex-1 overflow-y-auto p-5 space-y-3 scroll-smooth"
          >
            <!-- 空状态引导 -->
            <div
              v-if="documentChunks.length === 0"
              class="py-12 flex flex-col items-center justify-center text-center space-y-3"
            >
              <Layers class="w-10 h-10 text-cyber-cyan/60" />
              <div class="space-y-1 max-w-md">
                <h3 class="text-sm font-semibold text-main">
                  当前尚未挂载端侧知识库文档
                </h3>
                <p class="text-xs text-muted leading-relaxed">
                  您可以上传本地 Markdown、论文、文本或代码文件，或直接点击下方按钮一键加载内置《XCWeb 2026 技术白皮书》体验端侧 BM25 + 向量混合检索。
                </p>
              </div>
              <div class="flex items-center gap-2 pt-1">
                <button
                  type="button"
                  class="px-3.5 py-1.5 rounded-xl cyber-btn-primary text-xs font-medium inline-flex items-center gap-1.5"
                  @click="handleLoadBuiltinWhitepaper"
                >
                  <Sparkles class="w-3.5 h-3.5" />
                  <span>加载内置示例技术白皮书</span>
                </button>
                <button
                  type="button"
                  class="px-3.5 py-1.5 rounded-xl bg-elevated hover:bg-card text-xs text-main border border-subtle inline-flex items-center gap-1.5"
                  @click="fileInputRef?.click()"
                >
                  <Upload class="w-3.5 h-3.5 text-cyber-cyan" />
                  <span>选择本地文件</span>
                </button>
              </div>
            </div>

            <!-- 模式 A：语义分块定位列表（自动滚动并高亮目标分块） -->
            <template v-else-if="viewMode === 'chunks'">
              <div
                v-for="(chunk, cIdx) in documentChunks"
                :key="chunk.chunkId"
                :data-chunk-id="chunk.chunkId"
                :data-rag-highlighted="isTargetChunk(chunk) ? 'true' : 'false'"
                class="rounded-2xl p-4 border transition-all duration-300 cursor-pointer"
                :class="
                  isTargetChunk(chunk)
                    ? 'bg-cyber-cyan/15 border-cyber-cyan shadow-cyber-cyan ring-1 ring-cyber-cyan/50'
                    : 'bg-card/75 border-subtle hover:border-primary/40'
                "
                @click="selectChunkToHighlight(chunk)"
              >
                <div class="flex flex-wrap items-center justify-between gap-2 mb-2">
                  <div class="flex items-center gap-2">
                    <span
                      class="px-2 py-0.5 rounded-md font-mono text-[11px] font-semibold border"
                      :class="
                        isTargetChunk(chunk)
                          ? 'bg-cyber-cyan text-slate-950 border-cyber-cyan'
                          : 'bg-elevated text-sub border-subtle'
                      "
                    >
                      切片 #{{ cIdx + 1 }}
                    </span>

                    <span
                      v-if="chunk.sectionTitle"
                      class="text-xs font-semibold text-main"
                    >
                      {{ chunk.sectionTitle }}
                    </span>

                    <span
                      v-if="isTargetChunk(chunk)"
                      class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-cyber-cyan/25 text-cyber-cyan border border-cyber-cyan/50 animate-pulse"
                    >
                      <Sparkles class="w-2.5 h-2.5" />
                      <span>当前引用命中段落 [{{ highlightedChunk?.index || cIdx + 1 }}]</span>
                    </span>
                  </div>

                  <div class="flex items-center gap-2 text-[11px] font-mono text-muted">
                    <span v-if="chunk.startOffset !== undefined">
                      偏移 {{ chunk.startOffset }}~{{ chunk.endOffset }}
                    </span>
                    <span class="text-cyber-cyan">
                      相似度 {{ Math.round((chunk.score || 0.9) * 100) }}%
                    </span>
                  </div>
                </div>

                <!-- 分块原文内容（支持命中词高亮） -->
                <div
                  class="text-xs leading-relaxed whitespace-pre-wrap font-mono"
                  :class="isTargetChunk(chunk) ? 'text-main' : 'text-sub'"
                  v-html="renderHighlightedChunkText(chunk.content, isTargetChunk(chunk))"
                />
              </div>
            </template>

            <!-- 模式 B：完整文档原文上下文视图（内联高亮引用位置） -->
            <template v-else>
              <div
                class="rounded-2xl bg-card/85 border border-subtle p-4 text-xs font-mono text-sub leading-relaxed whitespace-pre-wrap"
                v-html="renderedFullTextWithHighlight"
              />
            </template>
          </div>

          <!-- 底部说明栏 -->
          <div
            class="px-5 py-3 border-t border-subtle bg-elevated/50 flex flex-wrap items-center justify-between gap-2 text-[11px] text-muted"
          >
            <span>
              端侧分块策略：380 字符语义段落窗口 + 64 字符滑动重叠（Sliding Window Overlap）
            </span>
            <button
              type="button"
              class="px-3.5 py-1 rounded-xl bg-card hover:bg-elevated text-main border border-subtle transition-colors"
              @click="closeCitationPreview"
            >
              关闭预览
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
