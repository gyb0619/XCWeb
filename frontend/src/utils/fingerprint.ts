/**
 * 浏览器指纹采集与 SHA-256 哈希生成工具
 * 采集 Canvas / WebGL / UA / 屏幕 / 时区 / 语言特征，生成稳定的 64 位十六进制哈希
 */

const FP_STORAGE_KEY = 'xcweb_device_fingerprint_v1'
let cachedFingerprint: string | null = null

function getCanvasFingerprint(): string {
  try {
    const canvas = document.createElement('canvas')
    canvas.width = 240
    canvas.height = 60
    const ctx = canvas.getContext('2d')
    if (!ctx) return 'no-canvas-2d'
    ctx.textBaseline = 'alphabetic'
    ctx.fillStyle = '#2563EB'
    ctx.fillRect(10, 10, 100, 30)
    ctx.fillStyle = '#06B6D4'
    ctx.font = '16px Inter, Arial, sans-serif'
    ctx.fillText('XCWeb-Cognitive-FP-2026', 14, 30)
    ctx.strokeStyle = 'rgba(99, 102, 241, 0.8)'
    ctx.arc(180, 30, 18, 0, Math.PI * 2, true)
    ctx.stroke()
    return canvas.toDataURL()
  } catch {
    return 'canvas-blocked'
  }
}

function getWebGLFingerprint(): string {
  try {
    const canvas = document.createElement('canvas')
    const gl =
      canvas.getContext('webgl') ||
      (canvas.getContext('experimental-webgl') as WebGLRenderingContext | null)
    if (!gl) return 'no-webgl'
    const debugInfo = gl.getExtension('WEBGL_debug_renderer_info')
    if (!debugInfo) {
      return `${gl.getParameter(gl.VENDOR)}|${gl.getParameter(gl.RENDERER)}`
    }
    const vendor = String(gl.getParameter(debugInfo.UNMASKED_VENDOR_WEBGL) ?? '')
    const renderer = String(gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL) ?? '')
    return `${vendor}|${renderer}`
  } catch {
    return 'webgl-blocked'
  }
}

async function sha256Hex(raw: string): Promise<string> {
  if (typeof window !== 'undefined' && window.crypto?.subtle) {
    const encoder = new TextEncoder()
    const data = encoder.encode(raw)
    const digest = await window.crypto.subtle.digest('SHA-256', data)
    const byteArray = Array.from(new Uint8Array(digest))
    return byteArray.map((b) => b.toString(16).padStart(2, '0')).join('')
  }
  // 确定性 64 位十六进制兜底（当非安全上下文无 crypto.subtle 时）
  let h1 = 0xdeadbeef ^ raw.length
  let h2 = 0x41c6ce57 ^ raw.length
  for (let i = 0; i < raw.length; i++) {
    const ch = raw.charCodeAt(i)
    h1 = Math.imul(h1 ^ ch, 2654435761)
    h2 = Math.imul(h2 ^ ch, 1597334677)
  }
  const part = (
    (h1 >>> 0).toString(16).padStart(8, '0') + (h2 >>> 0).toString(16).padStart(8, '0')
  ).repeat(4)
  return part.slice(0, 64)
}

export async function getBrowserFingerprint(): Promise<string> {
  if (cachedFingerprint && cachedFingerprint.length === 64) {
    return cachedFingerprint
  }

  try {
    const stored = localStorage.getItem(FP_STORAGE_KEY)
    if (stored && /^[a-f0-9]{64}$/i.test(stored)) {
      cachedFingerprint = stored.toLowerCase()
      return cachedFingerprint
    }
  } catch {
    // ignore storage errors
  }

  const components: string[] = [
    navigator.userAgent || 'unknown-ua',
    navigator.language || 'zh-CN',
    String(navigator.hardwareConcurrency || 4),
    String(screen?.width || 1920) + 'x' + String(screen?.height || 1080),
    String(screen?.colorDepth || 24),
    Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai',
    getCanvasFingerprint(),
    getWebGLFingerprint(),
  ]

  const hash = await sha256Hex(components.join('||'))
  cachedFingerprint = hash

  try {
    localStorage.setItem(FP_STORAGE_KEY, hash)
  } catch {
    // ignore storage errors
  }

  return hash
}
