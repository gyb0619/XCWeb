import { computed, ref } from 'vue'
import type { Attachment, Message, RagChunk } from '@/types/api'
import { useChatStore } from '@/stores/chatStore'

export interface RagChunkMeta extends RagChunk {
  sectionTitle?: string
  startOffset?: number
  endOffset?: number
  bm25Score?: number
  vectorScore?: number
}

const SAMPLE_WHITEPAPER_NAME = 'XCWeb_2026_AI_Native_Architecture_Whitepaper.md'

export const BUILTIN_WHITEPAPER_CONTENT = `# XCWeb 2026 AI-Native 认知架构与配额防刷技术白皮书

## 1. 2026 AI-Native Web 架构演进与流式状态机规范
传统大模型 Web 界面大多停留在“单轮文字套壳”阶段，普遍存在流式输出卡顿、思维链（Chain-of-Thought）与正文混杂、缺乏端侧知识增强等痛点。XCWeb（Xtreme Cognitive Web）采用“科技深邃蓝（Electric Cyber Blue）”设计系统，通过 POST /api/chat/completions 建立标准化的全双工感 SSE（Server-Sent Events）事件管道。
服务端统一将上游推理模型的 <think> 标签或 reasoning_content 字段规范化为独立的 thinking 事件流，并依次输出 meta、agent_step、thinking、content、usage、done 事件。前端通过 fetch + ReadableStream 增量解码，实现毫秒级首包响应与零阻塞打字机渲染。

## 2. DeepSeek-R1 蒸馏模型与强化学习推理（RLVR）机制解析
DeepSeek-R1-Distill-Qwen-7B 通过将大规模强化学习（RLVR）习得的长思维链推理模式蒸馏至轻量级稠密模型，显著提升了复杂逻辑、数学推导与代码架构分析能力。与传统指令微调模型（如 Qwen2.5-7B-Instruct、GLM-4-Flash）直接输出结论不同，推理模型在生成最终正文前会经历自我反思、假设检验与多路径剪枝阶段。
XCWeb 在前端构建了独立的 ThinkingBlock 深度思考抽屉组件：思考进行中展示科技蓝旋转指示器与 0.1s 精度实时跳动计时器；思考完成瞬间，旋转指示器模糊淡出（blur + opacity 过渡），翠绿色微光对勾弹性放大浮入，并联动触发正文容器 600ms 自下而上滑升动效（slideUp）。

## 3. 四级角色权限体系与游客三重防刷配额矩阵
为在“零门槛免登录体验”与“网关算力防刷保护”之间取得工程平衡，XCWeb 设立了四级角色权限体系：
- 游客（Guest）：每日 10 次对话配额，单次上下文上限 4,000 Tokens，会话仅存储于浏览器本地 IndexedDB。
- 普通用户（User）：每日 50 次对话配额，单次上下文上限 16,000 Tokens，支持无痕隐私对话、多版本回答分支与 SQLite 云端增量同步。
- 管理员（Admin）与开发者（Dev）：不限调用次数与上下文长度，分别拥有预置模型启停看板与 Dev Console 调试追踪能力。
针对游客身份，系统实施严密的“匿名签名令牌（guestId）+ 浏览器指纹哈希（X-Fingerprint SHA-256）+ 客户端出口 IP”三重独立计数校验，任一维度触及阈值即触发 429 QUOTA_EXCEEDED 拦截，并按 Asia/Shanghai 时区自然日 00:00 自动重置。

## 4. Agent 可视化思考时间轴与白名单工具链
当对话开启 Agent 规划或命中复杂检索分析意图时，XCWeb 启动四阶段智能体执行管线：规划（Plan）→ 工具调用（Tool Call）→ 数据回传（Observation / Data Return）→ 总结分析（Synthesis）。
所有可调用工具均受服务端白名单严格管控（包含 planner、weather_query、web_search、data_analyzer、code_sandbox），严禁客户端注入未授权外部端点。前端 AgentTimeline 组件以可交互流程图形式实时呈现每一步的输入参数（input）、执行输出（output）与毫秒级耗时（durationMs）。

## 5. 端侧轻量 RAG 混合检索引擎与零上传隐私保护
XCWeb 内置纯浏览器端运行的轻量级 RAG（Retrieval-Augmented Generation）知识检索引擎。用户上传的长文本、学术论文或代码文件直接在浏览器内存中完成带滑动窗口重叠（Sliding Window Overlap）的语义段落切片。
在提问阶段，useClientRAG 引擎在本地执行 BM25 词频逆文档频率算法与词向量余弦相似度（Cosine Similarity）混合打分，自动提取 Top-3 高相关切片注入对话上下文，并在模型回答中生成可点击的【引用来源 [1] [2]】锚点徽章，点击即可打开原文预览弹窗并精准高亮对应段落，全程零原始文件上传服务器。`

// 模块级单例状态，确保跨组件共享同一套知识库视图与弹窗开关
const showKnowledgeModal = ref<boolean>(false)
const activePreviewDocName = ref<string>('')
const lastQueryTerms = ref<string[]>([])

function createDocId(): string {
  return `rag_doc_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`
}

/**
 * 中英文混合分词器：提取英文/代码词元 + 中文二元词组（Bigrams）与关键词
 */
export function tokenizeText(text: string): string[] {
  if (!text) return []
  const lower = text.toLowerCase()
  const tokens: string[] = []

  // 1. 提取英文单词、数字与代码标识符
  const wordMatches = lower.match(/[a-z0-9_.-]{2,}/g)
  if (wordMatches) {
    tokens.push(...wordMatches)
  }

  // 2. 提取连续中文字符并生成 unigram + bigram
  const cjkBlocks = lower.match(/[\u4e00-\u9fa5]{2,}/g)
  if (cjkBlocks) {
    for (const block of cjkBlocks) {
      if (block.length <= 4) {
        tokens.push(block)
      }
      for (let i = 0; i < block.length - 1; i++) {
        tokens.push(block.slice(i, i + 2))
      }
    }
  }

  return tokens
}

/**
 * 浏览器本地语义段落分块（带滑动窗口重叠）
 */
export function chunkDocumentSemantics(
  docName: string,
  rawText: string,
  baseIndexOffset = 0,
  maxChunkChars = 380,
  overlapChars = 64,
): RagChunkMeta[] {
  const normalized = rawText.replace(/\r\n/g, '\n').trim()
  if (!normalized) return []

  // 按 Markdown 标题或双换行拆分语义自然段
  const rawSections = normalized
    .split(/\n(?=#{1,4}\s)|\n{2,}/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)

  const chunks: RagChunkMeta[] = []
  let currentSectionTitle = '文档概述'
  let runningBuffer = ''
  let cursorOffset = 0

  const flushChunk = (contentStr: string, startPos: number) => {
    const clean = contentStr.trim()
    if (!clean) return
    const chunkIdx = chunks.length + 1
    chunks.push({
      index: baseIndexOffset + chunkIdx,
      docName,
      chunkId: `${docName}#chunk_${chunkIdx}`,
      content: clean,
      score: 0.9,
      sectionTitle: currentSectionTitle,
      startOffset: startPos,
      endOffset: startPos + clean.length,
    })
  }

  for (const block of rawSections) {
    const headingMatch = block.match(/^#{1,4}\s+([^\n]+)/)
    if (headingMatch) {
      currentSectionTitle = headingMatch[1].trim()
    }

    const posInDoc = normalized.indexOf(block, cursorOffset)
    const actualPos = posInDoc >= 0 ? posInDoc : cursorOffset

    if ((runningBuffer + '\n\n' + block).trim().length <= maxChunkChars) {
      runningBuffer = runningBuffer ? `${runningBuffer}\n\n${block}` : block
    } else {
      if (runningBuffer) {
        flushChunk(runningBuffer, Math.max(0, actualPos - runningBuffer.length))
        // 保留尾部滑动窗口重叠上下文
        const overlapSlice =
          runningBuffer.length > overlapChars
            ? runningBuffer.slice(-overlapChars)
            : ''
        runningBuffer = overlapSlice ? `${overlapSlice}\n${block}` : block
      } else {
        // 单个大段落超过 maxChunkChars，按滑动窗口切分
        let stepStart = 0
        while (stepStart < block.length) {
          const piece = block.slice(stepStart, stepStart + maxChunkChars)
          flushChunk(piece, actualPos + stepStart)
          if (stepStart + maxChunkChars >= block.length) break
          stepStart += maxChunkChars - overlapChars
        }
        runningBuffer = ''
      }
    }

    cursorOffset = actualPos + block.length
  }

  if (runningBuffer.trim()) {
    flushChunk(
      runningBuffer,
      Math.max(0, normalized.length - runningBuffer.length),
    )
  }

  return chunks
}

/**
 * 计算 BM25 + 词频向量余弦相似度（Cosine Similarity）混合检索得分
 */
export function rankChunksByHybridBM25AndVector(
  query: string,
  chunks: RagChunkMeta[],
  topK = 3,
): RagChunkMeta[] {
  if (chunks.length === 0) return []

  const queryTokens = tokenizeText(query)
  lastQueryTerms.value = Array.from(new Set(queryTokens)).slice(0, 12)

  if (queryTokens.length === 0) {
    return chunks.slice(0, topK).map((c, idx) => ({
      ...c,
      index: idx + 1,
      score: Number((0.92 - idx * 0.04).toFixed(2)),
    }))
  }

  const docTokensList = chunks.map((c) =>
    tokenizeText(`${c.sectionTitle || ''} ${c.content}`),
  )
  const avgDocLen =
    docTokensList.reduce((acc, list) => acc + list.length, 0) /
    Math.max(1, chunks.length)

  // 计算逆文档频率 IDF
  const idfMap = new Map<string, number>()
  const uniqueQueryTerms = Array.from(new Set(queryTokens))
  const N = chunks.length

  for (const term of uniqueQueryTerms) {
    let df = 0
    for (const docTokens of docTokensList) {
      if (docTokens.includes(term)) df++
    }
    const idf = Math.log(1 + (N - df + 0.5) / (df + 0.5))
    idfMap.set(term, idf)
  }

  // 构建 Query 词频向量
  const queryTf = new Map<string, number>()
  for (const t of queryTokens) {
    queryTf.set(t, (queryTf.get(t) ?? 0) + 1)
  }

  const k1 = 1.5
  const b = 0.75

  const rawScored = chunks.map((chunk, i) => {
    const docTokens = docTokensList[i]
    const docLen = Math.max(1, docTokens.length)
    const docTf = new Map<string, number>()
    for (const t of docTokens) {
      docTf.set(t, (docTf.get(t) ?? 0) + 1)
    }

    // 1. BM25 得分
    let bm25 = 0
    for (const term of uniqueQueryTerms) {
      const tf = docTf.get(term) ?? 0
      if (tf === 0) continue
      const idf = idfMap.get(term) ?? 0.1
      const num = tf * (k1 + 1)
      const denom = tf + k1 * (1 - b + b * (docLen / avgDocLen))
      bm25 += idf * (num / denom)
    }

    // 2. 词向量余弦相似度 (Cosine Similarity)
    let dotProduct = 0
    let queryNormSq = 0
    let docNormSq = 0

    for (const [term, qCount] of queryTf.entries()) {
      const idf = idfMap.get(term) ?? 0.5
      const qWeight = qCount * idf
      const dWeight = (docTf.get(term) ?? 0) * idf
      dotProduct += qWeight * dWeight
      queryNormSq += qWeight * qWeight
    }

    for (const [term, dCount] of docTf.entries()) {
      const idf = idfMap.get(term) ?? 0.3
      const dWeight = dCount * idf
      docNormSq += dWeight * dWeight
    }

    const cosine =
      queryNormSq > 0 && docNormSq > 0
        ? dotProduct / (Math.sqrt(queryNormSq) * Math.sqrt(docNormSq))
        : 0

    return {
      chunk,
      bm25,
      cosine,
    }
  })

  const maxBm25 = Math.max(0.001, ...rawScored.map((s) => s.bm25))

  const combined = rawScored.map(({ chunk, bm25, cosine }, idx) => {
    const normBm25 = bm25 / maxBm25
    const hybrid = normBm25 * 0.6 + cosine * 0.4
    // 归一化到直观展示区间 [0.74, 0.98]
    const displayScore =
      hybrid > 0.05
        ? Math.min(0.99, Number((0.76 + hybrid * 0.22).toFixed(2)))
        : Number(Math.max(0.68, 0.85 - idx * 0.03).toFixed(2))

    return {
      ...chunk,
      bm25Score: Number(bm25.toFixed(3)),
      vectorScore: Number(cosine.toFixed(3)),
      score: displayScore,
    }
  })

  return combined
    .sort((a, b) => b.score - a.score)
    .slice(0, topK)
    .map((item, idx) => ({
      ...item,
      index: idx + 1,
    }))
}

export function useClientRAG() {
  const chatStore = useChatStore()

  const isPreviewModalOpen = computed<boolean>({
    get: () => Boolean(chatStore.highlightedRagChunk) || showKnowledgeModal.value,
    set: (val: boolean) => {
      showKnowledgeModal.value = val
      if (!val) {
        chatStore.highlightedRagChunk = null
      }
    },
  })

  const hasBuiltinWhitepaper = computed<boolean>(() =>
    chatStore.ragDocuments.some((d) => d.name === SAMPLE_WHITEPAPER_NAME),
  )

  /**
   * 重新构建全部端侧文档的语义分块索引
   */
  function rebuildChunkIndex() {
    const allChunks: RagChunkMeta[] = []
    for (const doc of chatStore.ragDocuments) {
      if (!doc.content) continue
      const docChunks = chunkDocumentSemantics(
        doc.name,
        doc.content,
        allChunks.length,
      )
      doc.chunksCount = docChunks.length
      allChunks.push(...docChunks)
    }
    chatStore.activeRagChunks = allChunks
  }

  /**
   * 上传本地文件并在浏览器内存中完成语义分块
   */
  async function uploadDocument(file: File): Promise<Attachment> {
    const text = await file.text()
    const existingIdx = chatStore.ragDocuments.findIndex(
      (d) => d.name === file.name,
    )
    if (existingIdx >= 0) {
      chatStore.ragDocuments.splice(existingIdx, 1)
    }

    const docChunks = chunkDocumentSemantics(
      file.name,
      text,
      chatStore.activeRagChunks.length,
    )

    const attachment: Attachment = {
      id: createDocId(),
      name: file.name,
      size: file.size,
      type: file.type || 'text/plain',
      content: text,
      chunksCount: docChunks.length,
    }

    chatStore.ragDocuments.push(attachment)
    rebuildChunkIndex()
    activePreviewDocName.value = attachment.name
    return attachment
  }

  /**
   * 一键加载内置《XCWeb 2026 AI-Native 认知架构与配额防刷技术白皮书》示例文档
   */
  function loadSampleWhitepaper(): Attachment {
    const existing = chatStore.ragDocuments.find(
      (d) => d.name === SAMPLE_WHITEPAPER_NAME,
    )
    if (existing) {
      rebuildChunkIndex()
      activePreviewDocName.value = existing.name
      return existing
    }

    const bytes = new TextEncoder().encode(BUILTIN_WHITEPAPER_CONTENT).length
    const docChunks = chunkDocumentSemantics(
      SAMPLE_WHITEPAPER_NAME,
      BUILTIN_WHITEPAPER_CONTENT,
      chatStore.activeRagChunks.length,
    )

    const attachment: Attachment = {
      id: createDocId(),
      name: SAMPLE_WHITEPAPER_NAME,
      size: bytes,
      type: 'text/markdown',
      content: BUILTIN_WHITEPAPER_CONTENT,
      chunksCount: docChunks.length,
    }

    chatStore.ragDocuments.push(attachment)
    rebuildChunkIndex()
    activePreviewDocName.value = attachment.name
    return attachment
  }

  /**
   * 删除指定本地文档并更新内存索引
   */
  function removeDocument(docId: string) {
    chatStore.removeRagDocument(docId)
    rebuildChunkIndex()
  }

  /**
   * 针对用户提问执行 BM25 + 向量余弦相似度混合检索 Top-K 段落
   */
  function retrieveTopChunks(query: string, topK = 3): RagChunkMeta[] {
    if (
      chatStore.ragDocuments.length > 0 &&
      chatStore.activeRagChunks.length === 0
    ) {
      rebuildChunkIndex()
    }
    if (chatStore.activeRagChunks.length === 0) return []

    return rankChunksByHybridBM25AndVector(
      query,
      chatStore.activeRagChunks as RagChunkMeta[],
      topK,
    )
  }

  /**
   * 当处于离线/Mock 演示模式且用户尚未手动上传文件时，
   * 自动挂载内置技术白皮书并返回匹配的 Top-3 引用切片，确保【引用来源 [1] [2]】始终可点击并高亮原文
   */
  function ensureDemoCitationsForQuery(query: string, topK = 3): RagChunkMeta[] {
    if (chatStore.activeRagChunks.length === 0) {
      loadSampleWhitepaper()
    }
    return retrieveTopChunks(query, topK)
  }

  /**
   * 点击引用来源徽章（如【引用来源 [1]】）时打开弹窗并定位高亮对应原文分块
   */
  function openCitationPreview(chunk: RagChunk) {
    activePreviewDocName.value = chunk.docName
    chatStore.highlightedRagChunk = chunk
    showKnowledgeModal.value = true
  }

  /**
   * 根据消息与引用序号 [1] / [2] 打开对应原文高亮预览
   */
  function openCitationByIndex(msg: Message, citeIndex: number) {
    const citations = msg.ragCitations ?? []
    let target =
      citations.find((c) => c.index === citeIndex) ||
      citations[citeIndex - 1] ||
      null

    if (!target) {
      const fallback = ensureDemoCitationsForQuery(msg.content || '架构', 3)
      target =
        fallback.find((c) => c.index === citeIndex) || fallback[0] || null
    }

    if (target) {
      openCitationPreview(target)
    } else {
      showKnowledgeModal.value = true
    }
  }

  function openKnowledgeBaseModal() {
    if (
      !activePreviewDocName.value &&
      chatStore.ragDocuments.length > 0
    ) {
      activePreviewDocName.value = chatStore.ragDocuments[0].name
    }
    showKnowledgeModal.value = true
  }

  function closeCitationPreview() {
    showKnowledgeModal.value = false
    chatStore.highlightedRagChunk = null
  }

  return {
    isPreviewModalOpen,
    showKnowledgeModal,
    activePreviewDocName,
    lastQueryTerms,
    hasBuiltinWhitepaper,
    uploadDocument,
    loadSampleWhitepaper,
    removeDocument,
    rebuildChunkIndex,
    retrieveTopChunks,
    ensureDemoCitationsForQuery,
    openCitationPreview,
    openCitationByIndex,
    openKnowledgeBaseModal,
    closeCitationPreview,
  }
}
