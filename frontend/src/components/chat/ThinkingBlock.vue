<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Brain, CheckCircle2, ChevronDown, Sparkles } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    messageId: string
    thinkingContent?: string | null
    isThinking?: boolean
    durationMs?: number
    defaultCollapsed?: boolean
  }>(),
  {
    thinkingContent: '',
    isThinking: false,
    durationMs: 0,
    defaultCollapsed: false,
  },
)

const emit = defineEmits<{
  (e: 'thinking-complete', messageId: string): void
}>()

const isCollapsed = ref<boolean>(props.defaultCollapsed)
const liveElapsedMs = ref<number>(props.durationMs || 0)
const justCompleted = ref<boolean>(false)

let timerHandle: ReturnType<typeof setInterval> | null = null
let startTimestamp = 0

function startLiveTimer() {
  stopLiveTimer()
  startTimestamp = performance.now() - (props.durationMs || 0)
  timerHandle = setInterval(() => {
    const elapsed = Math.round(performance.now() - startTimestamp)
    liveElapsedMs.value = Math.max(elapsed, props.durationMs || 0)
  }, 100)
}

function stopLiveTimer() {
  if (timerHandle !== null) {
    clearInterval(timerHandle)
    timerHandle = null
  }
}

const formattedSeconds = computed<string>(() => {
  const ms = props.isThinking
    ? Math.max(100, liveElapsedMs.value, props.durationMs || 0)
    : Math.max(props.durationMs || 0, liveElapsedMs.value, 100)
  return `${(ms / 1000).toFixed(1)}s`
})

const thoughtLineCount = computed<number>(() => {
  if (!props.thinkingContent) return 0
  return props.thinkingContent
    .trim()
    .split(/\r?\n/)
    .filter((l) => l.trim().length > 0).length
})

function toggleDrawer() {
  isCollapsed.value = !isCollapsed.value
}

watch(
  () => props.durationMs,
  (val) => {
    if (typeof val === 'number' && val > liveElapsedMs.value) {
      liveElapsedMs.value = val
    }
  },
)

watch(
  () => props.isThinking,
  (thinkingNow, wasThinking) => {
    if (thinkingNow) {
      isCollapsed.value = false
      justCompleted.value = false
      startLiveTimer()
    } else {
      stopLiveTimer()
      if (props.durationMs && props.durationMs > 0) {
        liveElapsedMs.value = props.durationMs
      }
      if (wasThinking) {
        justCompleted.value = true
        emit('thinking-complete', props.messageId)
      }
    }
  },
  { immediate: true },
)

onMounted(() => {
  if (props.isThinking) {
    startLiveTimer()
  }
})

onBeforeUnmount(() => {
  stopLiveTimer()
})
</script>

<template>
  <div
    class="thinking-drawer-mount rounded-2xl bg-elevated/55 border transition-all duration-300 overflow-hidden"
    :class="
      isThinking
        ? 'border-cyber-cyan/45 shadow-cyber-cyan'
        : 'border-cyber-cyan/20 hover:border-cyber-cyan/35'
    "
  >
    <!-- 顶部可点击抽屉控制栏 -->
    <button
      type="button"
      class="w-full flex items-center justify-between gap-3 px-4 py-2.5 text-xs text-sub hover:text-main transition-colors select-none"
      :aria-expanded="!isCollapsed"
      @click="toggleDrawer"
    >
      <div class="flex items-center gap-2.5 min-w-0">
        <!-- 状态图标形变容器：思考中科技蓝旋转指示器 -> 思考结束模糊淡出 + 翠绿对勾微光放大浮入 (PRD 4.4) -->
        <div class="relative w-5 h-5 flex items-center justify-center shrink-0">
          <!-- 1. 科技蓝旋转指示器（思考结束时 blur + opacity-0 模糊淡出） -->
          <div
            class="absolute inset-0 flex items-center justify-center transition-all duration-500 ease-out"
            :class="
              isThinking
                ? 'opacity-100 blur-0 scale-100'
                : 'opacity-0 blur-[4px] scale-50 pointer-events-none'
            "
          >
            <span
              class="w-4 h-4 rounded-full border-2 border-primary-light/30 border-t-cyber-cyan animate-spin shadow-[0_0_10px_rgba(6,182,212,0.65)]"
            />
          </div>

          <!-- 2. 绿色微光对勾（思考结束时带微光弹性放大浮入） -->
          <div
            class="absolute inset-0 flex items-center justify-center transition-all duration-500 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
            :class="
              isThinking
                ? 'opacity-0 blur-[4px] scale-50 pointer-events-none'
                : 'opacity-100 blur-0 scale-100'
            "
          >
            <CheckCircle2
              class="w-4 h-4 text-emerald-400 drop-shadow-[0_0_8px_rgba(16,185,129,0.85)]"
              :class="justCompleted ? 'scale-105' : ''"
            />
          </div>
        </div>

        <Brain class="w-3.5 h-3.5 text-cyber-cyan shrink-0" />

        <span class="font-semibold text-main truncate">
          {{ isThinking ? '深度思考推理中...' : '深度思考已完成' }}
        </span>

        <!-- 实时 0.1s 精度跳动计时器徽章 -->
        <span
          class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full font-mono text-[11px] border transition-colors shrink-0"
          :class="
            isThinking
              ? 'bg-cyber-cyan/15 text-cyber-cyan border-cyber-cyan/40 animate-pulse'
              : 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
          "
        >
          <Sparkles v-if="isThinking" class="w-2.5 h-2.5" />
          <span>用时 {{ formattedSeconds }}</span>
        </span>

        <span
          v-if="thoughtLineCount > 0"
          class="hidden sm:inline text-[11px] text-muted font-mono shrink-0"
        >
          · {{ thoughtLineCount }} 步推理链
        </span>
      </div>

      <div class="flex items-center gap-1.5 text-[11px] text-muted shrink-0">
        <span>{{ isCollapsed ? '展开思维链' : '收起' }}</span>
        <ChevronDown
          class="w-3.5 h-3.5 transition-transform duration-300"
          :class="isCollapsed ? '-rotate-90' : 'rotate-0'"
        />
      </div>
    </button>

    <!-- CSS Grid 1fr / 0fr 平滑折叠抽屉内容区 (PRD 4.4) -->
    <div
      class="grid transition-[grid-template-rows,opacity] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)]"
      :class="
        isCollapsed
          ? 'grid-rows-[0fr] opacity-0'
          : 'grid-rows-[1fr] opacity-100'
      "
    >
      <div class="overflow-hidden min-h-0">
        <div
          class="px-4 pb-3.5 pt-2 text-xs text-sub font-mono whitespace-pre-wrap leading-relaxed border-t border-subtle/60 relative"
        >
          <!-- 左侧科技蓝呼吸导引线 -->
          <div class="pl-3 border-l-2 border-cyber-cyan/40 space-y-1">
            <span>{{ thinkingContent || '正在构建多维思维链与推理路径...' }}</span>
            <span
              v-if="isThinking"
              class="inline-block w-1.5 h-3.5 ml-1 align-middle bg-cyber-cyan animate-pulse"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
