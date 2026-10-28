<script setup lang="ts">
import { computed, nextTick, ref, useSlots, watch } from 'vue'
import { ChevronLeft, ChevronRight, Layers } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chatStore'
import type { Message } from '@/types/api'

const props = defineProps<{
  message: Message
}>()

const slots = useSlots()
const chatStore = useChatStore()

const localStageRef = ref<HTMLElement | null>(null)
const isTransitioning = ref<boolean>(false)
const isIndicatorBlurred = ref<boolean>(false)

const hasSlotContent = computed<boolean>(() => Boolean(slots.default))

const totalVersions = computed<number>(() => {
  return props.message.versions?.length ?? 0
})

const currentIndex = computed<number>(() => {
  const raw = props.message.currentVersionIndex ?? 0
  if (totalVersions.value <= 0) return 0
  return Math.max(0, Math.min(totalVersions.value - 1, raw))
})

const currentVersionMeta = computed(() => {
  return props.message.versions?.[currentIndex.value] ?? null
})

function isReducedMotion(): boolean {
  if (typeof window === 'undefined') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function resolveStageElement(): HTMLElement | null {
  if (localStageRef.value) return localStageRef.value
  if (typeof document === 'undefined') return null
  return document.querySelector<HTMLElement>(
    `[data-message-id="${props.message.id}"] .version-parallax-stage`,
  )
}

/**
 * 视差版本翻页核心动效 (PRD 4.3)：
 * 1. 旧版本内容以 150ms 滑出并淡出
 * 2. 新版本内容以 150px 摆幅带弹性阻尼 cubic-bezier(0.34, 1.56, 0.64, 1) 滑入
 * 3. 底部控制栏原位微调（页码文字带短暂 filter: blur(3px) 过渡），不整体重建控制栏 DOM
 */
async function handleSwitchVersion(targetIndex: number) {
  if (isTransitioning.value) return
  if (targetIndex < 0 || targetIndex >= totalVersions.value) return
  if (targetIndex === currentIndex.value) return

  const direction: 'next' | 'prev' = targetIndex > currentIndex.value ? 'next' : 'prev'
  const stageEl = resolveStageElement()
  const reduced = isReducedMotion()

  isTransitioning.value = true
  // 页码与元信息原位微调：触发短暂 filter: blur(3px)
  isIndicatorBlurred.value = true

  if (!stageEl || typeof window === 'undefined') {
    chatStore.switchMessageVersion(props.message.id, targetIndex)
    window.setTimeout(() => {
      isIndicatorBlurred.value = false
      isTransitioning.value = false
    }, 150)
    return
  }

  stageEl.style.willChange = 'transform, opacity'

  if (reduced) {
    // 无障碍降级：简单淡出淡入，不产生 150px 位移
    stageEl.style.transition = 'opacity 120ms ease-out'
    stageEl.style.opacity = '0'

    await new Promise<void>((resolve) => {
      window.setTimeout(resolve, 120)
    })

    chatStore.switchMessageVersion(props.message.id, targetIndex)
    await nextTick()

    stageEl.style.transition = 'opacity 150ms ease-in'
    stageEl.style.opacity = '1'

    window.setTimeout(() => {
      isIndicatorBlurred.value = false
      stageEl.style.transition = ''
      stageEl.style.willChange = ''
      isTransitioning.value = false
    }, 150)
    return
  }

  // 第一阶段：旧版本以严格 150ms 滑出并淡出 (PRD 4.3)
  const exitOffsetPx = direction === 'next' ? -68 : 68
  stageEl.style.transition =
    'transform 150ms cubic-bezier(0.4, 0, 1, 1), opacity 150ms ease-out'
  stageEl.style.transform = `translate3d(${exitOffsetPx}px, 0, 0)`
  stageEl.style.opacity = '0'

  await new Promise<void>((resolve) => {
    window.setTimeout(resolve, 150)
  })

  // 切换版本数据源（底部控制栏保持原位 DOM 不重建）
  chatStore.switchMessageVersion(props.message.id, targetIndex)
  await nextTick()

  // 第二阶段：新版本预置在 150px 摆幅起点，随后以弹性阻尼 cubic-bezier(0.34, 1.56, 0.64, 1) 滑入 (PRD 4.3)
  const enterStartPx = direction === 'next' ? 150 : -150
  stageEl.style.transition = 'none'
  stageEl.style.transform = `translate3d(${enterStartPx}px, 0, 0)`
  stageEl.style.opacity = '0.2'

  // 强制浏览器同步回流以激活动画起点
  void stageEl.offsetWidth

  stageEl.style.transition =
    'transform 460ms cubic-bezier(0.34, 1.56, 0.64, 1), opacity 280ms ease-out'
  stageEl.style.transform = 'translate3d(0, 0, 0)'
  stageEl.style.opacity = '1'

  window.setTimeout(() => {
    isIndicatorBlurred.value = false
  }, 90)

  window.setTimeout(() => {
    stageEl.style.transition = ''
    stageEl.style.transform = ''
    stageEl.style.opacity = ''
    stageEl.style.willChange = ''
    isTransitioning.value = false
  }, 470)
}

// 当外部（例如无损重试流式开始）直接追加新版本并切换索引时，同步触发底栏页码消模糊过渡
watch(
  () => props.message.currentVersionIndex,
  (newIdx, oldIdx) => {
    if (isTransitioning.value || newIdx === oldIdx) return
    isIndicatorBlurred.value = true
    if (typeof window !== 'undefined') {
      window.setTimeout(() => {
        isIndicatorBlurred.value = false
      }, 180)
    }
  },
)
</script>

<template>
  <div class="version-switcher-root space-y-2.5">
    <!-- 可选包裹插槽：用于承载需要视差翻页的回答正文与思考抽屉 -->
    <div v-if="hasSlotContent" ref="localStageRef" class="version-parallax-stage">
      <slot />
    </div>

    <!-- 底部持久版本控制栏：原位微调，不整体重建 DOM (PRD 4.3) -->
    <div
      v-if="totalVersions > 1"
      data-motion-anchor="parallax-version-switcher"
      class="inline-flex items-center gap-2 px-3 py-1.5 rounded-xl bg-elevated/75 border border-subtle text-xs select-none shadow-sm"
    >
      <Layers class="w-3.5 h-3.5 text-primary-light shrink-0" />

      <button
        type="button"
        :disabled="currentIndex <= 0 || isTransitioning"
        class="p-0.5 rounded-lg hover:bg-card hover:text-primary-light disabled:opacity-35 disabled:cursor-not-allowed transition-colors"
        title="切换到上一版本回答"
        @click="handleSwitchVersion(currentIndex - 1)"
      >
        <ChevronLeft class="w-3.5 h-3.5" />
      </button>

      <!-- 页码指示器：切换时带短暂 filter: blur(3px) 原位微调过渡 -->
      <span
        class="version-page-indicator font-mono text-[11px] font-semibold text-main px-1 min-w-[74px] text-center"
        :class="isIndicatorBlurred ? 'is-blurring' : ''"
      >
        版本 {{ currentIndex + 1 }} / {{ totalVersions }}
      </span>

      <button
        type="button"
        :disabled="currentIndex >= totalVersions - 1 || isTransitioning"
        class="p-0.5 rounded-lg hover:bg-card hover:text-primary-light disabled:opacity-35 disabled:cursor-not-allowed transition-colors"
        title="切换到下一版本回答"
        @click="handleSwitchVersion(currentIndex + 1)"
      >
        <ChevronRight class="w-3.5 h-3.5" />
      </button>

      <!-- 分支圆点进度指示（原位高亮切换） -->
      <div class="hidden sm:flex items-center gap-1 pl-1.5 border-l border-subtle">
        <button
          v-for="(ver, idx) in message.versions"
          :key="ver.versionId"
          type="button"
          class="h-1.5 rounded-full transition-all duration-300"
          :class="
            idx === currentIndex
              ? 'w-4 bg-primary-light shadow-cyber-glow'
              : 'w-1.5 bg-muted/40 hover:bg-muted'
          "
          :title="`跳转至第 ${idx + 1} 页回答版本`"
          @click="handleSwitchVersion(idx)"
        />
      </div>

      <span
        v-if="currentVersionMeta?.isMock"
        class="hidden md:inline-block text-[10px] font-mono text-amber-400/90 pl-1"
      >
        · Mock分支
      </span>
    </div>
  </div>
</template>

<style scoped>
.version-page-indicator {
  display: inline-block;
  filter: blur(0px);
  opacity: 1;
  transform: translateY(0);
  transition:
    filter 160ms cubic-bezier(0.22, 1, 0.36, 1),
    opacity 160ms ease,
    transform 160ms ease;
}

.version-page-indicator.is-blurring {
  filter: blur(3px);
  opacity: 0.45;
  transform: scale(0.96);
}
</style>
