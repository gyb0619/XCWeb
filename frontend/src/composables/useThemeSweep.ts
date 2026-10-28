import { readonly, ref } from 'vue'
import type { ThemeMode } from '@/types/api'

export interface ThemeSweepState {
  isActive: boolean
  isReducedMotion: boolean
  fromTheme: ThemeMode
  toTheme: ThemeMode
  progress: number
  clipPathPolygon: string
  svgPolygonPoints: string
  wavefrontLine: {
    x1: number
    y1: number
    x2: number
    y2: number
  }
  overlayOpacity: number
}

const SWEEP_DURATION_MS = 500

const isActive = ref<boolean>(false)
const isReducedMotion = ref<boolean>(false)
const fromTheme = ref<ThemeMode>('dark')
const toTheme = ref<ThemeMode>('light')
const progress = ref<number>(0)
const clipPathPolygon = ref<string>('polygon(0% 0%, 0% 0%, 0% 0%, 0% 0%, 0% 0%)')
const svgPolygonPoints = ref<string>('0,0 0,0 0,0 0,0 0,0')
const wavefrontLine = ref<{ x1: number; y1: number; x2: number; y2: number }>({
  x1: 0,
  y1: 0,
  x2: 0,
  y2: 0,
})
const overlayOpacity = ref<number>(0)

let activeRafId: number | null = null
let activeResolve: (() => void) | null = null

function checkReducedMotion(): boolean {
  if (typeof window === 'undefined') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * 三次缓动函数（保证 500ms 内 135° 对角线扫屏丝滑加减速）
 */
function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2
}

/**
 * 根据对角线推进距离 S ∈ [0, 200] 计算 135° 方向（左上 -> 右下）五边形顶点
 */
function computeDiagonalGeometry(distanceS: number) {
  const clamped = Math.max(0, Math.min(200, distanceS))
  const x1 = Number(Math.min(100, clamped).toFixed(2))
  const y1 = Number(Math.max(0, clamped - 100).toFixed(2))
  const x2 = Number(Math.max(0, clamped - 100).toFixed(2))
  const y2 = Number(Math.min(100, clamped).toFixed(2))

  return {
    clipPath: `polygon(0% 0%, ${x1}% 0%, ${x1}% ${y1}%, ${x2}% ${y2}%, 0% ${y2}%)`,
    svgPoints: `0,0 ${x1},0 ${x1},${y1} ${x2},${y2} 0,${y2}`,
    line: { x1, y1, x2, y2 },
  }
}

export function useThemeSweep() {
  function cancelActiveSweep() {
    if (activeRafId !== null && typeof window !== 'undefined') {
      window.cancelAnimationFrame(activeRafId)
      activeRafId = null
    }
    if (activeResolve) {
      const resolve = activeResolve
      activeResolve = null
      resolve()
    }
  }

  /**
   * 触发 135° 对角线扫屏切换主题 (PRD 4.3：严格 500ms，扫屏期间无缝完成主题变量切换)
   */
  function triggerThemeSweep(
    targetTheme: ThemeMode,
    currentTheme: ThemeMode,
    onMidpointApplyTheme?: (next: ThemeMode) => void,
  ): Promise<void> {
    if (typeof window === 'undefined') {
      onMidpointApplyTheme?.(targetTheme)
      return Promise.resolve()
    }

    cancelActiveSweep()

    const reduced = checkReducedMotion()
    isReducedMotion.value = reduced
    fromTheme.value = currentTheme
    toTheme.value = targetTheme
    isActive.value = true
    progress.value = 0

    document.documentElement.setAttribute('data-theme-sweeping', 'true')

    // 无障碍降级模式：简单淡入淡出过渡，不产生对角线大位移 (PRD 4.3)
    if (reduced) {
      clipPathPolygon.value = 'polygon(0% 0%, 100% 0%, 100% 100%, 0% 100%, 0% 100%)'
      svgPolygonPoints.value = '0,0 100,0 100,100 100,100 0,100'
      overlayOpacity.value = 0.45
      onMidpointApplyTheme?.(targetTheme)

      return new Promise<void>((resolve) => {
        activeResolve = resolve
        window.setTimeout(() => {
          overlayOpacity.value = 0
          isActive.value = false
          document.documentElement.removeAttribute('data-theme-sweeping')
          activeResolve = null
          resolve()
        }, 180)
      })
    }

    // 标准 135° SVG Polygon + clip-path 500ms 扫屏模式
    const startGeom = computeDiagonalGeometry(0)
    clipPathPolygon.value = startGeom.clipPath
    svgPolygonPoints.value = startGeom.svgPoints
    wavefrontLine.value = startGeom.line
    overlayOpacity.value = 0.92

    let themeSwitchedMidway = false
    const startTime = performance.now()

    return new Promise<void>((resolve) => {
      activeResolve = resolve

      const step = (now: number) => {
        const elapsed = now - startTime
        const rawT = Math.min(1, Math.max(0, elapsed / SWEEP_DURATION_MS))
        const easedT = easeInOutCubic(rawT)

        progress.value = easedT

        // S 从 0 推进至 200（沿 135° 对角线从左上角 (0,0) 扫向右下角 (100,100)）
        const distanceS = easedT * 200
        const geom = computeDiagonalGeometry(distanceS)

        clipPathPolygon.value = geom.clipPath
        svgPolygonPoints.value = geom.svgPoints
        wavefrontLine.value = geom.line

        // 在扫屏锋面越过屏幕中轴时无缝切换底层 CSS 主题变量，避免任何撕裂或闪烁
        if (!themeSwitchedMidway && rawT >= 0.44) {
          themeSwitchedMidway = true
          onMidpointApplyTheme?.(targetTheme)
        }

        // 后半段平滑消散遮罩层，让新主题界面自然透出
        if (rawT < 0.55) {
          overlayOpacity.value = 0.92
        } else {
          const fadeOutRatio = (rawT - 0.55) / 0.45
          overlayOpacity.value = Number((0.92 * (1 - fadeOutRatio)).toFixed(3))
        }

        if (rawT < 1) {
          activeRafId = window.requestAnimationFrame(step)
        } else {
          if (!themeSwitchedMidway) {
            onMidpointApplyTheme?.(targetTheme)
          }
          isActive.value = false
          overlayOpacity.value = 0
          document.documentElement.removeAttribute('data-theme-sweeping')
          activeRafId = null
          activeResolve = null
          resolve()
        }
      }

      activeRafId = window.requestAnimationFrame(step)
    })
  }

  return {
    isActive: readonly(isActive),
    isReducedMotion: readonly(isReducedMotion),
    fromTheme: readonly(fromTheme),
    toTheme: readonly(toTheme),
    progress: readonly(progress),
    clipPathPolygon: readonly(clipPathPolygon),
    svgPolygonPoints: readonly(svgPolygonPoints),
    wavefrontLine: readonly(wavefrontLine),
    overlayOpacity: readonly(overlayOpacity),
    triggerThemeSweep,
    cancelActiveSweep,
  }
}
