<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

interface NeuralParticle {
  x: number
  y: number
  vx: number
  vy: number
  radius: number
  color: string
  rgb: string
  pulsePhase: number
  pulseSpeed: number
}

const props = withDefaults(
  defineProps<{
    maxParticles?: number
    connectionDistance?: number
    cursorRadius?: number
  }>(),
  {
    maxParticles: 68,
    connectionDistance: 145,
    cursorRadius: 190,
  },
)

const canvasRef = ref<HTMLCanvasElement | null>(null)
const isStaticFallback = ref<boolean>(false)
const isCanvasReady = ref<boolean>(false)

// 科技深邃蓝设计系统粒子色盘 (PRD 4.1 & 4.2)
const CYBER_PALETTE: Array<{ hex: string; rgb: string }> = [
  { hex: '#2563EB', rgb: '37, 99, 235' }, // Royal Blue
  { hex: '#3B82F6', rgb: '59, 130, 246' }, // Electric Blue
  { hex: '#06B6D4', rgb: '6, 182, 212' }, // Cyber Cyan
]

let animationFrameId: number | null = null
let initFrameId: number | null = null
let idleCallbackId: number | null = null
let initTimeoutId: number | null = null
let mediaQueryList: MediaQueryList | null = null
let particles: NeuralParticle[] = []
let viewportWidth = 0
let viewportHeight = 0

const pointer = {
  x: -9999,
  y: -9999,
  active: false,
}

function shouldDegradeToStatic(): boolean {
  if (typeof window === 'undefined') return true
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const lowConcurrency =
    typeof navigator !== 'undefined' &&
    typeof navigator.hardwareConcurrency === 'number' &&
    navigator.hardwareConcurrency <= 2
  return reducedMotion || lowConcurrency
}

function createParticles(width: number, height: number): NeuralParticle[] {
  const areaScale = Math.min(1, (width * height) / (1440 * 900))
  const count = Math.max(28, Math.floor(props.maxParticles * Math.max(0.55, areaScale)))
  const list: NeuralParticle[] = []

  for (let i = 0; i < count; i++) {
    const paletteItem = CYBER_PALETTE[i % CYBER_PALETTE.length]
    list.push({
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.65,
      vy: (Math.random() - 0.5) * 0.65,
      radius: 1.4 + Math.random() * 1.8,
      color: paletteItem.hex,
      rgb: paletteItem.rgb,
      pulsePhase: Math.random() * Math.PI * 2,
      pulseSpeed: 0.02 + Math.random() * 0.025,
    })
  }
  return list
}

function syncCanvasSize() {
  const canvas = canvasRef.value
  if (!canvas || typeof window === 'undefined') return

  const rect = canvas.getBoundingClientRect()
  viewportWidth = rect.width || window.innerWidth
  viewportHeight = rect.height || window.innerHeight

  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  canvas.width = Math.floor(viewportWidth * dpr)
  canvas.height = Math.floor(viewportHeight * dpr)

  const ctx = canvas.getContext('2d')
  if (ctx) {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }

  if (particles.length === 0) {
    particles = createParticles(viewportWidth, viewportHeight)
  }
}

function renderFrame() {
  if (isStaticFallback.value) return

  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.clearRect(0, 0, viewportWidth, viewportHeight)

  const maxDist = props.connectionDistance
  const maxDistSq = maxDist * maxDist
  const cursorDist = props.cursorRadius
  const cursorDistSq = cursorDist * cursorDist

  // 更新并绘制粒子节点
  for (let i = 0; i < particles.length; i++) {
    const p = particles[i]
    p.x += p.vx
    p.y += p.vy
    p.pulsePhase += p.pulseSpeed

    // 边界弹性反弹
    if (p.x <= 0 || p.x >= viewportWidth) {
      p.vx *= -1
      p.x = Math.max(0, Math.min(viewportWidth, p.x))
    }
    if (p.y <= 0 || p.y >= viewportHeight) {
      p.vy *= -1
      p.y = Math.max(0, Math.min(viewportHeight, p.y))
    }

    // 光标微引力交互
    if (pointer.active) {
      const dxCursor = pointer.x - p.x
      const dyCursor = pointer.y - p.y
      const distSqCursor = dxCursor * dxCursor + dyCursor * dyCursor

      if (distSqCursor > 100 && distSqCursor < cursorDistSq) {
        const dist = Math.sqrt(distSqCursor)
        const pull = ((cursorDist - dist) / cursorDist) * 0.022
        p.x += (dxCursor / dist) * pull * 8
        p.y += (dyCursor / dist) * pull * 8
      }
    }

    const glowAlpha = 0.55 + Math.sin(p.pulsePhase) * 0.3
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2)
    ctx.fillStyle = `rgba(${p.rgb}, ${glowAlpha.toFixed(2)})`
    ctx.fill()

    // 粒子间神经突触距离感应连线
    for (let j = i + 1; j < particles.length; j++) {
      const q = particles[j]
      const dx = p.x - q.x
      const dy = p.y - q.y
      const distSq = dx * dx + dy * dy

      if (distSq < maxDistSq) {
        const ratio = 1 - Math.sqrt(distSq) / maxDist
        const lineAlpha = ratio * 0.28
        ctx.beginPath()
        ctx.moveTo(p.x, p.y)
        ctx.lineTo(q.x, q.y)
        ctx.strokeStyle = `rgba(${p.rgb}, ${lineAlpha.toFixed(3)})`
        ctx.lineWidth = 0.85
        ctx.stroke()
      }
    }

    // 光标高光流光突触连线
    if (pointer.active) {
      const dxCursor = pointer.x - p.x
      const dyCursor = pointer.y - p.y
      const distSqCursor = dxCursor * dxCursor + dyCursor * dyCursor

      if (distSqCursor < cursorDistSq) {
        const dist = Math.sqrt(distSqCursor)
        const ratio = 1 - dist / cursorDist
        const streamAlpha = Math.min(0.78, ratio * 0.72)

        const gradient = ctx.createLinearGradient(p.x, p.y, pointer.x, pointer.y)
        gradient.addColorStop(0, `rgba(${p.rgb}, ${(streamAlpha * 0.45).toFixed(3)})`)
        gradient.addColorStop(0.5, `rgba(59, 130, 246, ${streamAlpha.toFixed(3)})`)
        gradient.addColorStop(1, `rgba(6, 182, 212, ${streamAlpha.toFixed(3)})`)

        ctx.beginPath()
        ctx.moveTo(p.x, p.y)
        ctx.lineTo(pointer.x, pointer.y)
        ctx.strokeStyle = gradient
        ctx.lineWidth = 1.25
        ctx.stroke()

        // 流光脉冲节点（沿突触连线向光标流动）
        const flowT = (p.pulsePhase * 0.35) % 1
        const flowX = p.x + dxCursor * flowT
        const flowY = p.y + dyCursor * flowT
        ctx.beginPath()
        ctx.arc(flowX, flowY, 1.6, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(6, 182, 212, ${(ratio * 0.85).toFixed(2)})`
        ctx.fill()
      }
    }
  }

  animationFrameId = window.requestAnimationFrame(renderFrame)
}

function startParticleEngine() {
  if (shouldDegradeToStatic()) {
    isStaticFallback.value = true
    stopParticleEngine()
    return
  }

  isStaticFallback.value = false
  syncCanvasSize()
  isCanvasReady.value = true

  if (animationFrameId === null) {
    animationFrameId = window.requestAnimationFrame(renderFrame)
  }
}

function stopParticleEngine() {
  if (animationFrameId !== null && typeof window !== 'undefined') {
    window.cancelAnimationFrame(animationFrameId)
    animationFrameId = null
  }
}

function handlePointerMove(e: MouseEvent) {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  pointer.x = e.clientX - rect.left
  pointer.y = e.clientY - rect.top
  pointer.active = true
}

function handlePointerLeave() {
  pointer.active = false
  pointer.x = -9999
  pointer.y = -9999
}

function handleVisibilityChange() {
  if (typeof document === 'undefined') return
  if (document.hidden) {
    stopParticleEngine()
  } else if (!isStaticFallback.value && isCanvasReady.value) {
    startParticleEngine()
  }
}

function handleMotionPreferenceChange() {
  if (shouldDegradeToStatic()) {
    isStaticFallback.value = true
    stopParticleEngine()
  } else {
    startParticleEngine()
  }
}

onMounted(() => {
  if (typeof window === 'undefined') return

  isStaticFallback.value = shouldDegradeToStatic()
  mediaQueryList = window.matchMedia('(prefers-reduced-motion: reduce)')
  mediaQueryList.addEventListener('change', handleMotionPreferenceChange)
  window.addEventListener('resize', syncCanvasSize, { passive: true })
  window.addEventListener('mousemove', handlePointerMove, { passive: true })
  window.addEventListener('mouseleave', handlePointerLeave, { passive: true })
  document.addEventListener('visibilitychange', handleVisibilityChange)

  // PRD 4.2 & 7：必须在首屏渲染完成后（requestAnimationFrame + requestIdleCallback/setTimeout）再初始化粒子场，保障 FCP <= 1.2s
  initFrameId = window.requestAnimationFrame(() => {
    const winWithIdle = window as Window & {
      requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number
    }
    if (typeof winWithIdle.requestIdleCallback === 'function') {
      idleCallbackId = winWithIdle.requestIdleCallback(
        () => {
          startParticleEngine()
        },
        { timeout: 300 },
      )
    } else {
      initTimeoutId = window.setTimeout(() => {
        startParticleEngine()
      }, 48)
    }
  })
})

onUnmounted(() => {
  stopParticleEngine()
  if (typeof window !== 'undefined') {
    if (initFrameId !== null) window.cancelAnimationFrame(initFrameId)
    const winWithIdle = window as Window & {
      cancelIdleCallback?: (id: number) => void
    }
    if (idleCallbackId !== null && typeof winWithIdle.cancelIdleCallback === 'function') {
      winWithIdle.cancelIdleCallback(idleCallbackId)
    }
    if (initTimeoutId !== null) window.clearTimeout(initTimeoutId)
    window.removeEventListener('resize', syncCanvasSize)
    window.removeEventListener('mousemove', handlePointerMove)
    window.removeEventListener('mouseleave', handlePointerLeave)
  }
  if (typeof document !== 'undefined') {
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  }
  if (mediaQueryList) {
    mediaQueryList.removeEventListener('change', handleMotionPreferenceChange)
  }
})
</script>

<template>
  <div
    class="neural-canvas-container absolute inset-0 overflow-hidden pointer-events-none select-none"
    aria-hidden="true"
  >
    <!-- 科技深邃蓝静态渐变基底（首屏直出 & prefers-reduced-motion 降级背景） -->
    <div
      class="absolute inset-0 bg-[#0B0F19]"
      style="
        background-image:
          radial-gradient(circle at 18% 22%, rgba(37, 99, 235, 0.24) 0%, transparent 48%),
          radial-gradient(circle at 82% 76%, rgba(6, 182, 212, 0.18) 0%, transparent 50%),
          radial-gradient(circle at 50% 50%, rgba(59, 130, 246, 0.1) 0%, transparent 65%),
          linear-gradient(135deg, #070a12 0%, #0b0f19 52%, #0f172a 100%);
      "
    />

    <!-- 科技网格微光纹理层 -->
    <div
      class="absolute inset-0 opacity-20"
      style="
        background-image:
          linear-gradient(rgba(59, 130, 246, 0.12) 1px, transparent 1px),
          linear-gradient(90deg, rgba(59, 130, 246, 0.12) 1px, transparent 1px);
        background-size: 48px 48px;
        mask-image: radial-gradient(ellipse at center, black 35%, transparent 80%);
      "
    />

    <!-- Canvas 2D 神经突触粒子层（首屏绘制完成后平滑浮现） -->
    <canvas
      v-if="!isStaticFallback"
      ref="canvasRef"
      class="absolute inset-0 w-full h-full transition-opacity duration-700"
      :class="isCanvasReady ? 'opacity-100' : 'opacity-0'"
    />
  </div>
</template>
