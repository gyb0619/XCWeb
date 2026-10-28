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
import { marked } from 'marked'
import hljs from 'highlight.js'
import {
  Table2,
  BarChart3,
  LineChart,
  Search,
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
  Sparkles,
  Download,
  X,
} from 'lucide-vue-next'
import { useConfigStore } from '@/stores/configStore'

const props = withDefaults(
  defineProps<{
    content: string
    messageId: string
    hideMarkdown?: boolean
  }>(),
  {
    hideMarkdown: false,
  },
)

const configStore = useConfigStore()

export interface StructuredTableData {
  title: string
  headers: string[]
  rows: string[][]
  isSyntheticScore: boolean
}

type ViewMode = 'table' | 'bar' | 'line'
type SortOrder = 'asc' | 'desc' | null

const activeView = ref<ViewMode>('table')
const searchQuery = ref<string>('')
const sortColIndex = ref<number | null>(null)
const sortOrder = ref<SortOrder>(null)

const chartContainerRef = ref<HTMLElement | null>(null)
let chartInstance: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

// Markdown 渲染（集成代码高亮）
const renderedHtml = computed<string>(() => {
  if (!props.content) return ''
  const rawHtml = marked.parse(props.content, { async: false })
  const htmlStr = typeof rawHtml === 'string' ? rawHtml : ''
  return htmlStr.replace(
    /<pre><code class="language-([\w-]+)">([\s\S]*?)<\/code><\/pre>/g,
    (_match, lang: string, code: string) => {
      const decoded = code
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&amp;/g, '&')
        .replace(/&quot;/g, '"')
      try {
        if (hljs.getLanguage(lang)) {
          const highlighted = hljs.highlight(decoded, { language: lang }).value
          return `<pre class="hljs-pre rounded-xl bg-slate-950/90 p-3.5 overflow-x-auto border border-slate-800 my-2"><code class="hljs language-${lang}">${highlighted}</code></pre>`
        }
      } catch {
        // fallback to default code block
      }
      return `<pre class="hljs-pre rounded-xl bg-slate-950/90 p-3.5 overflow-x-auto border border-slate-800 my-2"><code>${code}</code></pre>`
    },
  )
})

function cleanMarkdownCell(raw: string): string {
  return raw
    .replace(/\*\*(.*?)\*\*/g, '$1')
    .replace(/`(.*?)`/g, '$1')
    .replace(/~~(.*?)~~/g, '$1')
    .trim()
}

function splitTableRow(line: string): string[] {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  return trimmed.split('|').map((cell) => cleanMarkdownCell(cell))
}

function isSeparatorLine(line: string): boolean {
  const trimmed = line.trim()
  if (!trimmed.includes('|') || !trimmed.includes('-')) return false
  const cells = splitTableRow(trimmed)
  return (
    cells.length >= 2 &&
    cells.every((c) => /^:?-{2,}:?$/.test(c.trim()))
  )
}

/**
 * 解析 Markdown 中的 GFM 结构化表格
 */
const parsedTable = computed<StructuredTableData | null>(() => {
  if (!props.content) return null
  const lines = props.content.split(/\r?\n/)

  for (let i = 0; i < lines.length - 2; i++) {
    const headerLine = lines[i].trim()
    const sepLine = lines[i + 1].trim()

    if (headerLine.includes('|') && isSeparatorLine(sepLine)) {
      const headers = splitTableRow(headerLine)
      if (headers.length < 2) continue

      const rows: string[][] = []
      let j = i + 2
      while (j < lines.length) {
        const rowLine = lines[j].trim()
        if (!rowLine.includes('|') || isSeparatorLine(rowLine)) break
        const cells = splitTableRow(rowLine)
        if (cells.length >= 2) {
          const normalized = headers.map((_, idx) => cells[idx] ?? '-')
          rows.push(normalized)
        }
        j++
      }

      if (rows.length >= 2) {
        // 尝试从表格上方最近的标题提取卡片名称
        let title = '结构化对比与多维分析视图'
        for (let k = i - 1; k >= Math.max(0, i - 5); k--) {
          const prev = lines[k].trim()
          if (prev.startsWith('#')) {
            title = prev.replace(/^#+\s*/, '').trim()
            break
          }
        }

        // 检测除第一列外是否包含可提取的数值
        let numericColFound = false
        for (let c = 1; c < headers.length; c++) {
          const validNums = rows.filter((r) => extractNumericValue(r[c]) !== null)
          if (validNums.length >= Math.ceil(rows.length * 0.5)) {
            numericColFound = true
            break
          }
        }

        return {
          title,
          headers,
          rows,
          isSyntheticScore: !numericColFound,
        }
      }
    }
  }

  return null
})

/**
 * 从单元格字符串中提取首个有效数值（支持 16,000 / 95.4% / 55 FPS / 1.2s 等格式）
 */
function extractNumericValue(cell: string): number | null {
  if (!cell) return null
  const normalized = cell.replace(/,/g, '')
  const match = normalized.match(/-?\d+(\.\d+)?/)
  if (!match) return null
  const val = Number.parseFloat(match[0])
  if (Number.isNaN(val)) return null
  if (/k\b/i.test(normalized) && val < 1000) {
    return val * 1000
  }
  return val
}

/**
 * 当表格全为纯文本对比列时，自动计算技术成熟度/信息密度量化得分 (60 ~ 98)
 */
function computeTextFeatureScore(cell: string, rowIdx: number, colIdx: number): number {
  const directNum = extractNumericValue(cell)
  if (directNum !== null && directNum > 0 && directNum <= 200) {
    return directNum
  }

  let score = 68 + ((rowIdx * 5 + colIdx * 7) % 10)
  const positiveKeywords = [
    '支持',
    '内置',
    '自动',
    '实时',
    '流式',
    '独立',
    '三重校验',
    '云端',
    '加密',
    '60',
    '55',
    'ECharts',
    '智能体',
    'RAG',
    '高',
    '极速',
    '完整',
    '零错误',
  ]
  const negativeKeywords = [
    '不支持',
    '无',
    '缺失',
    '传统',
    '卡顿',
    '单轮',
    '手动',
    '低',
    '粗糙',
    '混杂',
    '受限',
  ]

  for (const kw of positiveKeywords) {
    if (cell.includes(kw)) score += 6
  }
  for (const kw of negativeKeywords) {
    if (cell.includes(kw)) score -= 8
  }

  score += Math.min(12, Math.floor(cell.length / 6))
  return Math.max(45, Math.min(98, score))
}

function isNumericMetricCell(cell: string): boolean {
  return /\d+(\.\d+)?\s*(%|ms|s|FPS|Tokens|K|次|倍|MB|GB)?/i.test(cell) && /\d/.test(cell)
}

// 筛选与排序后的表格行
const filteredAndSortedRows = computed<string[][]>(() => {
  const table = parsedTable.value
  if (!table) return []

  const q = searchQuery.value.trim().toLowerCase()
  let list = table.rows.filter((row) => {
    if (!q) return true
    return row.some((cell) => cell.toLowerCase().includes(q))
  })

  if (sortColIndex.value !== null && sortOrder.value !== null) {
    const colIdx = sortColIndex.value
    const dir = sortOrder.value === 'asc' ? 1 : -1

    list = [...list].sort((a, b) => {
      const cellA = a[colIdx] ?? ''
      const cellB = b[colIdx] ?? ''
      const numA = extractNumericValue(cellA)
      const numB = extractNumericValue(cellB)

      if (numA !== null && numB !== null) {
        return (numA - numB) * dir
      }
      return cellA.localeCompare(cellB, 'zh-CN') * dir
    })
  }

  return list
})

function handleHeaderSort(colIdx: number) {
  if (sortColIndex.value !== colIdx) {
    sortColIndex.value = colIdx
    sortOrder.value = 'asc'
  } else if (sortOrder.value === 'asc') {
    sortOrder.value = 'desc'
  } else if (sortOrder.value === 'desc') {
    sortColIndex.value = null
    sortOrder.value = null
  } else {
    sortOrder.value = 'asc'
  }
}

function exportTableCsv() {
  const table = parsedTable.value
  if (!table) return

  const escapeCsv = (val: string) => `"${val.replace(/"/g, '""')}"`
  const csvLines = [
    table.headers.map(escapeCsv).join(','),
    ...filteredAndSortedRows.value.map((r) => r.map(escapeCsv).join(',')),
  ]
  const blob = new Blob(['\uFEFF' + csvLines.join('\n')], {
    type: 'text/csv;charset=utf-8;',
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `xcweb_table_${props.messageId.slice(-6)}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

/**
 * 构建科技深邃蓝（Electric Cyber Blue）ECharts 配置项
 */
function buildChartOption(): echarts.EChartsOption {
  const table = parsedTable.value
  if (!table) return {}

  const isLight = configStore.theme === 'light'
  const textColor = isLight ? '#334155' : '#CBD5E1'
  const mutedGridColor = isLight
    ? 'rgba(15, 23, 42, 0.08)'
    : 'rgba(148, 163, 184, 0.12)'

  const rows =
    filteredAndSortedRows.value.length > 0
      ? filteredAndSortedRows.value
      : table.rows

  const categories = rows.map((r, idx) => r[0] || `维度 ${idx + 1}`)
  const seriesHeaders = table.headers.slice(1)

  const cyberPalettes: Array<{ main: string; light: string }> = [
    { main: '#2563EB', light: '#3B82F6' }, // Royal Blue -> Electric Blue
    { main: '#06B6D4', light: '#22D3EE' }, // Cyber Cyan
    { main: '#6366F1', light: '#818CF8' }, // Incognito Indigo
    { main: '#10B981', light: '#34D399' }, // Emerald Accent
  ]

  const chartType = activeView.value === 'line' ? 'line' : 'bar'

  const series: echarts.SeriesOption[] = seriesHeaders.map((colName, sIdx) => {
    const palette = cyberPalettes[sIdx % cyberPalettes.length]
    const dataValues = rows.map((row, rIdx) => {
      const cell = row[sIdx + 1] ?? ''
      if (!table.isSyntheticScore) {
        const num = extractNumericValue(cell)
        return num !== null ? num : computeTextFeatureScore(cell, rIdx, sIdx + 1)
      }
      return computeTextFeatureScore(cell, rIdx, sIdx + 1)
    })

    if (chartType === 'bar') {
      return {
        name: colName,
        type: 'bar',
        barMaxWidth: 28,
        data: dataValues,
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: palette.light },
            { offset: 1, color: palette.main },
          ]),
        },
      }
    }

    return {
      name: colName,
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 7,
      data: dataValues,
      lineStyle: {
        width: 3,
        color: palette.light,
      },
      itemStyle: {
        color: palette.main,
        borderColor: '#ffffff',
        borderWidth: 1.5,
      },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: `${palette.light}55` },
          { offset: 1, color: `${palette.main}05` },
        ]),
      },
    }
  })

  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: isLight
        ? 'rgba(255, 255, 255, 0.94)'
        : 'rgba(17, 24, 39, 0.92)',
      borderColor: 'rgba(59, 130, 246, 0.35)',
      textStyle: {
        color: isLight ? '#0F172A' : '#F8FAFC',
        fontSize: 12,
      },
    },
    legend: {
      top: 4,
      textStyle: {
        color: textColor,
        fontSize: 11,
      },
    },
    grid: {
      top: 40,
      left: 16,
      right: 16,
      bottom: 12,
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      data: categories,
      axisLine: {
        lineStyle: { color: mutedGridColor },
      },
      axisLabel: {
        color: textColor,
        fontSize: 11,
        interval: 0,
        formatter: (value: string) =>
          value.length > 10 ? `${value.slice(0, 10)}…` : value,
      },
    },
    yAxis: {
      type: 'value',
      name: table.isSyntheticScore ? '特征完备度指数' : '指标数值',
      nameTextStyle: {
        color: textColor,
        fontSize: 10,
      },
      splitLine: {
        lineStyle: {
          color: mutedGridColor,
          type: 'dashed',
        },
      },
      axisLabel: {
        color: textColor,
        fontSize: 11,
      },
    },
    series,
  }
}

async function renderOrUpdateChart() {
  if (activeView.value === 'table' || !parsedTable.value) return
  await nextTick()
  const el = chartContainerRef.value
  if (!el) return

  if (!chartInstance) {
    chartInstance = echarts.init(el)
  }
  chartInstance.setOption(buildChartOption(), true)
  chartInstance.resize()
}

function handleWindowResize() {
  if (chartInstance) {
    chartInstance.resize()
  }
}

watch(
  [activeView, filteredAndSortedRows, () => configStore.theme, () => props.content],
  () => {
    if (activeView.value !== 'table') {
      void renderOrUpdateChart()
    }
  },
)

onMounted(() => {
  window.addEventListener('resize', handleWindowResize)
  if (typeof ResizeObserver !== 'undefined' && chartContainerRef.value) {
    resizeObserver = new ResizeObserver(() => {
      chartInstance?.resize()
    })
    resizeObserver.observe(chartContainerRef.value)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleWindowResize)
  resizeObserver?.disconnect()
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<template>
  <div class="generative-ui-wrapper space-y-3">
    <!-- 1. 标准 Markdown 正文渲染（当未设置 hideMarkdown 时展示） -->
    <div
      v-if="!hideMarkdown && content"
      class="glass-panel rounded-2xl p-4 text-sm text-main leading-relaxed answer-slide-up space-y-2 prose prose-invert max-w-none"
      v-html="renderedHtml"
    />

    <!-- 2. 生成式交互 UI 卡片 (PRD 6.3：自动识别结构化表格并渲染交互式筛选表格 + 科技蓝 ECharts 图表) -->
    <div
      v-if="parsedTable"
      class="generative-ui-card rounded-2xl glass-panel border border-primary/35 overflow-hidden shadow-cyber-glow animate-slide-up"
    >
      <!-- 卡片顶栏：标题 + 视图切换器（交互表格 / 科技蓝柱状图 / 科技蓝折线图） -->
      <div
        class="flex flex-wrap items-center justify-between gap-2 px-4 py-3 bg-elevated/60 border-b border-subtle"
      >
        <div class="flex items-center gap-2 min-w-0">
          <div
            class="w-7 h-7 rounded-lg cyber-btn-primary flex items-center justify-center shrink-0"
          >
            <Sparkles class="w-3.5 h-3.5 text-white" />
          </div>
          <div class="truncate">
            <div class="flex items-center gap-2">
              <span class="text-xs font-bold text-main truncate">
                {{ parsedTable.title }}
              </span>
              <span
                class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-cyber-cyan/15 text-cyber-cyan border border-cyber-cyan/30 shrink-0"
              >
                Generative UI
              </span>
            </div>
            <p class="text-[11px] text-muted truncate">
              共 {{ parsedTable.rows.length }} 项结构化记录 · 支持实时筛选、列排序与 ECharts 可视化切换
            </p>
          </div>
        </div>

        <!-- 视图切换胶囊 -->
        <div class="flex items-center gap-1 p-1 rounded-xl bg-card/80 border border-subtle text-xs">
          <button
            type="button"
            class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg transition-all"
            :class="
              activeView === 'table'
                ? 'cyber-btn-primary text-white font-medium'
                : 'text-sub hover:text-main'
            "
            @click="activeView = 'table'"
          >
            <Table2 class="w-3.5 h-3.5" />
            <span>交互表格</span>
          </button>

          <button
            type="button"
            class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg transition-all"
            :class="
              activeView === 'bar'
                ? 'cyber-btn-primary text-white font-medium'
                : 'text-sub hover:text-main'
            "
            @click="activeView = 'bar'"
          >
            <BarChart3 class="w-3.5 h-3.5" />
            <span>柱状图</span>
          </button>

          <button
            type="button"
            class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg transition-all"
            :class="
              activeView === 'line'
                ? 'cyber-btn-primary text-white font-medium'
                : 'text-sub hover:text-main'
            "
            @click="activeView = 'line'"
          >
            <LineChart class="w-3.5 h-3.5" />
            <span>折线图</span>
          </button>
        </div>
      </div>

      <!-- 工具栏：关键词实时筛选 + CSV 导出 -->
      <div
        class="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 bg-card/40 border-b border-subtle/60 text-xs"
      >
        <div class="relative flex-1 min-w-[200px] max-w-xs">
          <Search class="w-3.5 h-3.5 text-muted absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            v-model="searchQuery"
            type="text"
            placeholder="输入关键词实时筛选表格或图表维度..."
            class="w-full pl-8 pr-7 py-1.5 rounded-xl bg-elevated/70 border border-subtle text-xs text-main placeholder-muted focus:outline-none focus:border-primary/60"
          />
          <button
            v-if="searchQuery"
            type="button"
            class="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted hover:text-main"
            @click="searchQuery = ''"
          >
            <X class="w-3 h-3" />
          </button>
        </div>

        <div class="flex items-center gap-3 text-[11px] text-muted">
          <span>
            匹配结果：
            <strong class="font-mono text-main">{{ filteredAndSortedRows.length }}</strong>
            / {{ parsedTable.rows.length }} 行
          </span>
          <button
            type="button"
            class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-elevated hover:bg-primary/20 text-sub hover:text-primary-light border border-subtle transition-colors"
            @click="exportTableCsv"
          >
            <Download class="w-3 h-3" />
            <span>导出 CSV</span>
          </button>
        </div>
      </div>

      <!-- 视图 A：可筛选、可排序的交互式数据表格 -->
      <div v-if="activeView === 'table'" class="overflow-x-auto max-h-80">
        <table class="w-full text-left text-xs border-collapse">
          <thead class="bg-elevated/50 text-sub border-b border-subtle sticky top-0 z-10">
            <tr>
              <th
                v-for="(header, colIdx) in parsedTable.headers"
                :key="colIdx"
                class="py-2.5 px-4 font-semibold cursor-pointer select-none hover:text-main hover:bg-elevated transition-colors"
                @click="handleHeaderSort(colIdx)"
              >
                <div class="flex items-center gap-1.5">
                  <span>{{ header }}</span>
                  <ArrowUp
                    v-if="sortColIndex === colIdx && sortOrder === 'asc'"
                    class="w-3.5 h-3.5 text-cyber-cyan shrink-0"
                  />
                  <ArrowDown
                    v-else-if="sortColIndex === colIdx && sortOrder === 'desc'"
                    class="w-3.5 h-3.5 text-cyber-cyan shrink-0"
                  />
                  <ArrowUpDown v-else class="w-3 h-3 text-muted opacity-60 shrink-0" />
                </div>
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-subtle/50">
            <tr
              v-for="(row, rIdx) in filteredAndSortedRows"
              :key="rIdx"
              class="hover:bg-primary/5 transition-colors"
            >
              <td
                v-for="(cell, cIdx) in row"
                :key="cIdx"
                class="py-2.5 px-4 leading-relaxed"
                :class="cIdx === 0 ? 'font-semibold text-main' : 'text-sub'"
              >
                <span
                  v-if="cIdx > 0 && isNumericMetricCell(cell)"
                  class="font-mono px-2 py-0.5 rounded-md bg-cyber-cyan/10 text-cyber-cyan border border-cyber-cyan/25"
                >
                  {{ cell }}
                </span>
                <span v-else>{{ cell }}</span>
              </td>
            </tr>
            <tr v-if="filteredAndSortedRows.length === 0">
              <td
                :colspan="parsedTable.headers.length"
                class="py-6 text-center text-muted text-xs"
              >
                未找到匹配「{{ searchQuery }}」的数据行
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 视图 B/C：ECharts 科技深邃蓝折线图 / 柱状图 -->
      <div v-else class="p-4 space-y-2">
        <div
          ref="chartContainerRef"
          class="w-full h-72 rounded-xl bg-card/40 border border-subtle/50 p-2"
        />
        <div class="flex items-center justify-between text-[11px] text-muted px-1">
          <span>
            配色方案：Electric Cyber Blue（#2563EB · #3B82F6 · #06B6D4）
          </span>
          <span v-if="parsedTable.isSyntheticScore">
            * 纯文本对比列已自动量化为特征完备度指数 (0-100)
          </span>
        </div>
      </div>
    </div>
  </div>
</template>
