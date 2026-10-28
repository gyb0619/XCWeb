<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  Bot,
  CheckCircle2,
  ChevronDown,
  Clock,
  Compass,
  Database,
  Loader2,
  ShieldCheck,
  Sparkles,
  Terminal,
  Wrench,
  AlertCircle,
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

// 记录当前展开的 stepId 集合；默认展开最新或第一个可查看节点以便一键交互，也可点击任一节点切换
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
    class="agent-timeline-mount rounded-2xl bg-elevated/45 border border-subtle p-3.5 space-y-3 transition-all"
  >
    <!-- 顶部总览栏 -->
    <div class="flex flex-wrap items-center justify-between gap-2">
      <div class="flex items-center flex-wrap gap-2">
        <div
          class="w-6 h-6 rounded-lg bg-cyber-cyan/15 border border-cyber-cyan/35 flex items-center justify-center text-cyber-cyan shrink-0"
        >
          <Bot class="w-3.5 h-3.5" />
        </div>
        <span class="text-xs font-semibold text-main">
          Agent 可视化思考时间轴
        </span>
        <span
          class="hidden md:inline-flex items-center gap-1 text-[10px] font-mono px-2 py-0.5 rounded-full bg-card/80 text-muted border border-subtle"
        >
          规划 (Plan) → 工具调用 (Tool Call) → 数据回传 (Data Return) → 总结分析 (Synthesis)
        </span>
      </div>

      <div class="flex items-center gap-2 text-[11px]">
        <span
          class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-cyber-cyan/10 text-cyber-cyan border border-cyber-cyan/25 font-mono"
        >
          <Clock class="w-3 h-3" />
          <span>总耗时 {{ totalDurationMs }}ms</span>
        </span>

        <button
          type="button"
          class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-card hover:bg-elevated text-sub hover:text-main border border-subtle transition-colors"
          @click="toggleAllSteps"
        >
          <span>{{ isAllExpanded ? '全部收起' : '全部展开' }}</span>
          <ChevronDown
            class="w-3 h-3 transition-transform duration-200"
            :class="isAllExpanded ? 'rotate-180' : ''"
          />
        </button>
      </div>
    </div>

    <!-- 四阶段交互式步骤时间轴卡片网格 -->
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2.5">
      <div
        v-for="(step, idx) in steps"
        :key="step.stepId"
        class="relative flex flex-col rounded-xl bg-card/85 border transition-all duration-200"
        :class="
          isStepExpanded(step.stepId)
            ? 'border-cyber-cyan/50 shadow-cyber-cyan sm:col-span-2 lg:col-span-2'
            : 'border-subtle hover:border-primary/40'
        "
      >
        <!-- 节点头部（点击展开 / 折叠详细输入输出与耗时） -->
        <button
          type="button"
          class="w-full p-2.5 text-left flex flex-col gap-1.5 select-none"
          :aria-expanded="isStepExpanded(step.stepId)"
          @click="toggleStep(step.stepId)"
        >
          <div class="flex items-center justify-between gap-1.5">
            <div class="flex items-center gap-1.5 min-w-0">
              <!-- 阶段图标 -->
              <span
                class="w-5 h-5 rounded-md flex items-center justify-center shrink-0 border"
                :class="getPhaseMeta(step.phase).badgeClass"
              >
                <Compass v-if="step.phase === 'plan'" class="w-3 h-3" />
                <Wrench v-else-if="step.phase === 'tool'" class="w-3 h-3" />
                <Database
                  v-else-if="step.phase === 'observation'"
                  class="w-3 h-3"
                />
                <Sparkles v-else class="w-3 h-3" />
              </span>

              <span class="text-xs font-semibold text-main truncate">
                {{ step.title || getPhaseMeta(step.phase).label }}
              </span>
            </div>

            <!-- 状态与耗时徽章 -->
            <div class="flex items-center gap-1 shrink-0">
              <Loader2
                v-if="step.status === 'running'"
                class="w-3.5 h-3.5 text-cyber-cyan animate-spin"
              />
              <AlertCircle
                v-else-if="step.status === 'error'"
                class="w-3.5 h-3.5 text-red-400"
              />
              <CheckCircle2
                v-else
                class="w-3.5 h-3.5 text-emerald-400"
              />
              <span class="font-mono text-[10px] text-cyber-cyan">
                {{ step.durationMs }}ms
              </span>
              <ChevronDown
                class="w-3 h-3 text-muted transition-transform duration-200"
                :class="isStepExpanded(step.stepId) ? 'rotate-180 text-cyber-cyan' : ''"
              />
            </div>
          </div>

          <!-- 工具白名单标识与单行摘要 -->
          <div class="flex items-center justify-between gap-2 text-[11px]">
            <span
              class="inline-flex items-center gap-1 font-mono text-[10px] px-1.5 py-0.5 rounded bg-elevated text-sub border border-subtle shrink-0"
            >
              <Terminal class="w-2.5 h-2.5 text-primary-light" />
              {{ step.toolName }}
            </span>
            <span class="text-muted truncate text-[11px]">
              {{ step.output }}
            </span>
          </div>

          <!-- 阶段序号进度条装饰 -->
          <div class="w-full h-1 rounded-full bg-elevated overflow-hidden mt-0.5">
            <div
              class="h-full rounded-full transition-all duration-500"
              :class="
                step.status === 'running'
                  ? 'w-2/3 bg-cyber-cyan animate-pulse'
                  : 'w-full bg-gradient-to-r from-primary to-cyber-cyan'
              "
            />
          </div>
        </button>

        <!-- 点击展开的详情抽屉（展示 input、output、toolName、durationMs） -->
        <div
          class="grid transition-[grid-template-rows,opacity] duration-250 ease-out"
          :class="
            isStepExpanded(step.stepId)
              ? 'grid-rows-[1fr] opacity-100'
              : 'grid-rows-[0fr] opacity-0'
          "
        >
          <div class="overflow-hidden min-h-0">
            <div
              class="px-3 pb-3 pt-2 border-t border-subtle/60 space-y-2 text-[11px] bg-elevated/30"
            >
              <!-- 元信息行：阶段枚举 + 服务端白名单工具认证 + 精确耗时 -->
              <div class="flex flex-wrap items-center justify-between gap-1.5 text-[10px]">
                <div class="flex items-center gap-1.5">
                  <span
                    class="px-1.5 py-0.5 rounded border font-medium"
                    :class="getPhaseMeta(step.phase).badgeClass"
                  >
                    阶段 #{{ idx + 1 }} · {{ getPhaseMeta(step.phase).label }}
                  </span>
                  <span
                    class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/25 font-mono"
                  >
                    <ShieldCheck class="w-3 h-3" />
                    <span>白名单工具: {{ step.toolName }}</span>
                  </span>
                </div>
                <span class="font-mono text-cyber-cyan">
                  执行耗时: {{ step.durationMs }} ms
                </span>
              </div>

              <!-- 输入参数 Input -->
              <div class="space-y-1">
                <div class="text-[10px] font-semibold text-sub uppercase tracking-wider">
                  Input (输入参数)
                </div>
                <pre
                  class="p-2 rounded-lg bg-page/80 border border-subtle text-[11px] text-sub font-mono whitespace-pre-wrap break-words max-h-32 overflow-y-auto"
                >{{ step.input || '无附加参数' }}</pre>
              </div>

              <!-- 执行结果 Output -->
              <div class="space-y-1">
                <div class="text-[10px] font-semibold text-cyber-cyan uppercase tracking-wider">
                  Output (数据回传 / 执行结果)
                </div>
                <pre
                  class="p-2 rounded-lg bg-page/80 border border-cyber-cyan/25 text-[11px] text-main font-mono whitespace-pre-wrap break-words max-h-36 overflow-y-auto"
                >{{ step.output || '执行完成' }}</pre>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
