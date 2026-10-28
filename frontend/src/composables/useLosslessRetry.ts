import { readonly, ref } from 'vue'
import { useChatStore } from '@/stores/chatStore'

interface Point2D {
  x: number
  y: number
}

const FLIGHT_DURATION_MS = 600
const REDUCED_MOTION_DURATION_MS = 180

const isFlying = ref<boolean>(false)
const activeRetryMsgId = ref<string | null>(null)

function isReducedMotionPreferred(): boolean {
  if (typeof window === 'undefined') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * 三次缓动函数（保障 600ms 贝塞尔曲线飞移起止顺滑）
 */
function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2
}

/**
 * 计算三次贝塞尔曲线 B(t) = (1-t)^3 P0 + 3(1-t)^2 t P1 + 3(1-t) t^2 P2 + t^3 P3
 */
function evaluateCubicBezier(
  t: number,
  p0: Point2D,
  p1: Point2D,
  p2: Point2D,
  p3: Point2D,
): Point2D {
  const u = 1 - t
  const tt = t * t
  const uu = u * u
  const uuu = uu * u
  const ttt = tt * t

  return {
    x: uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x,
    y: uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y,
  }
}

function findSourceBubbleElement(
  msgId: string,
  explicitEl?: HTMLElement | null,
): HTMLElement | null {
  if (explicitEl) {
    if (explicitEl.classList.contains('message-user-bubble')) {
      return explicitEl
    }
    const nested = explicitEl.querySelector<HTMLElement>('.message-user-bubble')
    if (nested) return nested
  }
  if (typeof document === 'undefined') return null
  return document.querySelector<HTMLElement>(
    `[data-message-id="${msgId}"] .message-user-bubble`,
  )
}

function findChatInputTarget(): HTMLElement | null {
  if (typeof document === 'undefined') return null
  return document.querySelector<HTMLElement>('.chat-input-target')
}

/**
 * 无损历史重试核心执行函数 (PRD 4.3)：
 * 1. 不截断、不删除后续对话
 * 2. 原提问气泡向左偏移 150px 并弱化
 * 3. 动态克隆 .message-retry-replica 沿三次贝塞尔曲线用时 600ms 飞向底部 .chat-input-target
 * 4. 飞行结束后清理克隆节点，调用 chatStore.executeLosslessRetry(msgId) 追加新版本回答
 */
export async function handleLosslessRetry(
  msgId: string,
  sourceEl?: HTMLElement | null,
): Promise<void> {
  const chatStore = useChatStore()
  if (chatStore.isGenerating || isFlying.value) return

  const bubbleEl = findSourceBubbleElement(msgId, sourceEl)
  const targetInputEl = findChatInputTarget()

  // 在原气泡触发左移 150px 前先测量起始坐标
  const startRect = bubbleEl?.getBoundingClientRect() ?? null
  const targetRect = targetInputEl?.getBoundingClientRect() ?? null

  // 标记原提问气泡左移 150px 并弱化（不截断后续对话）
  const prepared = chatStore.prepareLosslessRetry(msgId)
  if (!prepared) return

  activeRetryMsgId.value = msgId
  isFlying.value = true

  try {
    if (
      typeof window !== 'undefined' &&
      typeof document !== 'undefined' &&
      bubbleEl &&
      startRect &&
      targetRect
    ) {
      const reducedMotion = isReducedMotionPreferred()

      // 动态克隆原提问气泡 DOM 节点，类名包含 .message-retry-replica (PRD 4.3)
      const replica = bubbleEl.cloneNode(true) as HTMLElement
      replica.classList.add('message-retry-replica')
      replica.removeAttribute('id')

      // 移除可能干扰定位的内联偏移类，统一由 translate3d 硬件加速驱动
      replica.classList.remove('-translate-x-[150px]', 'opacity-45')

      Object.assign(replica.style, {
        position: 'fixed',
        left: '0px',
        top: '0px',
        width: `${startRect.width}px`,
        height: `${startRect.height}px`,
        margin: '0',
        zIndex: '9999',
        pointerEvents: 'none',
        transformOrigin: 'center center',
        willChange: 'transform, opacity',
        transition: 'none',
        boxShadow:
          '0 12px 32px -4px rgba(37, 99, 235, 0.55), 0 0 20px 0 rgba(6, 182, 212, 0.45)',
        border: '1px solid rgba(6, 182, 212, 0.75)',
        transform: `translate3d(${startRect.left}px, ${startRect.top}px, 0) scale(1)`,
        opacity: '0.96',
      } satisfies Partial<CSSStyleDeclaration>)

      document.body.appendChild(replica)

      if (reducedMotion) {
        // 无障碍降级模式：仅做简单淡入淡出过渡，不产生大位移 (PRD 4.3)
        await new Promise<void>((resolve) => {
          replica.style.transition = `opacity ${REDUCED_MOTION_DURATION_MS}ms ease-out`
          window.requestAnimationFrame(() => {
            replica.style.opacity = '0'
          })
          window.setTimeout(() => {
            resolve()
          }, REDUCED_MOTION_DURATION_MS)
        })
      } else {
        // 计算三次贝塞尔曲线控制点：先顺着左移 150px 惯性向外舒展，再划出优美弧线飞入底部输入框
        const p0: Point2D = {
          x: startRect.left,
          y: startRect.top,
        }
        const p3: Point2D = {
          x: targetRect.left + Math.min(36, targetRect.width * 0.08),
          y: targetRect.top + Math.max(8, (targetRect.height - startRect.height * 0.72) * 0.35),
        }
        const deltaY = p3.y - p0.y
        const p1: Point2D = {
          x: Math.max(16, p0.x - 150),
          y: p0.y + deltaY * 0.22,
        }
        const p2: Point2D = {
          x: Math.max(24, p3.x - 75),
          y: p0.y + deltaY * 0.78,
        }

        const startTime = performance.now()

        await new Promise<void>((resolve) => {
          const tick = (now: number) => {
            const elapsed = now - startTime
            const rawProgress = Math.min(1, Math.max(0, elapsed / FLIGHT_DURATION_MS))
            const eased = easeInOutCubic(rawProgress)

            const pos = evaluateCubicBezier(eased, p0, p1, p2, p3)
            const scale = 1 - eased * 0.22
            const opacity =
              rawProgress < 0.84
                ? 0.96 - rawProgress * 0.12
                : Math.max(0, 0.86 * (1 - (rawProgress - 0.84) / 0.16))

            replica.style.transform = `translate3d(${pos.x.toFixed(2)}px, ${pos.y.toFixed(2)}px, 0) scale(${scale.toFixed(3)})`
            replica.style.opacity = opacity.toFixed(3)

            if (rawProgress < 1) {
              window.requestAnimationFrame(tick)
            } else {
              resolve()
            }
          }

          window.requestAnimationFrame(tick)
        })

        // 飞入底部输入框瞬间触发微光脉冲反馈
        if (targetInputEl) {
          targetInputEl.classList.add('ring-2', 'ring-cyber-cyan/70', 'shadow-cyber-cyan')
          window.setTimeout(() => {
            targetInputEl.classList.remove('ring-2', 'ring-cyber-cyan/70', 'shadow-cyber-cyan')
          }, 360)
        }
      }

      // 清理克隆节点
      if (replica.parentNode) {
        replica.parentNode.removeChild(replica)
      }
    }

    // 飞行完成后立即执行无损重试生成，将新回答作为第 2 页追加至版本切换器
    await chatStore.executeLosslessRetry(msgId)
  } finally {
    isFlying.value = false
    activeRetryMsgId.value = null
  }
}

export function useLosslessRetry() {
  return {
    isFlying: readonly(isFlying),
    activeRetryMsgId: readonly(activeRetryMsgId),
    triggerLosslessRetry: handleLosslessRetry,
    handleLosslessRetry,
  }
}
