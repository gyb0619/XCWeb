import { ref } from 'vue'
import type {
  AgentStep,
  ChatCompletionRequest,
  Message,
  SSEDeltaEvent,
  SSEDoneEvent,
  SSEErrorEvent,
  SSEMetaEvent,
  SSEUsageEvent,
} from '@/types/api'
import { buildAuthHeaders, handleHttpErrorResponse } from '@/utils/apiClient'
import { useQuotaStore } from '@/stores/quotaStore'
import { useAuthStore } from '@/stores/authStore'

/**
 * 客户端 <think>...</think> 标签流式剥离状态机
 * 用于对上游未剥离或夹杂在 content 事件中的 <think> 标签做二次兜底规范化
 */
export interface ThinkStreamNormalizer {
  processContentChunk: (rawDelta: string) => {
    thinkingDelta: string
    contentDelta: string
    inThinkBlock: boolean
  }
  flush: () => {
    thinkingDelta: string
    contentDelta: string
  }
}

export function createThinkTagNormalizer(): ThinkStreamNormalizer {
  let inThinkBlock = false
  let pendingBuffer = ''

  function processContentChunk(rawDelta: string) {
    let thinkingDelta = ''
    let contentDelta = ''
    pendingBuffer += rawDelta

    while (pendingBuffer.length > 0) {
      if (!inThinkBlock) {
        const openIdx = pendingBuffer.indexOf('<think>')
        if (openIdx >= 0) {
          contentDelta += pendingBuffer.slice(0, openIdx)
          pendingBuffer = pendingBuffer.slice(openIdx + '<think>'.length)
          inThinkBlock = true
          continue
        }

        // 检查末尾是否有可能是半个 <think> 标签前缀
        const possiblePrefixMatch = pendingBuffer.match(/<(?:t(?:h(?:i(?:n(?:k)?)?)?)?)?$/)
        if (possiblePrefixMatch && possiblePrefixMatch.index !== undefined) {
          contentDelta += pendingBuffer.slice(0, possiblePrefixMatch.index)
          pendingBuffer = pendingBuffer.slice(possiblePrefixMatch.index)
        } else {
          contentDelta += pendingBuffer
          pendingBuffer = ''
        }
        break
      } else {
        const closeIdx = pendingBuffer.indexOf('</think>')
        if (closeIdx >= 0) {
          thinkingDelta += pendingBuffer.slice(0, closeIdx)
          pendingBuffer = pendingBuffer
            .slice(closeIdx + '</think>'.length)
            .replace(/^\r?\n+/, '')
          inThinkBlock = false
          continue
        }

        // 检查末尾是否有可能是半个 </think> 标签前缀
        const possibleClosePrefix = pendingBuffer.match(
          /<(?:\/(?:t(?:h(?:i(?:n(?:k)?)?)?)?)?)?$/,
        )
        if (possibleClosePrefix && possibleClosePrefix.index !== undefined) {
          thinkingDelta += pendingBuffer.slice(0, possibleClosePrefix.index)
          pendingBuffer = pendingBuffer.slice(possibleClosePrefix.index)
        } else {
          thinkingDelta += pendingBuffer
          pendingBuffer = ''
        }
        break
      }
    }

    return {
      thinkingDelta,
      contentDelta,
      inThinkBlock,
    }
  }

  function flush() {
    const rest = pendingBuffer
    pendingBuffer = ''
    if (inThinkBlock) {
      inThinkBlock = false
      return { thinkingDelta: rest, contentDelta: '' }
    }
    return { thinkingDelta: '', contentDelta: rest }
  }

  return {
    processContentChunk,
    flush,
  }
}

export function useChatStream() {
  const isStreaming = ref<boolean>(false)
  const streamError = ref<string | null>(null)

  /**
   * 核心 SSE 流式对话执行器（挂载至 chatStore.registerCustomStreamExecutor）
   */
  async function executeChatStream(
    req: ChatCompletionRequest,
    assistantMsg: Message,
    signal: AbortSignal,
  ): Promise<void> {
    const quotaStore = useQuotaStore()
    const authStore = useAuthStore()
    isStreaming.value = true
    streamError.value = null

    const startTime = performance.now()

    if (!authStore.token || authStore.token.startsWith('guest_offline_')) {
      try {
        await authStore.loginAsGuest(true)
      } catch {
        // fallback handled below
      }
    }

    let headers = await buildAuthHeaders({
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
      if (response.status === 401 || response.status === 403) {
        await authStore.loginAsGuest(true)
        headers = await buildAuthHeaders({
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        })
        response = await fetch('/api/chat/completions', {
          method: 'POST',
          headers,
          body: JSON.stringify(req),
          signal,
        })
      }
    } catch (err) {
      if ((err as Error)?.name === 'AbortError') {
        isStreaming.value = false
        throw err
      }
      // 网络断开或后端未启动：无缝切换至前端高仿真流式生成器 (PRD 3.2 & 7)
      await runHighFidelityMockStream(req, assistantMsg, signal)
      isStreaming.value = false
      return
    }

    if (!response.ok) {
      // 502 / 503 / 504 等网关错误（如本地开发时后端未启动导致 Vite Proxy 报错）降级至内置 Mock
      if (response.status >= 500) {
        await runHighFidelityMockStream(req, assistantMsg, signal)
        isStreaming.value = false
        return
      }
      // 401 / 403 / 413 / 429 业务与配额拦截直接交由统一错误处理器抛出弹窗
      isStreaming.value = false
      await handleHttpErrorResponse(response)
    }

    if (!response.body) {
      await runHighFidelityMockStream(req, assistantMsg, signal)
      isStreaming.value = false
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    const thinkNormalizer = createThinkTagNormalizer()

    let buffer = ''
    let currentEvent = 'message'
    let hasReceivedValidChunk = false

    try {
      while (true) {
        const { value, done } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split(/\r?\n/)
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
              hasReceivedValidChunk = true
              dispatchSSEEvent(
                currentEvent,
                parsed,
                assistantMsg,
                startTime,
                thinkNormalizer,
              )
            } catch {
              // 兼容非 JSON 纯文本帧
              if (currentEvent === 'thinking') {
                assistantMsg.isThinking = true
                assistantMsg.thinkingContent =
                  (assistantMsg.thinkingContent ?? '') + dataStr
                assistantMsg.thinkingDurationMs = Math.round(
                  performance.now() - startTime,
                )
              } else if (currentEvent === 'content') {
                applyNormalizedContentDelta(
                  dataStr,
                  assistantMsg,
                  startTime,
                  thinkNormalizer,
                )
              }
            }
          }
        }
      }

      // 冲刷残留缓冲区
      const flushed = thinkNormalizer.flush()
      if (flushed.thinkingDelta) {
        assistantMsg.thinkingContent =
          (assistantMsg.thinkingContent ?? '') + flushed.thinkingDelta
      }
      if (flushed.contentDelta) {
        assistantMsg.content += flushed.contentDelta
      }

      // 若流意外为空（上游异常关闭且未发任何片段），平滑降级到 Mock
      if (!hasReceivedValidChunk && !assistantMsg.content) {
        await runHighFidelityMockStream(req, assistantMsg, signal)
        return
      }

      assistantMsg.isThinking = false
      assistantMsg.isStreaming = false

      quotaStore.recordCallAndTokens(
        assistantMsg.usage?.totalTokens ?? 0,
        Boolean(assistantMsg.isMock),
      )
    } finally {
      isStreaming.value = false
    }
  }

  function applyNormalizedContentDelta(
    rawDelta: string,
    assistantMsg: Message,
    startTime: number,
    thinkNormalizer: ThinkStreamNormalizer,
  ) {
    const { thinkingDelta, contentDelta, inThinkBlock } =
      thinkNormalizer.processContentChunk(rawDelta)

    if (thinkingDelta) {
      assistantMsg.isThinking = true
      assistantMsg.thinkingContent =
        (assistantMsg.thinkingContent ?? '') + thinkingDelta
      assistantMsg.thinkingDurationMs = Math.round(performance.now() - startTime)
    }

    if (contentDelta) {
      if (assistantMsg.isThinking && !inThinkBlock) {
        assistantMsg.isThinking = false
        assistantMsg.thinkingDurationMs = Math.round(
          performance.now() - startTime,
        )
      }
      assistantMsg.content += contentDelta
    }
  }

  function dispatchSSEEvent(
    eventType: string,
    payload: Record<string, unknown>,
    assistantMsg: Message,
    startTime: number,
    thinkNormalizer: ThinkStreamNormalizer,
  ) {
    if (eventType === 'meta') {
      const meta = payload as unknown as SSEMetaEvent
      assistantMsg.model = meta.model || assistantMsg.model
      assistantMsg.isMock = Boolean(meta.mock)
      assistantMsg.fallbackReason = meta.fallbackReason ?? null
    } else if (eventType === 'agent_step') {
      const step = payload as unknown as AgentStep
      if (!assistantMsg.agentSteps) {
        assistantMsg.agentSteps = []
      }
      const existingIdx = assistantMsg.agentSteps.findIndex(
        (s) => s.stepId === step.stepId,
      )
      if (existingIdx >= 0) {
        assistantMsg.agentSteps.splice(existingIdx, 1, step)
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
      applyNormalizedContentDelta(delta, assistantMsg, startTime, thinkNormalizer)
    } else if (eventType === 'usage') {
      assistantMsg.usage = payload as unknown as SSEUsageEvent
    } else if (eventType === 'error') {
      const err = payload as unknown as SSEErrorEvent
      streamError.value = err.message || '流式连接中断'
      assistantMsg.content += `\n\n> ⚠️ **流式异常 (${err.code || 'STREAM_ERROR'})**：${err.message || '上游连接中断'}`
    } else if (eventType === 'done') {
      const _done = payload as unknown as SSEDoneEvent
      if (assistantMsg.isThinking) {
        assistantMsg.isThinking = false
        assistantMsg.thinkingDurationMs = Math.round(
          performance.now() - startTime,
        )
      }
      assistantMsg.isStreaming = false
      void _done
    }
  }

  /**
   * 根据用户提问智能匹配后端白名单工具标识（weather_query / web_search / data_analyzer / code_sandbox）
   */
  function selectAllowlistTool(prompt: string): {
    toolName: string
    toolInput: string
    toolOutput: string
  } {
    const lower = prompt.toLowerCase()
    if (lower.includes('天气') || lower.includes('weather') || lower.includes('气温')) {
      return {
        toolName: 'weather_query',
        toolInput: JSON.stringify(
          { location: 'Shanghai / Beijing', metric: 'temperature, AQI, humidity' },
          null,
          2,
        ),
        toolOutput:
          '{"status":"200 OK","shanghai":{"temp":"22°C","condition":"晴转多云","aqi":35},"beijing":{"temp":"19°C","condition":"晴","aqi":42}}',
      }
    }
    if (
      lower.includes('代码') ||
      lower.includes('算法') ||
      lower.includes('计算') ||
      lower.includes('公式') ||
      lower.includes('ts') ||
      lower.includes('java')
    ) {
      return {
        toolName: 'code_sandbox',
        toolInput: JSON.stringify(
          {
            runtime: 'isolated-v8-sandbox',
            task: `验证「${prompt.slice(0, 28)}」复杂度与类型约束`,
          },
          null,
          2,
        ),
        toolOutput:
          '{"exitCode":0,"typeCheck":"0 errors","benchmark":"58.4 FPS steady, P95 latency 14ms"}',
      }
    }
    if (
      lower.includes('趋势') ||
      lower.includes('检索') ||
      lower.includes('最新') ||
      lower.includes('2026')
    ) {
      return {
        toolName: 'web_search',
        toolInput: JSON.stringify(
          {
            query: prompt.slice(0, 42),
            topK: 3,
            filter: 'AI-Native Architecture & Reasoning Models 2026',
          },
          null,
          2,
        ),
        toolOutput:
          '命中 3 篇高置信度技术文献：1.《SSE Streaming & Reasoning UI Spec》 2.《DeepSeek-R1 Distillation Report》 3.《Client-Side Hybrid RAG Benchmark》',
      }
    }
    return {
      toolName: 'data_analyzer',
      toolInput: JSON.stringify(
        {
          dataset: 'xcweb_architecture_benchmark_2026',
          dimensions: ['流式首包延迟', '思维链分离', '配额防刷精度', '帧率稳定性'],
          query: prompt.slice(0, 36),
        },
        null,
        2,
      ),
      toolOutput:
        '聚合完成：生成 4 维架构量化对比矩阵，置信度 0.97，含端侧 RAG 引用映射锚点 [1] [2]',
    }
  }

  /**
   * 内置高仿真流式生成器（断网、无 Key 或后端未启动时的完整闭环兜底）
   * 完整推送：meta (mock=true) -> 4 阶段 agent_step -> 流式 thinking -> 结构化 content (含表格与【引用来源 [1]】) -> usage -> done
   */
  async function runHighFidelityMockStream(
    req: ChatCompletionRequest,
    assistantMsg: Message,
    signal: AbortSignal,
  ): Promise<void> {
    const quotaStore = useQuotaStore()

    const sleep = (ms: number) =>
      new Promise<void>((resolve, reject) => {
        if (signal.aborted) {
          reject(new DOMException('Aborted', 'AbortError'))
          return
        }
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

    const userPrompt =
      req.messages[req.messages.length - 1]?.content ||
      '请分析 2026 年 AI Native Web 架构趋势并给出对比表格'

    // 1. 标记 meta 降级状态 (mock = true)
    assistantMsg.isMock = true
    assistantMsg.fallbackReason = 'OFFLINE_OR_NO_API_KEY'
    assistantMsg.model = req.model || 'deepseek-ai/DeepSeek-R1-Distill-Qwen-7B'

    assistantMsg.ragCitations = []

    // 2. 推送 4 阶段 Agent 可视化思考时间轴（规划 -> 工具调用 -> 数据回传 -> 总结分析）
    const toolMeta = selectAllowlistTool(userPrompt)
    const plannedSteps: Array<Omit<AgentStep, 'status'>> = [
      {
        stepId: 'step_plan_1',
        phase: 'plan',
        title: '规划 (Plan) · 意图拆解',
        toolName: 'planner',
        input: `用户指令：「${userPrompt.slice(0, 60)}」\n启用深度思考：${req.enableThinking ? '是' : '否'}`,
        output:
          '制定 4 步执行计划：① 解析核心问题拓扑 → ② 调用服务端白名单工具采集指标 → ③ 验证状态机与参数空间 → ④ 生成结构化对比表格与结论',
        durationMs: 86,
      },
      {
        stepId: 'step_tool_2',
        phase: 'tool',
        title: '工具调用 (Tool Call)',
        toolName: toolMeta.toolName,
        input: toolMeta.toolInput,
        output: toolMeta.toolOutput,
        durationMs: 142,
      },
      {
        stepId: 'step_obs_3',
        phase: 'observation',
        title: '数据回传 (Data Return)',
        toolName: 'context_merger',
        input: `合并工具回传数据与系统状态，完成技术指标校验`,
        output: `状态机交叉验证完成，已锁定结构化对比关键维度`,
        durationMs: 68,
      },
      {
        stepId: 'step_syn_4',
        phase: 'synthesis',
        title: '总结分析 (Synthesis)',
        toolName: 'synthesizer',
        input: '构建多维 Markdown 结构化对比矩阵与架构建议',
        output: '结构化合成完毕，准备开启思维链与正文流式输出',
        durationMs: 54,
      },
    ]

    assistantMsg.agentSteps = []
    for (const rawStep of plannedSteps) {
      const runningStep: AgentStep = {
        ...rawStep,
        status: 'running',
        durationMs: 0,
      }
      assistantMsg.agentSteps = [...assistantMsg.agentSteps, runningStep]
      await sleep(95)

      const completedStep: AgentStep = {
        ...rawStep,
        status: 'completed',
      }
      assistantMsg.agentSteps = assistantMsg.agentSteps.map((s) =>
        s.stepId === completedStep.stepId ? completedStep : s,
      )
      await sleep(55)
    }

    // 3. 流式推送深度思考 (thinking) 增量片段
    const thinkingStart = performance.now()
    assistantMsg.isThinking = true
    assistantMsg.thinkingContent = ''

    const thoughtSegments = [
      `正在深度拆解用户提问：「${userPrompt.slice(0, 42)}」...\n`,
      `1. [意图与上下文对齐]：识别到核心技术诉求，已完成问题拓扑与多维对比参数校验。\n`,
      `2. [架构与推理范式对比]：对比传统单轮套壳 Web 应用与 XCWeb 2026 AI-Native 架构在流式协议（SSE 多事件通道）、深度思考抽屉状态机、四级角色三重防刷配额上的本质差异。\n`,
      `3. [安全与可观测性验证]：确认所有工具调用均通过后端白名单（${toolMeta.toolName}）完成，API Key 零前端暴露，未配置外网 Key 时平滑切换至 Mock 引擎。\n`,
      `4. [输出排版规划]：生成结构化 Markdown 对比表格（支持联动 ECharts 柱状图/折线图渲染）。`,
    ]

    for (const seg of thoughtSegments) {
      // 拆分为更细腻的 token 片段以呈现顺滑的实时计时与打字流
      const mid = Math.ceil(seg.length / 2)
      const partA = seg.slice(0, mid)
      const partB = seg.slice(mid)

      await sleep(110)
      assistantMsg.thinkingContent =
        (assistantMsg.thinkingContent ?? '') + partA
      assistantMsg.thinkingDurationMs = Math.round(
        performance.now() - thinkingStart,
      )

      await sleep(110)
      assistantMsg.thinkingContent =
        (assistantMsg.thinkingContent ?? '') + partB
      assistantMsg.thinkingDurationMs = Math.round(
        performance.now() - thinkingStart,
      )
    }

    // 思考完成：切换 isThinking = false，触发 ThinkingBlock 绿色微光对勾与正文 600ms slideUp
    assistantMsg.thinkingDurationMs = Math.max(
      1100,
      Math.round(performance.now() - thinkingStart),
    )
    assistantMsg.isThinking = false
    await sleep(80)

    // 4. 流式推送结构化正文 content（含 Markdown 对比表格）
    const answerSegments = [
      `针对您的提问 **「${userPrompt.slice(0, 48)}」**，结合 Agent 工具链与技术规范分析，为您呈现结构化洞察：\n\n`,
      `### 一、核心架构演进与机制解析\n\n`,
      `1. **全链路流式状态机与深度思考解耦**：XCWeb 通过 \`POST /api/chat/completions\` 建立标准化 SSE 管道，将上游推理模型的 \`<think>\` 标签与 \`reasoning_content\` 统一规范化为 \`thinking\` 事件，在前端独立思考抽屉中实时计时，并在思考完成瞬间以 **600ms \`slideUp\`** 动效平滑托出正文。\n`,
      `2. **四级角色权限与游客三重防刷矩阵**：系统对游客实施 **匿名签名令牌 (\`guestId\`) + 浏览器指纹 (\`X-Fingerprint\`) + 客户端 IP** 三重独立计数拦截（游客 10次/日 · 4K Tokens，普通用户 50次/日 · 16K Tokens），兼顾零门槛体验与算力安全。\n`,
      `3. **极简二级菜单与交互规范**：采用沉浸式二级功能浮层与二级折叠结构，减少一级视觉干扰，让会话历史与对话内容成为核心视觉焦点。\n\n`,
      `### 二、2026 AI-Native 认知架构量化对比表\n\n`,
      `| 评估维度 | 传统聊天套壳方案 | XCWeb AI-Native 平台 | 核心性能提升 |\n`,
      `| :--- | :--- | :--- | :--- |\n`,
      `| 首包流式响应延迟 | 1200ms | 180ms | 85% |\n`,
      `| 深度思考抽屉与微动效帧率 | 30 FPS | 60 FPS | 100% |\n`,
      `| 配额防刷拦截准确率 | 55% | 99% | 44% |\n`,
      `| 交互层级清晰度 | 单层堆叠臃肿 | 二级结构收拢 | 显著提升 |\n\n`,
      `> 💡 **交互提示**：您可以在下方的 **Generative UI 卡片** 中一键切换交互式筛选表格、科技蓝柱状图与折线图。`,
    ]

    for (const seg of answerSegments) {
      await sleep(65)
      assistantMsg.content += seg
    }

    // 5. 推送末尾 usage 统计
    const promptTokens = quotaStore.estimateTokens(userPrompt) + 128
    const completionTokens = quotaStore.estimateTokens(
      (assistantMsg.thinkingContent ?? '') + assistantMsg.content,
    )
    assistantMsg.usage = {
      promptTokens,
      completionTokens,
      totalTokens: promptTokens + completionTokens,
      estimated: true,
      mock: true,
    }

    // PRD 2.4: Mock 降级响应计入调用次数，不计入真实 Token 消耗
    quotaStore.recordCallAndTokens(0, true)
  }

  return {
    isStreaming,
    streamError,
    executeChatStream,
  }
}
