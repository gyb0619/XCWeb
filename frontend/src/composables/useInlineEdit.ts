import { nextTick, readonly, ref } from 'vue'
import { useChatStore } from '@/stores/chatStore'
import type { Message } from '@/types/api'

const EXPAND_DURATION_MS = 340
const COLLAPSE_DURATION_MS = 320
const REDUCED_DURATION_MS = 140

const editingMessageId = ref<string | null>(null)
const editingDraft = ref<string>('')
const isMorphing = ref<boolean>(false)

function isReducedMotionPreferred(): boolean {
  if (typeof window === 'undefined') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function findBubbleElement(
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

function resetInlineBoxStyles(el: HTMLElement) {
  el.style.width = ''
  el.style.height = ''
  el.style.maxWidth = ''
  el.style.overflow = ''
  el.style.transition = ''
  el.style.willChange = ''
}

/**
 * 自适应内联编辑展开动效 (PRD 4.3)：
 * 测量当前气泡尺寸 (getBoundingClientRect) 与目标全宽编辑框尺寸，通过 CSS transition 平滑展开
 */
export async function handleInlineEdit(
  msg: Message,
  explicitEl?: HTMLElement | null,
): Promise<void> {
  if (isMorphing.value) return

  // 若已有正在编辑的另一条消息，先重置
  if (editingMessageId.value && editingMessageId.value !== msg.id) {
    const prevEl = findBubbleElement(editingMessageId.value)
    if (prevEl) resetInlineBoxStyles(prevEl)
  }

  const bubbleEl = findBubbleElement(msg.id, explicitEl)
  const startRect = bubbleEl?.getBoundingClientRect() ?? null
  const parentWidth =
    bubbleEl?.parentElement?.clientWidth ??
    (typeof window !== 'undefined' ? Math.min(window.innerWidth - 48, 860) : 680)

  editingDraft.value = msg.content
  editingMessageId.value = msg.id

  await nextTick()

  if (!bubbleEl || !startRect || typeof window === 'undefined') {
    return
  }

  isMorphing.value = true
  const reduced = isReducedMotionPreferred()

  if (reduced) {
    // 无障碍降级模式：仅使用简单淡入过渡
    bubbleEl.style.width = '100%'
    bubbleEl.style.maxWidth = 'none'
    bubbleEl.style.opacity = '0.4'
    void bubbleEl.offsetWidth
    bubbleEl.style.transition = `opacity ${REDUCED_DURATION_MS}ms ease-out`
    bubbleEl.style.opacity = '1'

    window.setTimeout(() => {
      bubbleEl.style.transition = ''
      bubbleEl.style.opacity = ''
      isMorphing.value = false
      const textarea = bubbleEl.querySelector<HTMLTextAreaElement>('textarea')
      textarea?.focus()
    }, REDUCED_DURATION_MS)
    return
  }

  // 1. 临时以目标全宽测量编辑态自然高度
  bubbleEl.style.transition = 'none'
  bubbleEl.style.width = `${parentWidth}px`
  bubbleEl.style.height = 'auto'
  bubbleEl.style.maxWidth = 'none'
  const targetRect = bubbleEl.getBoundingClientRect()
  const targetWidth = parentWidth
  const targetHeight = Math.max(128, targetRect.height)

  // 2. 锁定在起始气泡宽高尺寸
  bubbleEl.style.width = `${startRect.width}px`
  bubbleEl.style.height = `${startRect.height}px`
  bubbleEl.style.overflow = 'hidden'
  bubbleEl.style.willChange = 'width, height'

  // 强制同步回流
  void bubbleEl.offsetWidth

  // 3. 驱动 CSS transition 平滑展开至全宽编辑框
  bubbleEl.style.transition = `width ${EXPAND_DURATION_MS}ms cubic-bezier(0.22, 1, 0.36, 1), height ${EXPAND_DURATION_MS}ms cubic-bezier(0.22, 1, 0.36, 1), background-color 240ms ease, border-color 240ms ease`
  bubbleEl.style.width = `${targetWidth}px`
  bubbleEl.style.height = `${targetHeight}px`

  await new Promise<void>((resolve) => {
    window.setTimeout(resolve, EXPAND_DURATION_MS)
  })

  bubbleEl.style.width = '100%'
  bubbleEl.style.height = 'auto'
  bubbleEl.style.overflow = ''
  bubbleEl.style.transition = ''
  bubbleEl.style.willChange = ''
  isMorphing.value = false

  const textarea = bubbleEl.querySelector<HTMLTextAreaElement>('textarea')
  if (textarea) {
    textarea.focus()
    textarea.setSelectionRange(textarea.value.length, textarea.value.length)
  }
}

/**
 * 内部辅助：测量收拢后的目标气泡宽高并平滑过渡回原位 (PRD 4.3)
 */
async function collapseFromInlineEdit(
  msgId: string,
  applyDomChange: () => void,
  explicitEl?: HTMLElement | null,
): Promise<void> {
  const bubbleEl = findBubbleElement(msgId, explicitEl)
  const expandedRect = bubbleEl?.getBoundingClientRect() ?? null

  applyDomChange()
  await nextTick()

  if (!bubbleEl || !expandedRect || typeof window === 'undefined') {
    return
  }

  isMorphing.value = true
  const reduced = isReducedMotionPreferred()

  // 先清除内联尺寸以测量目标气泡的自然宽高
  resetInlineBoxStyles(bubbleEl)

  if (reduced) {
    bubbleEl.style.opacity = '0.45'
    void bubbleEl.offsetWidth
    bubbleEl.style.transition = `opacity ${REDUCED_DURATION_MS}ms ease-out`
    bubbleEl.style.opacity = '1'
    await new Promise<void>((resolve) => {
      window.setTimeout(resolve, REDUCED_DURATION_MS)
    })
    bubbleEl.style.transition = ''
    bubbleEl.style.opacity = ''
    isMorphing.value = false
    return
  }

  const targetRect = bubbleEl.getBoundingClientRect()

  // 从展开尺寸平滑收拢至目标气泡尺寸
  bubbleEl.style.transition = 'none'
  bubbleEl.style.width = `${expandedRect.width}px`
  bubbleEl.style.height = `${expandedRect.height}px`
  bubbleEl.style.maxWidth = 'none'
  bubbleEl.style.overflow = 'hidden'
  bubbleEl.style.willChange = 'width, height'

  void bubbleEl.offsetWidth

  bubbleEl.style.transition = `width ${COLLAPSE_DURATION_MS}ms cubic-bezier(0.22, 1, 0.36, 1), height ${COLLAPSE_DURATION_MS}ms cubic-bezier(0.22, 1, 0.36, 1), background-color 240ms ease, border-color 240ms ease`
  bubbleEl.style.width = `${targetRect.width}px`
  bubbleEl.style.height = `${targetRect.height}px`

  await new Promise<void>((resolve) => {
    window.setTimeout(resolve, COLLAPSE_DURATION_MS)
  })

  resetInlineBoxStyles(bubbleEl)
  isMorphing.value = false
}

export async function cancelInlineEdit(
  msg?: Message,
  explicitEl?: HTMLElement | null,
): Promise<void> {
  const targetId = msg?.id ?? editingMessageId.value
  if (!targetId) {
    editingMessageId.value = null
    editingDraft.value = ''
    return
  }

  await collapseFromInlineEdit(
    targetId,
    () => {
      editingMessageId.value = null
      editingDraft.value = ''
    },
    explicitEl,
  )
}

export async function saveInlineEdit(
  msg: Message,
  explicitEl?: HTMLElement | null,
): Promise<void> {
  const updated = editingDraft.value.trim()
  if (!updated) return

  const chatStore = useChatStore()

  // 先将文本更新并执行平滑收拢动画，随后触发无损多版本分支生成
  await collapseFromInlineEdit(
    msg.id,
    () => {
      msg.content = updated
      editingMessageId.value = null
      editingDraft.value = ''
    },
    explicitEl,
  )

  await chatStore.executeLosslessRetry(msg.id, updated)
}

export function useInlineEdit() {
  return {
    editingMessageId,
    editingDraft,
    isMorphing: readonly(isMorphing),
    startInlineEdit: handleInlineEdit,
    handleInlineEdit,
    cancelInlineEdit,
    saveInlineEdit,
  }
}
