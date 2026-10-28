<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import * as echarts from 'echarts'
import {
  Terminal,
  X,
  RefreshCw,
  Activity,
  FileCode,
  FlaskConical,
  GitBranch,
  Download,
  Play,
  Search,
  CheckCircle2,
  Clock,
  Cpu,
  ShieldAlert,
} from 'lucide-vue-next'
import { useConfigStore } from '@/stores/configStore'
import { useChatStore } from '@/stores/chatStore'
import { useQuotaStore } from '@/stores/quotaStore'
import { apiRequest, buildAuthHeaders } from '@/utils/apiClient'
import type { AgentStep, DevMetrics, RequestLogItem } from '@/types/api'

const configStore = useConfigStore()
const chatStore = useChatStore()
const quotaStore = useQuotaStore()

type DevTab = 'requests' | 'heatmap' | 'sandbox' | 'agent_trace'

const activeTab = ref<DevTab>('requests')
const isLoading = ref<boolean>(false)

// Tab 1: 原始 API 请求审计筛选状态
const filterModel = ref<string>('ALL')
const filterMockMode = ref<'ALL' | 'REAL' | 'MOCK'>('ALL')
const searchLogKeyword = ref<string>('')

// ECharts 实例引用 (Tab 2)
const throughputChartRef = ref<HTMLElement | null>(null)
const heatmapChartRef = ref<HTMLElement | null>(null)
let throughputChart: echarts.ECharts | null = null
let heatmapChart: echarts.ECharts | null = null

const DAYS_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const HOURS_LABELS = Array.from({ length: 24 }, (_, i) => `${String(i).padStart(2, '0')}:00`)

function generateDefaultHeatmap(): Array<[number, number, number]> {
  const cells: Array<[number, number, number]> = []
  for (let d = 0; d < 7; d++) {
    for (let h = 0; h < 24; h++) {
      const isPeak = h >= 9 && h <= 22
      const base = isPeak ? 1200 : 180
      const wave = ((d * 7 + h * 13) % 9) * (isPeak ? 420 : 65)
      cells.push([h, d, base + wave])
    }
  }
  return cells
}

const metrics = ref<DevMetrics>({
  totalRequests: 312,
  avgLatencyMs: 618,
  mockFallbackRate: 0.16,
  throughputSeries: [
    { timestamp: '10:00', requestsPerMin: 14, tokensPerMin: 5400 },
    { timestamp: '11:00', requestsPerMin: 22, tokensPerMin: 8900 },
    { timestamp: '12:00', requestsPerMin: 18, tokensPerMin: 7100 },
    { timestamp: '13:00', requestsPerMin: 26, tokensPerMin: 11200 },
    { timestamp: '14:00', requestsPerMin: 31, tokensPerMin: 14600 },
    { timestamp: '15:00', requestsPerMin: 24, tokensPerMin: 9800 },
    { timestamp: '16:00', requestsPerMin: 29, tokensPerMin: 12400 },
  ],
  tokenHeatmap: generateDefaultHeatmap(),
  modelDistribution: [
    { model: 'deepseek-ai/DeepSeek-R1-Distill-Qwen-7B', count: 186, tokens: 78400 },
    { model: 'glm-4-flash', count: 58, tokens: 24600 },
    { model: 'llama-3.3-70b-versatile', count: 32, tokens: 15200 },
    { model: 'mock-cognitive-core', count: 36, tokens: 0 },
  ],
})

const requestLogs = ref<RequestLogItem[]>([
  {
    id: 108,
    identifier: 'user:101',
    model: 'deepseek-ai/DeepSeek-R1-Distill-Qwen-7B',
    isMock: false,
    promptTokens: 182,
    completionTokens: 524,
    latencyMs: 685,
    status: '200_OK',
    createdAt: Date.now() - 45000,
  },
  {
    id: 107,
    identifier: 'guest:guest_9f8a7b',
    model: 'mock-cognitive-core',
    isMock: true,
    promptTokens: 112,
    completionTokens: 340,
    latencyMs: 290,
    status: '200_MOCK_FALLBACK',
    createdAt: Date.now() - 125000,
  },
  {
    id: 106,
    identifier: 'user:101',
    model: 'glm-4-flash',
    isMock: false,
    promptTokens: 264,
    completionTokens: 610,
    latencyMs: 510,
    status: '200_OK',
    createdAt: Date.now() - 240000,
  },
  {
    id: 105,
    identifier: 'fp:e3b0c44298fc',
    model: 'llama-3.3-70b-versatile',
    isMock: false,
    promptTokens: 140,
    completionTokens: 390,
    latencyMs: 420,
    status: '200_OK',
    createdAt: Date.now() - 390000,
  },
])

const availableModelsInLogs = computed<string[]>(() => {
  const set = new Set<string>()
  for (const item of requestLogs.value) {
    set.add(item.model)
  }
  return Array.from(set)
})

const filteredRequestLogs = computed<RequestLogItem[]>(() => {
  const q = searchLogKeyword.value.trim().toLowerCase()
  return requestLogs.value.filter((item) => {
    if (filterModel.value !== 'ALL' && item.model !== filterModel.value) {
      return false
    }
    if (filterMockMode.value === 'REAL' && item.isMock) return false
    if (filterMockMode.value === 'MOCK' && !item.isMock) return false
    if (q) {
      const hay = `${item.id} ${item.identifier} ${item.model} ${item.status}`.toLowerCase()
      if (!hay.includes(q)) return false
    }
    return true
  })
})

// Tab 3: Prompt 调优沙箱 (Prompt Sandbox) 状态
const sandboxPromptA = ref<string>(
  '你是 XCWeb 架构顾问，请用精炼的要点列出核心方案，控制在 120 字以内。',
)
const sandboxPromptB = ref<string>(
  '你是资深全栈系统架构师，请从安全边界、性能指标与降级策略三个维度展开深入对比分析。',
)
const sandboxUserQuery = ref<string>(
  '如何设计面向免登录游客与注册用户的 AI 对话配额防刷体系？',
)
const sandboxTemperature = ref<number>(0.6)
const isRunningSandbox = ref<boolean>(false)

interface SandboxResult {
  output: string
  promptTokens: number
  completionTokens: number
  latencyMs: number
  isMock: boolean
}

const sandboxResultA = ref<SandboxResult | null>(null)
const sandboxResultB = ref<SandboxResult | null>(null)

async function runSingleSandboxVariant(
  sysPrompt: string,
  userPrompt: string,
  temp: number,
  variantLabel: 'A' | 'B',
): Promise<SandboxResult> {
  const start = performance.now()
  const pTokens = quotaStore.estimateTokens(`${sysPrompt}\n${userPrompt}`)

  try {
    const headers = await buildAuthHeaders({
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    })
    const res = await fetch('/api/chat/completions', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        conversationId: `sandbox_${Date.now()}_${variantLabel}`,
        model: configStore.selectedModel,
        messages: [
          { role: 'system', content: sysPrompt },
          { role: 'user', content: userPrompt },
        ],
        enableThinking: false,
        enableAgent: false,
        temperature: temp,
      }),
    })

    if (res.ok && res.body) {
      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buf = ''
      let contentAcc = ''
      let isMockFlag = false

      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        const lines = buf.split('\n')
        buf = lines.pop() ?? ''
        for (const raw of lines) {
          const line = raw.trim()
          if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim()
            if (!dataStr || dataStr === '[DONE]') continue
            try {
              const obj = JSON.parse(dataStr) as Record<string, unknown>
              if (typeof obj.mock === 'boolean') isMockFlag = obj.mock
              if (typeof obj.delta === 'string') contentAcc += obj.delta
            } catch {
              // ignore
            }
          }
        }
      }

      if (contentAcc.trim()) {
        return {
          output: contentAcc.trim(),
          promptTokens: pTokens,
          completionTokens: quotaStore.estimateTokens(contentAcc),
          latencyMs: Math.round(performance.now() - start),
          isMock: isMockFlag,
        }
      }
    }
  } catch {
    // 离线或沙箱快速仿真兜底
  }

  await new Promise((r) => setTimeout(r, variantLabel === 'A' ? 240 : 340))
  const mockOutput =
    variantLabel === 'A'
      ? `【精炼要点方案 (Temp=${temp})】\n1. 游客三重校验：匿名令牌 + SHA-256 指纹 + 客户端 IP；\n2. 阶梯配额：游客 10次/4K，注册用户 50次/16K；\n3. 每日 00:00 自然日自动重置，超额返回 429 引导升级。`
      : `【多维深度架构分析 (Temp=${temp})】\n- **安全防刷边界**：采用服务端签名匿名令牌绑定浏览器 Canvas/WebGL 指纹哈希，并叠加单 IP 阈值（30次/日）与每日令牌签发上限（5个/日）；\n- **性能与计量规范**：请求前预估上下文（超限返 413 不扣次数），流结束后按上游实际 usage 落库；\n- **高可用降级策略**：无 Key 或上游超时自动切换内置 Mock 引擎并标记 mock=true。`

  return {
    output: mockOutput,
    promptTokens: pTokens,
    completionTokens: quotaStore.estimateTokens(mockOutput),
    latencyMs: Math.round(performance.now() - start),
    isMock: true,
  }
}

async function handleRunSandboxComparison() {
  if (isRunningSandbox.value || !sandboxUserQuery.value.trim()) return
  isRunningSandbox.value = true
  try {
    const [resA, resB] = await Promise.all([
      runSingleSandboxVariant(
        sandboxPromptA.value,
        sandboxUserQuery.value,
        sandboxTemperature.value,
        'A',
      ),
      runSingleSandboxVariant(
        sandboxPromptB.value,
        sandboxUserQuery.value,
        sandboxTemperature.value,
        'B',
      ),
    ])
    sandboxResultA.value = resA
    sandboxResultB.value = resB
  } finally {
    isRunningSandbox.value = false
  }
}

// Tab 4: Agent 决策追踪 (Agent Decision Trace)
interface TracedMessageRecord {
  messageId: string
  model: string
  createdAt: number
  totalDurationMs: number
  steps: AgentStep[]
}

const fallbackDemoTrace: TracedMessageRecord[] = [
  {
    messageId: 'trace_demo_01',
    model: 'deepseek-ai/DeepSeek-R1-Distill-Qwen-7B',
    createdAt: Date.now() - 120000,
    totalDurationMs: 359,
    steps: [
      {
        stepId: 's_plan',
        phase: 'plan',
        title: '任务规划与意图解析',
        toolName: 'planner',
        status: 'completed',
        input: '分析 2026 AI Native Web 架构趋势并生成对比矩阵',
        output: '拆解为：1. 架构文献检索 -> 2. 性能数据聚合 -> 3. 结构化表格合成',
        durationMs: 92,
      },
      {
        stepId: 's_tool',
        phase: 'tool',
        title: '白名单工具调用',
        toolName: 'data_analyzer',
        status: 'completed',
        input: '{"metrics":["FCP","FPS","TokenThroughput"],"year":2026}',
        output: '返回 FCP <= 1.2s, 动效稳态 58 FPS, 流式首包 180ms 基准样本',
        durationMs: 145,
      },
      {
        stepId: 's_obs',
        phase: 'observation',
        title: '数据回传与上下文校验',
        toolName: 'validator',
        status: 'completed',
        input: '验证端侧 RAG 切片与工具回传一致性',
        output: '置信度评分 0.96，无幻觉冲突',
        durationMs: 64,
      },
      {
        stepId: 's_syn',
        phase: 'synthesis',
        title: '总结分析与生成式 UI 触发',
        toolName: 'synthesizer',
        status: 'completed',
        input: '组装 GFM 对比表格与思维链摘要',
        output: '流式输出完成，已激活 GenerativeUIRenderer',
        durationMs: 58,
      },
    ],
  },
]

const agentTraceRecords = computed<TracedMessageRecord[]>(() => {
  const fromSession: TracedMessageRecord[] = []
  for (const msg of chatStore.currentMessages) {
    if (msg.role === 'assistant' && msg.agentSteps && msg.agentSteps.length > 0) {
      const total = msg.agentSteps.reduce((acc, s) => acc + (s.durationMs || 0), 0)
      fromSession.push({
        messageId: msg.id,
        model: msg.model || configStore.selectedModel,
        createdAt: msg.createdAt,
        totalDurationMs: total || 1,
        steps: msg.agentSteps,
      })
    }
  }
  return fromSession.length > 0 ? fromSession : fallbackDemoTrace
})

async function loadDevData() {
  isLoading.value = true
  try {
    const [mRes, rRes] = await Promise.allSettled([
      apiRequest<DevMetrics>('/api/dev/metrics'),
      apiRequest<RequestLogItem[] | { requests?: RequestLogItem[] }>(
        '/api/dev/requests?limit=50',
      ),
    ])

    if (mRes.status === 'fulfilled' && mRes.value) {
      const incoming = mRes.value
      metrics.value = {
        ...incoming,
        tokenHeatmap:
          incoming.tokenHeatmap && incoming.tokenHeatmap.length > 10
            ? incoming.tokenHeatmap
            : generateDefaultHeatmap(),
      }
    }
    if (rRes.status === 'fulfilled' && rRes.value) {
      const val = rRes.value
      const list = Array.isArray(val) ? val : (val.requests ?? [])
      if (list.length > 0) requestLogs.value = list
    }
  } finally {
    isLoading.value = false
    if (activeTab.value === 'heatmap') {
      void renderMetricsCharts()
    }
  }
}

function renderMetricsCharts() {
  void nextTick(() => {
    const isLight = configStore.theme === 'light'
    const textColor = isLight ? '#334155' : '#CBD5E1'
    const gridBorder = isLight ? 'rgba(15,23,42,0.08)' : 'rgba(148,163,184,0.12)'

    if (throughputChartRef.value) {
      if (!throughputChart) {
        throughputChart = echarts.init(throughputChartRef.value)
      }
      const seriesData = metrics.value.throughputSeries
      throughputChart.setOption({
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        legend: {
          top: 0,
          textStyle: { color: textColor, fontSize: 11 },
        },
        grid: { top: 32, left: 12, right: 12, bottom: 8, containLabel: true },
        xAxis: {
          type: 'category',
          data: seriesData.map((s) => s.timestamp),
          axisLabel: { color: textColor, fontSize: 10 },
        },
        yAxis: [
          {
            type: 'value',
            name: 'Tokens/min',
            axisLabel: { color: textColor, fontSize: 10 },
            splitLine: { lineStyle: { color: gridBorder, type: 'dashed' } },
          },
          {
            type: 'value',
            name: 'Req/min',
            axisLabel: { color: textColor, fontSize: 10 },
            splitLine: { show: false },
          },
        ],
        series: [
          {
            name: 'Token 吞吐 (Tokens/min)',
            type: 'line',
            smooth: true,
            data: seriesData.map((s) => s.tokensPerMin),
            lineStyle: { width: 2.5, color: '#3B82F6' },
            itemStyle: { color: '#2563EB' },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(59,130,246,0.35)' },
                { offset: 1, color: 'rgba(37,99,235,0.02)' },
              ]),
            },
          },
          {
            name: '请求速率 (Req/min)',
            type: 'bar',
            yAxisIndex: 1,
            barMaxWidth: 16,
            data: seriesData.map((s) => s.requestsPerMin),
            itemStyle: {
              color: '#06B6D4',
              borderRadius: [4, 4, 0, 0],
            },
          },
        ],
      })
      throughputChart.resize()
    }

    if (heatmapChartRef.value) {
      if (!heatmapChart) {
        heatmapChart = echarts.init(heatmapChartRef.value)
      }
      const normalizedData = metrics.value.tokenHeatmap.map((item) => {
        // 兼容 [day, hour, tokens] 或 [hour, day, tokens]
        const first = item[0] ?? 0
        const second = item[1] ?? 0
        const val = item[2] ?? 0
        if (first < 7 && second >= 7) {
          return [second, first, val]
        }
        return [first, second % 7, val]
      })

      heatmapChart.setOption({
        backgroundColor: 'transparent',
        tooltip: {
          position: 'top',
          formatter: (params: unknown) => {
            const p = params as { value: [number, number, number] }
            const h = HOURS_LABELS[p.value[0]] ?? `${p.value[0]}:00`
            const d = DAYS_LABELS[p.value[1]] ?? ''
            return `${d} ${h}<br/>Token 消耗: <strong>${p.value[2].toLocaleString()}</strong>`
          },
        },
        grid: { top: 12, left: 12, right: 16, bottom: 28, containLabel: true },
        xAxis: {
          type: 'category',
          data: HOURS_LABELS,
          splitArea: { show: true },
          axisLabel: { color: textColor, fontSize: 10, interval: 2 },
        },
        yAxis: {
          type: 'category',
          data: DAYS_LABELS,
          splitArea: { show: true },
          axisLabel: { color: textColor, fontSize: 10 },
        },
        visualMap: {
          min: 0,
          max: 5000,
          calculable: false,
          orient: 'horizontal',
          left: 'center',
          bottom: 0,
          itemWidth: 10,
          itemHeight: 80,
          textStyle: { color: textColor, fontSize: 10 },
          inRange: {
            color: ['#0F172A', '#1E3A8A', '#2563EB', '#3B82F6', '#06B6D4'],
          },
        },
        series: [
          {
            name: 'Token 消耗热力图',
            type: 'heatmap',
            data: normalizedData,
            label: { show: false },
          },
        ],
      })
      heatmapChart.resize()
    }
  })
}

/**
 * 导出脱敏 JSON 调试数据快照 (PRD 2.1: 支持导出调试数据，严禁包含任何密钥与口令)
 */
function exportDebugSnapshotJson() {
  const snapshot = {
    exportedAt: new Date().toISOString(),
    platform: 'XCWeb Dev Console v1.0',
    securityNotice: 'REDACTED_SNAPSHOT_NO_SECRETS_OR_KEYS',
    selectedModel: configStore.selectedModel,
    theme: configStore.theme,
    metrics: metrics.value,
    recentRequestsRedacted: filteredRequestLogs.value,
    agentDecisionTraces: agentTraceRecords.value,
    promptSandboxComparison: {
      userQuery: sandboxUserQuery.value,
      temperature: sandboxTemperature.value,
      variantA: {
        systemPrompt: sandboxPromptA.value,
        result: sandboxResultA.value,
      },
      variantB: {
        systemPrompt: sandboxPromptB.value,
        result: sandboxResultB.value,
      },
    },
  }

  const blob = new Blob([JSON.stringify(snapshot, null, 2)], {
    type: 'application/json;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `xcweb_dev_snapshot_${Date.now()}.json`
  a.click()
  URL.revokeObjectURL(url)
}

function handleResize() {
  throughputChart?.resize()
  heatmapChart?.resize()
}

watch(
  () => configStore.showDevConsoleModal,
  (open) => {
    if (open) {
      void loadDevData()
    }
  },
)

watch(activeTab, (tab) => {
  if (tab === 'heatmap') {
    void renderMetricsCharts()
  }
})

onMounted(() => {
  window.addEventListener('resize', handleResize)
  if (configStore.showDevConsoleModal) {
    void loadDevData()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  throughputChart?.dispose()
  heatmapChart?.dispose()
})
</script>

<template>
  <div
    v-if="configStore.showDevConsoleModal"
    class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-md"
  >
    <div
      class="w-full max-w-5xl max-h-[88vh] flex flex-col rounded-2xl glass-modal border border-indigo-500/40 overflow-hidden animate-slide-up"
    >
      <!-- 顶部标题栏 + 导出 JSON 调试数据按钮 -->
      <div class="flex flex-wrap items-center justify-between gap-3 px-6 py-4 border-b border-subtle">
        <div class="flex items-center gap-2.5">
          <div
            class="w-9 h-9 rounded-xl bg-indigo-500/20 border border-indigo-500/40 flex items-center justify-center text-indigo-300"
          >
            <Terminal class="w-5 h-5" />
          </div>
          <div>
            <h2 class="text-sm font-bold text-main flex items-center gap-2">
              <span>开发者调试与可观测性工作台 (Dev Console)</span>
              <span
                class="text-[10px] font-mono px-2 py-0.5 rounded bg-indigo-500/20 text-indigo-300 border border-indigo-500/30"
              >
                ROLE = DEV
              </span>
            </h2>
            <p class="text-xs text-muted">
              原始脱敏 API 请求审计 · 7×24h Token 热力图 · Prompt 调优沙箱 · Agent 决策链追踪与快照导出
            </p>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <button
            type="button"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-indigo-500/20 hover:bg-indigo-500/30 border border-indigo-400/40 text-indigo-200 text-xs font-medium transition-colors"
            @click="exportDebugSnapshotJson"
          >
            <Download class="w-3.5 h-3.5" />
            <span>导出 JSON 调试快照</span>
          </button>

          <button
            type="button"
            class="p-2 rounded-lg text-sub hover:text-main hover:bg-elevated"
            title="刷新遥测数据"
            @click="loadDevData"
          >
            <RefreshCw class="w-4 h-4" :class="isLoading ? 'animate-spin' : ''" />
          </button>

          <button
            type="button"
            class="p-2 rounded-lg text-sub hover:text-main hover:bg-elevated"
            @click="configStore.showDevConsoleModal = false"
          >
            <X class="w-4 h-4" />
          </button>
        </div>
      </div>

      <!-- 四个核心功能区 Tab 导航 -->
      <div class="flex items-center gap-1 px-6 pt-2.5 border-b border-subtle bg-elevated/30 overflow-x-auto">
        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 text-xs font-semibold border-b-2 transition-colors whitespace-nowrap"
          :class="
            activeTab === 'requests'
              ? 'border-indigo-400 text-indigo-300'
              : 'border-transparent text-sub hover:text-main'
          "
          @click="activeTab = 'requests'"
        >
          <FileCode class="w-3.5 h-3.5" />
          <span>1. 原始 API 请求审计 (/api/dev/requests)</span>
        </button>

        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 text-xs font-semibold border-b-2 transition-colors whitespace-nowrap"
          :class="
            activeTab === 'heatmap'
              ? 'border-indigo-400 text-indigo-300'
              : 'border-transparent text-sub hover:text-main'
          "
          @click="activeTab = 'heatmap'"
        >
          <Activity class="w-3.5 h-3.5" />
          <span>2. Token 消耗热力图与吞吐监控 (/api/dev/metrics)</span>
        </button>

        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 text-xs font-semibold border-b-2 transition-colors whitespace-nowrap"
          :class="
            activeTab === 'sandbox'
              ? 'border-indigo-400 text-indigo-300'
              : 'border-transparent text-sub hover:text-main'
          "
          @click="activeTab = 'sandbox'"
        >
          <FlaskConical class="w-3.5 h-3.5" />
          <span>3. Prompt 调优沙箱 (Prompt Sandbox)</span>
        </button>

        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 text-xs font-semibold border-b-2 transition-colors whitespace-nowrap"
          :class="
            activeTab === 'agent_trace'
              ? 'border-indigo-400 text-indigo-300'
              : 'border-transparent text-sub hover:text-main'
          "
          @click="activeTab = 'agent_trace'"
        >
          <GitBranch class="w-3.5 h-3.5" />
          <span>4. Agent 决策追踪 (Agent Decision Trace)</span>
        </button>
      </div>

      <!-- 主内容滚动区 -->
      <div class="flex-1 overflow-y-auto p-6 space-y-5">
        <!-- ==================== Tab 1: 原始 API 请求审计 ==================== -->
        <template v-if="activeTab === 'requests'">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div class="flex items-center flex-wrap gap-2 text-xs">
              <div class="relative">
                <Search class="w-3.5 h-3.5 text-muted absolute left-3 top-1/2 -translate-y-1/2" />
                <input
                  v-model="searchLogKeyword"
                  type="text"
                  placeholder="搜索请求 ID / 标识 / 模型..."
                  class="pl-8 pr-3 py-1.5 rounded-xl bg-elevated/70 border border-subtle text-xs text-main placeholder-muted focus:outline-none focus:border-indigo-400"
                />
              </div>

              <select
                v-model="filterModel"
                class="px-3 py-1.5 rounded-xl bg-elevated/70 border border-subtle text-xs text-main focus:outline-none"
              >
                <option value="ALL">全部预置模型</option>
                <option v-for="m in availableModelsInLogs" :key="m" :value="m">
                  {{ m }}
                </option>
              </select>

              <select
                v-model="filterMockMode"
                class="px-3 py-1.5 rounded-xl bg-elevated/70 border border-subtle text-xs text-main focus:outline-none"
              >
                <option value="ALL">全部响应模式</option>
                <option value="REAL">真实上游调用</option>
                <option value="MOCK">Mock 降级响应</option>
              </select>
            </div>

            <div class="flex items-center gap-1.5 text-[11px] text-emerald-400">
              <ShieldAlert class="w-3.5 h-3.5" />
              <span>已执行脱敏过滤：不含 API Key、密码或 JWT 原文</span>
            </div>
          </div>

          <div class="rounded-xl border border-subtle overflow-hidden">
            <table class="w-full text-left text-xs">
              <thead class="bg-elevated/50 text-muted border-b border-subtle">
                <tr>
                  <th class="py-2.5 px-3">请求 ID</th>
                  <th class="py-2.5 px-3">调用方标识 (Identifier)</th>
                  <th class="py-2.5 px-3">目标模型</th>
                  <th class="py-2.5 px-3">Prompt / Completion</th>
                  <th class="py-2.5 px-3">端到端延迟</th>
                  <th class="py-2.5 px-3">模式与状态</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-subtle/60 font-mono text-[11px]">
                <tr
                  v-for="log in filteredRequestLogs"
                  :key="log.id"
                  class="hover:bg-elevated/30 transition-colors"
                >
                  <td class="py-2.5 px-3 text-muted">#{{ log.id }}</td>
                  <td class="py-2.5 px-3 text-sub">{{ log.identifier }}</td>
                  <td class="py-2.5 px-3 text-main">{{ log.model }}</td>
                  <td class="py-2.5 px-3 text-cyber-cyan">
                    {{ log.promptTokens }} / {{ log.completionTokens }} Tokens
                  </td>
                  <td class="py-2.5 px-3 text-sub">{{ log.latencyMs }} ms</td>
                  <td class="py-2.5 px-3">
                    <span
                      class="px-2 py-0.5 rounded text-[10px]"
                      :class="
                        log.isMock
                          ? 'bg-amber-500/15 text-amber-400 border border-amber-500/30'
                          : 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
                      "
                    >
                      {{ log.isMock ? 'MOCK_FALLBACK' : log.status }}
                    </span>
                  </td>
                </tr>
                <tr v-if="filteredRequestLogs.length === 0">
                  <td colspan="6" class="py-8 text-center text-muted">
                    暂无匹配的 API 请求审计记录
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>

        <!-- ==================== Tab 2: Token 消耗热力图与吞吐监控 ==================== -->
        <template v-else-if="activeTab === 'heatmap'">
          <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div class="p-3.5 rounded-xl bg-elevated/50 border border-subtle">
              <div class="text-[11px] text-muted">累计 API 请求总数</div>
              <div class="text-xl font-bold font-mono text-main mt-1">
                {{ metrics.totalRequests.toLocaleString() }}
              </div>
            </div>
            <div class="p-3.5 rounded-xl bg-elevated/50 border border-subtle">
              <div class="text-[11px] text-muted">平均端到端响应延迟</div>
              <div class="text-xl font-bold font-mono text-cyber-cyan mt-1">
                {{ metrics.avgLatencyMs }} ms
              </div>
            </div>
            <div class="p-3.5 rounded-xl bg-elevated/50 border border-subtle">
              <div class="text-[11px] text-muted">Mock 自动降级触发率</div>
              <div class="text-xl font-bold font-mono text-amber-400 mt-1">
                {{ (metrics.mockFallbackRate * 100).toFixed(1) }}%
              </div>
            </div>
          </div>

          <!-- 7天 x 24小时 Token 消耗热力图 -->
          <div class="p-4 rounded-xl bg-elevated/40 border border-subtle space-y-2">
            <div class="flex items-center justify-between text-xs">
              <span class="font-semibold text-main">
                7 天 × 24 小时 Token 消耗密度热力图 (ECharts Heatmap)
              </span>
              <span class="text-[11px] text-muted font-mono">单位: Tokens / 小时</span>
            </div>
            <div ref="heatmapChartRef" class="w-full h-60" />
          </div>

          <!-- API 吞吐趋势图 & 模型调用占比 -->
          <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div class="lg:col-span-2 p-4 rounded-xl bg-elevated/40 border border-subtle space-y-2">
              <div class="text-xs font-semibold text-main">
                API 吞吐序列 (Tokens/min & Requests/min)
              </div>
              <div ref="throughputChartRef" class="w-full h-52" />
            </div>

            <div class="p-4 rounded-xl bg-elevated/40 border border-subtle space-y-3">
              <div class="text-xs font-semibold text-main flex items-center gap-1.5">
                <Cpu class="w-3.5 h-3.5 text-cyber-cyan" />
                <span>模型调用与 Token 分布</span>
              </div>
              <div class="space-y-2.5">
                <div
                  v-for="m in metrics.modelDistribution"
                  :key="m.model"
                  class="p-2.5 rounded-lg bg-card/70 border border-subtle space-y-1 text-xs"
                >
                  <div class="font-mono text-main truncate" :title="m.model">
                    {{ m.model }}
                  </div>
                  <div class="flex items-center justify-between text-[11px] text-muted font-mono">
                    <span>{{ m.count }} 次调用</span>
                    <span class="text-cyber-cyan">{{ m.tokens.toLocaleString() }} Tokens</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>

        <!-- ==================== Tab 3: Prompt 调优沙箱 ==================== -->
        <template v-else-if="activeTab === 'sandbox'">
          <div class="p-4 rounded-xl bg-elevated/40 border border-subtle space-y-4">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h3 class="text-xs font-bold text-main">
                  A/B System Prompt 双轨调优沙箱
                </h3>
                <p class="text-[11px] text-muted">
                  在同一测试输入与 Temperature 下并行对比两套 System Prompt 的输出质量、耗时与 Token 开销
                </p>
              </div>

              <div class="flex items-center gap-3 text-xs">
                <label class="flex items-center gap-2 text-sub">
                  <span>Temperature:</span>
                  <input
                    v-model.number="sandboxTemperature"
                    type="range"
                    min="0"
                    max="1.2"
                    step="0.1"
                    class="w-24 accent-blue-500"
                  />
                  <span class="font-mono text-main w-7">{{ sandboxTemperature.toFixed(1) }}</span>
                </label>

                <button
                  type="button"
                  :disabled="isRunningSandbox"
                  class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl cyber-btn-primary text-xs font-semibold"
                  @click="handleRunSandboxComparison"
                >
                  <Play class="w-3.5 h-3.5" />
                  <span>{{ isRunningSandbox ? '双轨推理对比中...' : '运行双轨对比' }}</span>
                </button>
              </div>
            </div>

            <!-- 测试 User Query -->
            <div class="space-y-1">
              <label class="text-xs font-medium text-sub">测试提问 (User Prompt)</label>
              <input
                v-model="sandboxUserQuery"
                type="text"
                class="w-full px-3.5 py-2 rounded-xl bg-card/80 border border-subtle text-xs text-main focus:outline-none focus:border-primary"
              />
            </div>

            <!-- A / B Prompt 输入与输出对比双栏 -->
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
              <!-- Variant A -->
              <div class="p-3.5 rounded-xl bg-card/70 border border-primary/30 space-y-3">
                <div class="flex items-center justify-between text-xs">
                  <span class="font-bold text-primary-light">方案 A · System Prompt</span>
                  <span
                    v-if="sandboxResultA"
                    class="font-mono text-[11px] text-cyber-cyan"
                  >
                    {{ sandboxResultA.promptTokens + sandboxResultA.completionTokens }} Tokens ·
                    {{ sandboxResultA.latencyMs }}ms
                  </span>
                </div>
                <textarea
                  v-model="sandboxPromptA"
                  rows="3"
                  class="w-full rounded-lg bg-elevated/70 border border-subtle p-2.5 text-xs text-main resize-none focus:outline-none focus:border-primary"
                />
                <div
                  v-if="sandboxResultA"
                  class="p-3 rounded-lg bg-elevated/50 border border-subtle text-xs text-sub whitespace-pre-wrap leading-relaxed"
                >
                  {{ sandboxResultA.output }}
                </div>
              </div>

              <!-- Variant B -->
              <div class="p-3.5 rounded-xl bg-card/70 border border-cyber-cyan/30 space-y-3">
                <div class="flex items-center justify-between text-xs">
                  <span class="font-bold text-cyber-cyan">方案 B · System Prompt</span>
                  <span
                    v-if="sandboxResultB"
                    class="font-mono text-[11px] text-cyber-cyan"
                  >
                    {{ sandboxResultB.promptTokens + sandboxResultB.completionTokens }} Tokens ·
                    {{ sandboxResultB.latencyMs }}ms
                  </span>
                </div>
                <textarea
                  v-model="sandboxPromptB"
                  rows="3"
                  class="w-full rounded-lg bg-elevated/70 border border-subtle p-2.5 text-xs text-main resize-none focus:outline-none focus:border-cyber-cyan"
                />
                <div
                  v-if="sandboxResultB"
                  class="p-3 rounded-lg bg-elevated/50 border border-subtle text-xs text-sub whitespace-pre-wrap leading-relaxed"
                >
                  {{ sandboxResultB.output }}
                </div>
              </div>
            </div>
          </div>
        </template>

        <!-- ==================== Tab 4: Agent 决策追踪 ==================== -->
        <template v-else-if="activeTab === 'agent_trace'">
          <div class="space-y-4">
            <div
              v-for="trace in agentTraceRecords"
              :key="trace.messageId"
              class="p-4 rounded-xl bg-elevated/40 border border-subtle space-y-3"
            >
              <div class="flex flex-wrap items-center justify-between gap-2 border-b border-subtle pb-2.5 text-xs">
                <div class="flex items-center gap-2">
                  <GitBranch class="w-4 h-4 text-indigo-400" />
                  <span class="font-bold text-main font-mono">
                    Trace #{{ trace.messageId.slice(-8) }}
                  </span>
                  <span class="px-2 py-0.5 rounded bg-card text-sub font-mono text-[11px]">
                    {{ trace.model }}
                  </span>
                </div>
                <div class="flex items-center gap-2 font-mono text-[11px] text-cyber-cyan">
                  <Clock class="w-3.5 h-3.5" />
                  <span>总决策耗时: {{ trace.totalDurationMs }} ms</span>
                </div>
              </div>

              <!-- 4 阶段时间轴节点分解 -->
              <div class="space-y-2.5">
                <div
                  v-for="(step, sIdx) in trace.steps"
                  :key="step.stepId"
                  class="p-3 rounded-xl bg-card/80 border border-subtle space-y-1.5 text-xs"
                >
                  <div class="flex items-center justify-between">
                    <div class="flex items-center gap-2">
                      <span
                        class="w-5 h-5 rounded-full bg-primary/20 text-primary-light font-mono text-[11px] flex items-center justify-center font-bold"
                      >
                        {{ sIdx + 1 }}
                      </span>
                      <span class="font-semibold text-main">{{ step.title }}</span>
                      <span class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-elevated text-cyber-cyan">
                        phase={{ step.phase }} · tool={{ step.toolName }}
                      </span>
                    </div>
                    <div class="flex items-center gap-2 font-mono text-[11px]">
                      <CheckCircle2 class="w-3.5 h-3.5 text-emerald-400" />
                      <span class="text-main">{{ step.durationMs }} ms</span>
                      <span class="text-muted">
                        ({{ Math.round((step.durationMs / trace.totalDurationMs) * 100) }}%)
                      </span>
                    </div>
                  </div>

                  <div class="grid grid-cols-1 sm:grid-cols-2 gap-2 pt-1 text-[11px] font-mono">
                    <div class="p-2 rounded bg-elevated/60 text-sub truncate" :title="step.input">
                      <span class="text-muted">INPUT: </span>{{ step.input }}
                    </div>
                    <div class="p-2 rounded bg-elevated/60 text-cyber-cyan truncate" :title="step.output">
                      <span class="text-muted">OUTPUT: </span>{{ step.output }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
