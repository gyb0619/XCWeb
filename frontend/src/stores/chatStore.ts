import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  AgentStep,
  Attachment,
  ChatCompletionRequest,
  Conversation,
  Message,
  MessageVersion,
  RagChunk,
  SSEDeltaEvent,
  SSEDoneEvent,
  SSEErrorEvent,
  SSEMetaEvent,
  SSEUsageEvent,
  SyncConversationResponse,
} from '@/types/api'
import {
  idbDeleteConversation,
  idbGetConversations,
  idbSaveConversation,
} from '@/utils/idb'
import {
  apiRequest,
  buildAuthHeaders,
  handleHttpErrorResponse,
} from '@/utils/apiClient'
import { useAuthStore } from '@/stores/authStore'
import { useConfigStore } from '@/stores/configStore'
import { useQuotaStore } from '@/stores/quotaStore'

function createId(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const activeConversationId = ref<string>('')
  const isIncognito = ref<boolean>(false)
  const incognitoConversation = ref<Conversation | null>(null)
  const incognitoNotice = ref<string | null>(null)

  const isGenerating = ref<boolean>(false)
  const abortController = ref<AbortController | null>(null)

  // 无损历史重试状态 (PRD 4.3)
  const retryingMessageId = ref<string | null>(null)
  const retryTargetAssistantId = ref<string | null>(null)
  const inputDraft = ref<string>('')

  // 端侧轻量 RAG 本地文档挂载列表 (PRD 6.2)
  const ragDocuments = ref<Attachment[]>([])
  const activeRagChunks = ref<RagChunk[]>([])
  const highlightedRagChunk = ref<RagChunk | null>(null)

  // 预留给阶段 3 (@AI-LLM-Engineer) 挂载自定义流式处理器
  const externalStreamExecutor = ref<
    ((req: ChatCompletionRequest, assistantMsg: Message, signal: AbortSignal) => Promise<void>) | null
  >(null)

  const ownerStorageKey = computed<string>(() => {
    const authStore = useAuthStore()
    if (authStore.isLoggedIn && authStore.user) {
      return `user_${authStore.user.id}`
    }
    return `guest_${authStore.guestId || authStore.fingerprintHash.slice(0, 12) || 'anon'}`
  })

  const activeConversation = computed<Conversation | null>(() => {
    if (isIncognito.value) {
      return incognitoConversation.value
    }
    return (
      conversations.value.find((c) => c.id === activeConversationId.value) ||
      conversations.value[0] ||
      null
    )
  })

  const currentMessages = computed<Message[]>(() => {
    return activeConversation.value?.messages ?? []
  })

  function hydrateMessageFields(msg: Message): Message {
    let parsedAttachments: Attachment[] = msg.attachments ?? []
    if (!parsedAttachments.length && msg.attachmentsJson) {
      try {
        parsedAttachments = JSON.parse(msg.attachmentsJson) as Attachment[]
      } catch {
        parsedAttachments = []
      }
    }

    let parsedVersions: MessageVersion[] = msg.versions ?? []
    if (!parsedVersions.length && msg.versionsJson) {
      try {
        parsedVersions = JSON.parse(msg.versionsJson) as MessageVersion[]
      } catch {
        parsedVersions = []
      }
    }

    if (msg.role === 'assistant' && parsedVersions.length === 0 && msg.content) {
      parsedVersions = [
        {
          versionId: `${msg.id}_v1`,
          content: msg.content,
          thinkingContent: msg.thinkingContent ?? null,
          thinkingDurationMs: msg.thinkingDurationMs,
          agentSteps: msg.agentSteps,
          ragCitations: msg.ragCitations,
          model: msg.model,
          isMock: msg.isMock,
          fallbackReason: msg.fallbackReason,
          usage: msg.usage,
          createdAt: msg.createdAt,
        },
      ]
    }

    return {
      ...msg,
      attachments: parsedAttachments,
      versions: parsedVersions,
      currentVersionIndex:
        msg.currentVersionIndex !== undefined
          ? msg.currentVersionIndex
          : Math.max(0, parsedVersions.length - 1),
    }
  }

  function serializeConversationForSync(conv: Conversation): Conversation {
    return {
      ...conv,
      messages: conv.messages.map((m) => ({
        ...m,
        attachmentsJson: JSON.stringify(m.attachments ?? []),
        versionsJson: JSON.stringify(m.versions ?? []),
      })),
    }
  }

  async function loadConversations(): Promise<void> {
    const stored = await idbGetConversations(ownerStorageKey.value)
    const hydrated = stored.map((conv) => ({
      ...conv,
      messages: (conv.messages || []).map(hydrateMessageFields),
    }))
    conversations.value = hydrated

    if (hydrated.length > 0) {
      if (!hydrated.some((c) => c.id === activeConversationId.value)) {
        activeConversationId.value = hydrated[0].id
      }
    } else {
      const initial = createNewConversation(false)
      activeConversationId.value = initial.id
    }
  }

  function createNewConversation(persistImmediate = true): Conversation {
    const configStore = useConfigStore()
    const now = Date.now()
    const newConv: Conversation = {
      id: createId('conv'),
      title: isIncognito.value ? '无痕临时对话' : '新对话',
      model: configStore.selectedModel,
      createdAt: now,
      updatedAt: now,
      isIncognito: isIncognito.value,
      messages: [],
    }

    if (isIncognito.value) {
      incognitoConversation.value = newConv
      return newConv
    }

    conversations.value.unshift(newConv)
    activeConversationId.value = newConv.id
    if (persistImmediate) {
      void persistConversation(newConv)
    }
    return newConv
  }

  function selectConversation(convId: string) {
    if (isIncognito.value) {
      setIncognito(false)
    }
    const target = conversations.value.find((c) => c.id === convId)
    if (target) {
      activeConversationId.value = target.id
    }
  }

  async function deleteConversation(convId: string): Promise<void> {
    const authStore = useAuthStore()
    conversations.value = conversations.value.filter((c) => c.id !== convId)
    await idbDeleteConversation(ownerStorageKey.value, convId)

    if (activeConversationId.value === convId) {
      if (conversations.value.length > 0) {
        activeConversationId.value = conversations.value[0].id
      } else {
        createNewConversation(false)
      }
    }

    if (authStore.isLoggedIn) {
      try {
        await apiRequest<void>(`/api/sync/conversations/${encodeURIComponent(convId)}`, {
          method: 'DELETE',
        })
      } catch {
        // ignore offline delete error
      }
    }
  }

  async function persistConversation(conv: Conversation): Promise<void> {
    if (conv.isIncognito) return

    const serialized = serializeConversationForSync(conv)
    await idbSaveConversation(ownerStorageKey.value, serialized)

    const authStore = useAuthStore()
    if (authStore.isLoggedIn && authStore.token) {
      try {
        const res = await apiRequest<SyncConversationResponse>(
          '/api/sync/conversations',
          {
            method: 'POST',
            body: JSON.stringify(serialized),
          },
        )
        if (res.status === 'server_newer' && res.conversation) {
          const serverHydrated: Conversation = {
            ...res.conversation,
            messages: (res.conversation.messages || []).map(hydrateMessageFields),
          }
          const idx = conversations.value.findIndex((c) => c.id === conv.id)
          if (idx >= 0) {
            conversations.value[idx] = serverHydrated
          }
          await idbSaveConversation(ownerStorageKey.value, serverHydrated)
        }
      } catch {
        // 离线时保留 IndexedDB 副本，下次同步时按 updatedAt 仲裁
      }
    }
  }

  async function syncConversationsWithCloud(): Promise<void> {
    const authStore = useAuthStore()
    if (!authStore.isLoggedIn || !authStore.token) return

    try {
      const remoteList = await apiRequest<Conversation[]>(
        '/api/sync/conversations?since=0',
      )
      if (!Array.isArray(remoteList)) return

      const localMap = new Map<string, Conversation>()
      for (const local of conversations.value) {
        localMap.set(local.id, local)
      }

      for (const remote of remoteList) {
        const hydratedRemote: Conversation = {
          ...remote,
          messages: (remote.messages || []).map(hydrateMessageFields),
        }
        const existingLocal = localMap.get(remote.id)
        if (!existingLocal || hydratedRemote.updatedAt >= existingLocal.updatedAt) {
          localMap.set(remote.id, hydratedRemote)
          await idbSaveConversation(ownerStorageKey.value, hydratedRemote)
        } else {
          // 本地比服务端更新，主动上推
          void persistConversation(existingLocal)
        }
      }

      const merged = Array.from(localMap.values()).sort(
        (a, b) => b.updatedAt - a.updatedAt,
      )
      conversations.value = merged
      if (merged.length > 0 && !merged.some((c) => c.id === activeConversationId.value)) {
        activeConversationId.value = merged[0].id
      }
    } catch {
      // ignore offline error
    }
  }

  /**
   * 切换无痕临时对话模式（PRD 2.1：仅登录用户可用，游客点击提示需登录）
   */
  function toggleIncognito(triggerEl?: HTMLElement | null): boolean {
    const authStore = useAuthStore()
    if (authStore.isGuest) {
      incognitoNotice.value =
        '无痕临时对话为注册用户专享功能，登录后即可开启不留痕隐私会话。'
      setTimeout(() => {
        if (incognitoNotice.value) incognitoNotice.value = null
      }, 4000)
      return false
    }

    setIncognito(!isIncognito.value, triggerEl)
    return true
  }

  function setIncognito(enabled: boolean, triggerEl?: HTMLElement | null) {
    const configStore = useConfigStore()
    isIncognito.value = enabled

    if (enabled) {
      void configStore.setTheme('incognito', triggerEl)
      incognitoConversation.value = {
        id: createId('incog'),
        title: '无痕隐私对话',
        model: configStore.selectedModel,
        createdAt: Date.now(),
        updatedAt: Date.now(),
        isIncognito: true,
        messages: [],
      }
    } else {
      incognitoConversation.value = null
      if (configStore.theme === 'incognito') {
        void configStore.setTheme(configStore.previousThemeBeforeIncognito, triggerEl)
      }
    }
  }

  /**
   * 切换回答版本分支 (PRD 4.3 视差版本翻页数据源)
   */
  function switchMessageVersion(messageId: string, targetVersionIndex: number) {
    const conv = activeConversation.value
    if (!conv) return
    const msg = conv.messages.find((m) => m.id === messageId)
    if (!msg || !msg.versions || msg.versions.length === 0) return

    const boundedIndex = Math.max(
      0,
      Math.min(msg.versions.length - 1, targetVersionIndex),
    )
    const ver = msg.versions[boundedIndex]
    if (!ver) return

    msg.currentVersionIndex = boundedIndex
    msg.content = ver.content
    msg.thinkingContent = ver.thinkingContent ?? null
    msg.thinkingDurationMs = ver.thinkingDurationMs
    msg.agentSteps = ver.agentSteps
    msg.ragCitations = ver.ragCitations
    msg.model = ver.model
    msg.isMock = ver.isMock
    msg.fallbackReason = ver.fallbackReason
    msg.usage = ver.usage

    conv.updatedAt = Date.now()
    void persistConversation(conv)
  }

  /**
   * 无损历史重试准备 (PRD 4.3)：
   * 不截断、不删除后续对话；标记原提问气泡偏移状态，定位其对应回答消息以追加新版本
   */
  function prepareLosslessRetry(userMessageId: string): {
    promptText: string
    targetAssistantMessageId: string | null
  } | null {
    const conv = activeConversation.value
    if (!conv) return null

    const idx = conv.messages.findIndex((m) => m.id === userMessageId)
    if (idx < 0) return null

    const userMsg = conv.messages[idx]
    userMsg.isRetrying = true
    retryingMessageId.value = userMessageId

    let targetAssistantId: string | null = null
    for (let i = idx + 1; i < conv.messages.length; i++) {
      if (conv.messages[i].role === 'assistant') {
        targetAssistantId = conv.messages[i].id
        break
      }
    }

    retryTargetAssistantId.value = targetAssistantId
    inputDraft.value = userMsg.content

    return {
      promptText: userMsg.content,
      targetAssistantMessageId: targetAssistantId,
    }
  }

  function clearLosslessRetryState(userMessageId?: string) {
    const conv = activeConversation.value
    if (conv) {
      for (const m of conv.messages) {
        if (!userMessageId || m.id === userMessageId) {
          m.isRetrying = false
        }
      }
    }
    retryingMessageId.value = null
  }

  /**
   * 执行无损重试生成：将新回答写入目标 assistant 消息的 versions 新分支（不删除后续消息）
   */
  async function executeLosslessRetry(
    userMessageId: string,
    overridePrompt?: string,
  ): Promise<void> {
    const conv = activeConversation.value
    if (!conv) return

    const idx = conv.messages.findIndex((m) => m.id === userMessageId)
    if (idx < 0) return

    const userMsg = conv.messages[idx]
    if (overridePrompt !== undefined && overridePrompt.trim()) {
      userMsg.content = overridePrompt.trim()
    }

    let assistantMsg: Message | undefined
    for (let i = idx + 1; i < conv.messages.length; i++) {
      if (conv.messages[i].role === 'assistant') {
        assistantMsg = conv.messages[i]
        break
      }
    }

    // 保持原提问气泡左移 150px 与弱化状态 (PRD 4.3)，仅清理底部待发送草稿状态
    userMsg.isRetrying = true
    retryingMessageId.value = null
    inputDraft.value = ''

    if (!assistantMsg) {
      await sendMessage(userMsg.content)
      return
    }

    // 确保现有回答已保存在第 1 个版本中
    if (!assistantMsg.versions || assistantMsg.versions.length === 0) {
      assistantMsg.versions = [
        {
          versionId: `${assistantMsg.id}_v1`,
          content: assistantMsg.content,
          thinkingContent: assistantMsg.thinkingContent ?? null,
          thinkingDurationMs: assistantMsg.thinkingDurationMs,
          agentSteps: assistantMsg.agentSteps,
          ragCitations: assistantMsg.ragCitations,
          model: assistantMsg.model,
          isMock: assistantMsg.isMock,
          fallbackReason: assistantMsg.fallbackReason,
          usage: assistantMsg.usage,
          createdAt: assistantMsg.createdAt,
        },
      ]
    }

    const contextSlice = conv.messages.slice(0, idx + 1)
    await streamIntoAssistantMessage(conv, assistantMsg, contextSlice, true)
  }

  /**
   * 端侧轻量 RAG 本地文档切片与挂载 (PRD 6.2)
   */
  async function addRagDocument(file: File): Promise<Attachment> {
    const text = await file.text()
    const rawParagraphs = text
      .split(/\n{2,}/)
      .map((p) => p.trim())
      .filter((p) => p.length > 10)

    const chunks: RagChunk[] = (rawParagraphs.length > 0 ? rawParagraphs : [text])
      .slice(0, 20)
      .map((content, idx) => ({
        index: activeRagChunks.value.length + idx + 1,
        docName: file.name,
        chunkId: `${file.name}_c${idx + 1}`,
        content: content.slice(0, 600),
        score: Number((0.95 - idx * 0.03).toFixed(2)),
      }))

    const attachment: Attachment = {
      id: createId('doc'),
      name: file.name,
      size: file.size,
      type: file.type || 'text/plain',
      content: text,
      chunksCount: chunks.length,
    }

    ragDocuments.value.push(attachment)
    activeRagChunks.value.push(...chunks)
    return attachment
  }

  function removeRagDocument(docId: string) {
    const doc = ragDocuments.value.find((d) => d.id === docId)
    ragDocuments.value = ragDocuments.value.filter((d) => d.id !== docId)
    if (doc) {
      activeRagChunks.value = activeRagChunks.value.filter(
        (c) => c.docName !== doc.name,
      )
    }
  }

  function retrieveTopRagChunks(query: string, topK = 3): RagChunk[] {
    if (activeRagChunks.value.length === 0) return []
    const terms = query
      .toLowerCase()
      .split(/[\s,，。？！?!;；]+/)
      .filter((t) => t.length >= 2)

    const scored = activeRagChunks.value.map((chunk) => {
      let hitScore = 0.65
      const lower = chunk.content.toLowerCase()
      for (const term of terms) {
        if (lower.includes(term)) {
          hitScore += 0.12
        }
      }
      return {
        ...chunk,
        score: Math.min(0.99, Number(hitScore.toFixed(2))),
      }
    })

    return scored
      .sort((a, b) => b.score - a.score)
      .slice(0, topK)
      .map((c, idx) => ({ ...c, index: idx + 1 }))
  }

  /**
   * 发送新消息并开启 SSE 流式响应
   */
  async function sendMessage(rawContent?: string): Promise<void> {
    const text = (rawContent ?? inputDraft.value).trim()
    if (!text || isGenerating.value) return

    const quotaStore = useQuotaStore()
    const configStore = useConfigStore()

    // 若当前已有待执行的无损重试目标，直接走无损重试分支追加
    if (retryingMessageId.value) {
      await executeLosslessRetry(retryingMessageId.value, text)
      return
    }

    // 1. 本地预校验上下文 Token 上限 (413 CONTEXT_TOO_LONG)
    let conv = activeConversation.value
    if (!conv) {
      conv = createNewConversation(false)
    }

    const historyText = conv.messages.map((m) => m.content).join('\n')
    const estimatedTotalTokens = quotaStore.estimateTokens(
      `${configStore.systemPrompt}\n${historyText}\n${text}`,
    )

    if (
      !quotaStore.unlimited &&
      estimatedTotalTokens > quotaStore.contextTokenLimit
    ) {
      quotaStore.triggerContextTooLong({
        estimatedTokens: estimatedTotalTokens,
        limit: quotaStore.contextTokenLimit,
      })
      return
    }

    // 2. 本地预校验每日调用配额 (429 QUOTA_EXCEEDED)
    if (quotaStore.isQuotaExhausted) {
      quotaStore.triggerQuotaExceeded()
      return
    }

    inputDraft.value = ''

    const now = Date.now()
    const userMsg: Message = {
      id: createId('msg_u'),
      conversationId: conv.id,
      role: 'user',
      content: text,
      attachments: [...ragDocuments.value],
      createdAt: now,
    }

    if (conv.messages.length === 0 && !conv.isIncognito) {
      conv.title = text.slice(0, 28) + (text.length > 28 ? '...' : '')
    }

    conv.messages.push(userMsg)
    conv.updatedAt = now

    const assistantMsg: Message = {
      id: createId('msg_a'),
      conversationId: conv.id,
      role: 'assistant',
      content: '',
      thinkingContent: configStore.enableThinking ? '' : null,
      thinkingDurationMs: 0,
      isThinking: configStore.enableThinking,
      isStreaming: true,
      versions: [],
      currentVersionIndex: 0,
      agentSteps: [],
      ragCitations: [],
      model: configStore.selectedModel,
      isMock: false,
      createdAt: now + 1,
    }

    conv.messages.push(assistantMsg)
    const reactiveAssistant = conv.messages[conv.messages.length - 1]

    await streamIntoAssistantMessage(
      conv,
      reactiveAssistant,
      conv.messages.slice(0, -1),
      false,
    )
  }

  async function streamIntoAssistantMessage(
    conv: Conversation,
    assistantMsg: Message,
    contextMessages: Message[],
    isNewVersionBranch: boolean,
  ): Promise<void> {
    const configStore = useConfigStore()
    const quotaStore = useQuotaStore()

    isGenerating.value = true
    const controller = new AbortController()
    abortController.value = controller

    assistantMsg.isStreaming = true
    assistantMsg.isThinking = configStore.enableThinking
    assistantMsg.content = ''
    assistantMsg.thinkingContent = configStore.enableThinking ? '' : null
    assistantMsg.thinkingDurationMs = 0
    assistantMsg.agentSteps = []
    assistantMsg.ragCitations = []
    assistantMsg.model = configStore.selectedModel

    const requestBody: ChatCompletionRequest = {
      conversationId: conv.id,
      model: configStore.selectedModel,
      messages: [
        { role: 'system', content: configStore.systemPrompt },
        ...contextMessages.map((m) => ({
          role: m.role,
          content: m.content,
        })),
      ],
      enableThinking: configStore.enableThinking,
      enableAgent: configStore.enableAgent,
      temperature: 0.7,
    }

    const startTime = performance.now()

    try {
      if (externalStreamExecutor.value) {
        await externalStreamExecutor.value(
          requestBody,
          assistantMsg,
          controller.signal,
        )
      } else {
        await executeStandardSSEOrFallback(
          requestBody,
          assistantMsg,
          controller.signal,
          startTime,
        )
      }

      // 构建新版本记录
      const newVersion: MessageVersion = {
        versionId: `${assistantMsg.id}_v${(assistantMsg.versions?.length ?? 0) + 1}`,
        content: assistantMsg.content,
        thinkingContent: assistantMsg.thinkingContent ?? null,
        thinkingDurationMs: assistantMsg.thinkingDurationMs,
        agentSteps: assistantMsg.agentSteps ? [...assistantMsg.agentSteps] : [],
        ragCitations: assistantMsg.ragCitations ? [...assistantMsg.ragCitations] : [],
        model: assistantMsg.model,
        isMock: assistantMsg.isMock,
        fallbackReason: assistantMsg.fallbackReason,
        usage: assistantMsg.usage,
        createdAt: Date.now(),
      }

      if (!assistantMsg.versions) {
        assistantMsg.versions = []
      }
      if (isNewVersionBranch || assistantMsg.versions.length === 0) {
        assistantMsg.versions.push(newVersion)
        assistantMsg.currentVersionIndex = assistantMsg.versions.length - 1
      } else {
        assistantMsg.versions[assistantMsg.currentVersionIndex ?? 0] = newVersion
      }

      conv.updatedAt = Date.now()
      await persistConversation(conv)
      await quotaStore.fetchQuotaStatus()
    } catch (err) {
      if ((err as Error)?.name === 'AbortError') {
        assistantMsg.content += '\n\n*[已停止生成]*'
      } else if (!assistantMsg.content) {
        // 若因 429 或 413 被拦截，移除空占位消息
        if (!isNewVersionBranch) {
          conv.messages = conv.messages.filter((m) => m.id !== assistantMsg.id)
        }
      }
    } finally {
      assistantMsg.isStreaming = false
      assistantMsg.isThinking = false
      isGenerating.value = false
      abortController.value = null
    }
  }

  async function executeStandardSSEOrFallback(
    req: ChatCompletionRequest,
    assistantMsg: Message,
    signal: AbortSignal,
    startTime: number,
  ): Promise<void> {
    const quotaStore = useQuotaStore()
    let headers: Record<string, string> = {}
    headers = await buildAuthHeaders({
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    })

    let response: Response
    try {
      response = await fetch('/api/chat/completions', {
        method: 'POST',
        headers,
        body: JSON.stringify(req),
        signal,
      })
    } catch (err) {
      if ((err as Error)?.name === 'AbortError') throw err
      // 网络断开或后端未启动：自动触发内置 Mock 流式兜底 (PRD 3.2 & 7)
      await simulateOfflineMockStream(req, assistantMsg, signal, startTime)
      return
    }

    if (!response.ok) {
      // 若是 502 / 503 / 504 网关异常（如后端尚未启动），降级至本地 Mock
      if (response.status >= 500) {
        await simulateOfflineMockStream(req, assistantMsg, signal, startTime)
        return
      }
      await handleHttpErrorResponse(response)
    }

    if (!response.body) {
      await simulateOfflineMockStream(req, assistantMsg, signal, startTime)
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let currentEvent = 'message'

    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const rawLine of lines) {
        const line = rawLine.trim()
        if (!line) continue

        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          const dataStr = line.slice(5).trim()
          if (!dataStr || dataStr === '[DONE]') continue
          try {
            const parsed = JSON.parse(dataStr) as Record<string, unknown>
            handleParsedSSEEvent(currentEvent, parsed, assistantMsg, startTime)
          } catch {
            // ignore malformed chunk
          }
        }
      }
    }

    quotaStore.recordCallAndTokens(
      assistantMsg.usage?.totalTokens ?? 0,
      Boolean(assistantMsg.isMock),
    )
  }

  function handleParsedSSEEvent(
    eventType: string,
    payload: Record<string, unknown>,
    assistantMsg: Message,
    startTime: number,
  ) {
    if (eventType === 'meta') {
      const meta = payload as unknown as SSEMetaEvent
      assistantMsg.model = meta.model || assistantMsg.model
      assistantMsg.isMock = Boolean(meta.mock)
      assistantMsg.fallbackReason = meta.fallbackReason ?? null
    } else if (eventType === 'agent_step') {
      const step = payload as unknown as AgentStep
      if (!assistantMsg.agentSteps) assistantMsg.agentSteps = []
      const existingIdx = assistantMsg.agentSteps.findIndex(
        (s) => s.stepId === step.stepId,
      )
      if (existingIdx >= 0) {
        assistantMsg.agentSteps[existingIdx] = step
      } else {
        assistantMsg.agentSteps.push(step)
      }
    } else if (eventType === 'thinking') {
      const delta = (payload as unknown as SSEDeltaEvent).delta ?? ''
      assistantMsg.isThinking = true
      assistantMsg.thinkingContent = (assistantMsg.thinkingContent ?? '') + delta
      assistantMsg.thinkingDurationMs = Math.round(performance.now() - startTime)
    } else if (eventType === 'content') {
      const delta = (payload as unknown as SSEDeltaEvent).delta ?? ''
      if (assistantMsg.isThinking) {
        assistantMsg.isThinking = false
        assistantMsg.thinkingDurationMs = Math.round(performance.now() - startTime)
      }
      assistantMsg.content += delta
    } else if (eventType === 'usage') {
      assistantMsg.usage = payload as unknown as SSEUsageEvent
    } else if (eventType === 'error') {
      const err = payload as unknown as SSEErrorEvent
      assistantMsg.content += `\n\n> ⚠️ ${err.message || '流式输出中断'}`
    } else if (eventType === 'done') {
      const _done = payload as unknown as SSEDoneEvent
      assistantMsg.isThinking = false
      assistantMsg.isStreaming = false
      void _done
    }
  }

  async function simulateOfflineMockStream(
    req: ChatCompletionRequest,
    assistantMsg: Message,
    signal: AbortSignal,
    startTime: number,
  ): Promise<void> {
    const quotaStore = useQuotaStore()
    const sleep = (ms: number) =>
      new Promise<void>((resolve, reject) => {
        const timer = setTimeout(resolve, ms)
        signal.addEventListener(
          'abort',
          () => {
            clearTimeout(timer)
            reject(new DOMException('Aborted', 'AbortError'))
          },
          { once: true },
        )
      })

    assistantMsg.isMock = true
    assistantMsg.fallbackReason = 'OFFLINE_OR_NO_API_KEY'

    const userPrompt =
      req.messages[req.messages.length - 1]?.content || '你好'

    if (req.enableAgent) {
      const mockSteps: AgentStep[] = [
        {
          stepId: 'step_plan',
          phase: 'plan',
          title: '任务规划与意图解析',
          toolName: 'planner',
          status: 'completed',
          input: userPrompt,
          output: '拆解为知识检索、指标对比与结构化合成三个阶段',
          durationMs: 92,
        },
        {
          stepId: 'step_tool',
          phase: 'tool',
          title: '白名单工具调用',
          toolName: 'data_analyzer',
          status: 'completed',
          input: `query="${userPrompt.slice(0, 32)}"`,
          output: '返回多维性能基准与架构参数矩阵',
          durationMs: 145,
        },
        {
          stepId: 'step_obs',
          phase: 'observation',
          title: '数据回传与校验',
          toolName: 'validator',
          status: 'completed',
          input: '校验上下文一致性与引用锚点',
          output: '校验通过，置信度 0.96',
          durationMs: 64,
        },
        {
          stepId: 'step_syn',
          phase: 'synthesis',
          title: '总结分析与生成',
          toolName: 'synthesizer',
          status: 'completed',
          input: '聚合推理链与结构化表格',
          output: '开始流式输出最终回答',
          durationMs: 58,
        },
      ]
      for (const step of mockSteps) {
        await sleep(110)
        assistantMsg.agentSteps = [...(assistantMsg.agentSteps ?? []), step]
      }
    }

    if (req.enableThinking) {
      assistantMsg.isThinking = true
      const thoughtChunks = [
        '正在解析用户问题：「',
        userPrompt.slice(0, 36),
        '」...\n',
        '1. 识别核心诉求：结合 XCWeb 科技深邃蓝认知架构给出结构化解答；\n',
        '2. 验证配额、权限边界与状态机规范；\n',
        '3. 组织 Markdown 层次与对比维度，确保结论清晰可操作。',
      ]
      for (const chunk of thoughtChunks) {
        await sleep(80)
        assistantMsg.thinkingContent =
          (assistantMsg.thinkingContent ?? '') + chunk
        assistantMsg.thinkingDurationMs = Math.round(performance.now() - startTime)
      }
      assistantMsg.isThinking = false
    }

    const replyChunks = [
      `针对您的提问 **「${userPrompt.slice(0, 40)}」**，以下是 XCWeb 认知引擎的分析结果：\n\n`,
      `### 核心架构洞察\n`,
      `- **四级角色配额防刷体系**：支持游客（10次/日 · 4K上下文）、普通用户（50次/日 · 16K上下文）、管理员与开发者四级权限管控。\n`,
      `- **全链路流式与平滑降级**：当前处于 **内置 Mock 仿真模式**（\`mock = true\`），无需外网或 API Key 即可完整体验深度思考抽屉、无损历史重试与多版本分支切换。`,
    ]

    for (const chunk of replyChunks) {
      await sleep(70)
      assistantMsg.content += chunk
    }

    assistantMsg.usage = {
      promptTokens: quotaStore.estimateTokens(userPrompt),
      completionTokens: quotaStore.estimateTokens(assistantMsg.content),
      totalTokens:
        quotaStore.estimateTokens(userPrompt) +
        quotaStore.estimateTokens(assistantMsg.content),
      estimated: true,
      mock: true,
    }

    // PRD 2.4: Mock 降级响应不计入 Token 统计，但仍计入调用次数
    quotaStore.recordCallAndTokens(0, true)
  }

  function stopGeneration() {
    if (abortController.value) {
      abortController.value.abort()
      abortController.value = null
    }
    isGenerating.value = false
  }

  function registerCustomStreamExecutor(
    executor: (
      req: ChatCompletionRequest,
      assistantMsg: Message,
      signal: AbortSignal,
    ) => Promise<void>,
  ) {
    externalStreamExecutor.value = executor
  }

  return {
    conversations,
    activeConversationId,
    activeConversation,
    currentMessages,
    isIncognito,
    incognitoConversation,
    incognitoNotice,
    isGenerating,
    retryingMessageId,
    retryTargetAssistantId,
    inputDraft,
    ragDocuments,
    activeRagChunks,
    highlightedRagChunk,
    loadConversations,
    createNewConversation,
    selectConversation,
    deleteConversation,
    persistConversation,
    syncConversationsWithCloud,
    toggleIncognito,
    setIncognito,
    switchMessageVersion,
    prepareLosslessRetry,
    clearLosslessRetryState,
    executeLosslessRetry,
    addRagDocument,
    removeRagDocument,
    retrieveTopRagChunks,
    sendMessage,
    stopGeneration,
    registerCustomStreamExecutor,
  }
})
