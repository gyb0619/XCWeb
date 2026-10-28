<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useThemeSweep } from '@/composables/useThemeSweep'
import { useConfigStore } from '@/stores/configStore'
import type { ThemeMode } from '@/types/api'

const configStore = useConfigStore()
const {
  isActive,
  isReducedMotion,
  toTheme,
  clipPathPolygon,
  svgPolygonPoints,
  wavefrontLine,
  overlayOpacity,
  triggerThemeSweep,
  cancelActiveSweep,
} = useThemeSweep()

const themePalette = computed(() => {
  const map: Record<
    ThemeMode,
    { start: string; mid: string; edge: string; glow: string }
  > = {
    dark: {
      start: '#0B0F19',
      mid: '#111827',
      edge: '#3B82F6',
      glow: '#06B6D4',
    },
    light: {
      start: '#F8FAFC',
      mid: '#EFF6FF',
      edge: '#2563EB',
      glow: '#06B6D4',
    },
    incognito: {
      start: '#07090E',
      mid: '#141829',
      edge: '#6366F1',
      glow: '#06B6D4',
    },
  }
  return map[toTheme.value]
})

onMounted(() => {
  configStore.registerThemeSweepHandler(async (targetTheme: ThemeMode) => {
    await triggerThemeSweep(targetTheme, configStore.theme, (nextTheme) => {
      if (typeof document !== 'undefined') {
        document.documentElement.setAttribute('data-theme', nextTheme)
      }
    })
  })
})

onUnmounted(() => {
  cancelActiveSweep()
})
</script>

<template>
  <div
    v-show="isActive"
    class="theme-sweep-overlay pointer-events-none fixed inset-0 z-40 overflow-hidden"
    :style="{
      clipPath: clipPathPolygon,
      opacity: overlayOpacity,
      willChange: 'clip-path, opacity',
    }"
    aria-hidden="true"
  >
    <svg
      class="w-full h-full block"
      viewBox="0 0 100 100"
      preserveAspectRatio="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        <!-- 135° 对角线（左上 -> 右下）科技蓝扫屏渐变 -->
        <linearGradient id="themeSweepDiagGrad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" :stop-color="themePalette.start" stop-opacity="0.96" />
          <stop offset="72%" :stop-color="themePalette.mid" stop-opacity="0.9" />
          <stop offset="94%" :stop-color="themePalette.edge" stop-opacity="0.65" />
          <stop offset="100%" :stop-color="themePalette.glow" stop-opacity="0.85" />
        </linearGradient>

        <linearGradient id="themeSweepLaserGrad" x1="0%" y1="100%" x2="100%" y2="0%">
          <stop offset="0%" :stop-color="themePalette.edge" stop-opacity="0.3" />
          <stop offset="50%" :stop-color="themePalette.glow" stop-opacity="0.95" />
          <stop offset="100%" :stop-color="themePalette.edge" stop-opacity="0.3" />
        </linearGradient>
      </defs>

      <!-- 全屏 135° 对角线动态插值 Polygon -->
      <polygon :points="svgPolygonPoints" fill="url(#themeSweepDiagGrad)" />

      <!-- 135° 扫描锋面激光能量线（仅在非减少动态效果模式下渲染） -->
      <line
        v-if="!isReducedMotion"
        :x1="wavefrontLine.x1"
        :y1="wavefrontLine.y1"
        :x2="wavefrontLine.x2"
        :y2="wavefrontLine.y2"
        stroke="url(#themeSweepLaserGrad)"
        stroke-width="1.2"
        stroke-linecap="round"
      />
    </svg>
  </div>
</template>
