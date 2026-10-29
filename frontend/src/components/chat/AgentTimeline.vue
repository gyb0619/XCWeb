<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  Bot,
  ChevronDown,
  Compass,
  Database,
  Sparkles,
  Wrench,
} from 'lucide-vue-next'
import type { AgentPhase, AgentStep } from '@/types/api'

const props = withDefaults(
  defineProps<{
    steps: AgentStep[]
  }>(),
  {
    steps: () => [],
  },
)

// 二级结构：默认折叠整个时间轴，点击展开详情
const isTimelineOpen = ref<boolean>(false)
// 记录当前展开的单个 stepId 集合
const expandedStepIds = ref<Record<string, boolean>>({})

const PHASE_META: Record<
  AgentPhase,
  { label: string; shortLabel: string; badgeClass: string }
> = {
  plan: {
    label: '规划 (Plan)',
    shortLabel: '1. 规划',
    badgeClass: 'bg-primary/15 text-primary-light border-primary/35',
  },
  tool: {
    label: '工具调用 (Tool Call)',
    shortLabel: '2. 工具调用',
    badgeClass: 'bg-cyber-cyan/15 text-cyber-cyan border-cyber-cyan/35',
  },
  observation: {
    label: '数据回传 (Data Return)',
    shortLabel: '3. 数据回传',
    badgeClass: 'bg-indigo-500/15 text-indigo-300 border-indigo-500/35',
  },
  synthesis: {
    label: '总结分析 (Synthesis)',
    shortLabel: '4. 总结分析',
    badgeClass: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/35',
  },
}

const totalDurationMs = computed<number>(() => {
  return props.steps.reduce((acc, s) => acc + (s.durationMs || 0), 0)
})

const isAllExpanded = computed<boolean>(() => {
  if (props.steps.length === 0) return false
  return props.steps.every((s) => Boolean(expandedStepIds.value[s.stepId]))
})

function isStepExpanded(stepId: string): boolean {
  return Boolean(expandedStepIds.value[stepId])
}

function toggleStep(stepId: string) {
  expandedStepIds.value[stepId] = !expandedStepIds.value[stepId]
}

function toggleAllSteps() {
  const nextState = !isAllExpanded.value
  const map: Record<string, boolean> = {}
  for (const step of props.steps) {
    map[step.stepId] = nextState
  }
  expandedStepIds.value = map
}

function getPhaseMeta(phase: AgentPhase) {
  return PHASE_META[phase] || PHASE_META.plan
}
</script>

<template>
  <div
    v-if="steps && steps.length > 0"
    class="agent-timeline-mount rounded-xl bg-elevated/40 border border-subtle overflow-hidden transition-all text-xs"
  >
    <!-- 一级折叠栏：紧凑单行总览 (点击展开/收起二级详情结构) -->
    <button
      type="button"
      class="w-full flex items-center justify-between p-2.5 text-left hover:bg-elevated/60 transition-colors select-none"
      @click="isTimelineOpen = !isTimelineOpen"
    >
      <div class="flex items-center gap-2">
        <div
          class="w-5 h-5 rounded-md bg-cyber-cyan/15 border border-cyber-cyan/35 flex items-center justify-center text-cyber-cyan shrink-0"
        >
          <Bot class="w-3 h-3" />
        </div>
        <span class="font-medium text-main text-xs">
          Agent 规划执行完成
        </span>
        <span class="text-muted font-mono text-[11px]">
          ({{ steps.length }} 阶段 · {{ totalDurationMs }}ms)
        </span>
      </div>

      <div class="flex items-center gap-1.5 text-muted text-[11px]">
        <span>{{ isTimelineOpen ? '收起详情' : '展开详情' }}</span>
        <ChevronDown
          class="w-3.5 h-3.5 transition-transform duration-200"
          :class="isTimelineOpen ? 'rotate-180 text-cyber-cyan' : ''"
        />
      </div>
    </button>

    <!-- 二级展开结构：完整的四阶段步骤时间轴 -->
    <div
      v-if="isTimelineOpen"
      class="p-3 border-t border-subtle/60 space-y-3 bg-card/60 animate-slide-up"
    >
      <!-- 操作行：展开全部 -->
      <div class="flex items-center justify-between text-[11px] text-muted">
        <span class="font-mono text-[10px]">
          规划 (Plan) → 工具 (Tool) → 回传 (Observation) → 总结 (Synthesis)
        </span>
        <button
          type="button"
          class="text-sub hover:text-main underline transition-colors"
          @click.stop="toggleAllSteps"
        >
          {{ isAllExpanded ? '全部收起' : '全部展开' }}
        </button>
      </div>

      <!-- 四阶段交互式卡片网格 -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2">
        <div
          v-for="step in steps"
          :key="step.stepId"
          class="relative flex flex-col rounded-xl bg-card/85 border transition-all duration-200"
          :class="
            isStepExpanded(step.stepId)
              ? 'border-cyber-cyan/50 shadow-cyber-cyan sm:col-span-2 lg:col-span-2'
              : 'border-subtle hover:border-primary/40'
          "
        >
          <!-- 节点头部（点击展开/折叠具体输入输出） -->
          <button
            type="button"
            class="w-full p-2 text-left flex flex-col gap-1 select-none"
            :aria-expanded="isStepExpanded(step.stepId)"
            @click="toggleStep(step.stepId)"
          >
            <div class="flex items-center justify-between gap-1.5">
              <div class="flex items-center gap-1.5 min-w-0">
                <span
                  class="w-4 h-4 rounded flex items-center justify-center shrink-0 border"
                  :class="getPhaseMeta(step.phase).badgeClass"
                >
                  <Compass v-if="step.phase === 'plan'" class="w-2.5 h-2.5" />
                  <Wrench v-else-if="step.phase === 'tool'" class="w-2.5 h-2.5" />
                  <Database v-else-if="step.phase === 'observation'" class="w-2.5 h-2.5" />
                  <Sparkles v-else class="w-2.5 h-2.5" />
                </span>

                <span class="text-xs font-semibold text-main truncate">
                  {{ step.title || getPhaseMeta(step.phase).label }}
                </span>
              </div>

              <div class="flex items-center gap-1 shrink-0 text-[10px] font-mono text-cyber-cyan">
                <span>{{ step.durationMs }}ms</span>
                <ChevronDown
                  class="w-3 h-3 text-muted transition-transform duration-200"
                  :class="isStepExpanded(step.stepId) ? 'rotate-180 text-cyber-cyan' : ''"
                />
              </div>
            </div>

            <div class="flex items-center justify-between gap-2 text-[10px] text-muted truncate">
              <span class="font-mono text-primary-light">[{{ step.toolName }}]</span>
              <span class="truncate">{{ step.output }}</span>
            </div>
          </button>

          <!-- 点击展开的输入输出详情 -->
          <div
            v-if="isStepExpanded(step.stepId)"
            class="px-2.5 pb-2.5 pt-1.5 border-t border-subtle/60 space-y-1.5 text-[10px] bg-elevated/40"
          >
            <div class="space-y-0.5">
              <span class="text-sub font-semibold">Input:</span>
              <pre class="p-1.5 rounded bg-page/80 border border-subtle text-sub font-mono whitespace-pre-wrap break-words max-h-24 overflow-y-auto">{{ step.input || '无' }}</pre>
            </div>
            <div class="space-y-0.5">
              <span class="text-cyber-cyan font-semibold">Output:</span>
              <pre class="p-1.5 rounded bg-page/80 border border-cyber-cyan/30 text-main font-mono whitespace-pre-wrap break-words max-h-24 overflow-y-auto">{{ step.output }}</pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>