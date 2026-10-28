<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  X,
  UserCheck,
  Shield,
  LogIn,
  UserPlus,
  Fingerprint,
  Sparkles,
  CheckCircle2,
  Cpu,
} from 'lucide-vue-next'
import NeuralCanvas from '@/components/auth/NeuralCanvas.vue'
import { useAuthStore } from '@/stores/authStore'
import { getBrowserFingerprint } from '@/utils/fingerprint'
import type { UserRole } from '@/types/api'

type AuthTab = 'login' | 'register' | 'guest'

const authStore = useAuthStore()

const activeTab = ref<AuthTab>(authStore.authOverlayInitialTab)
const username = ref<string>('')
const email = ref<string>('')
const password = ref<string>('')
const isFadingOut = ref<boolean>(false)
const guestFingerprintPreview = ref<string>('')

const tabs: Array<{ key: AuthTab; label: string; badge?: string }> = [
  { key: 'login', label: '登录' },
  { key: 'register', label: '注册', badge: '50次/日' },
  { key: 'guest', label: '快捷体验' },
]

const activeTabIndex = computed<number>(() => {
  const idx = tabs.findIndex((t) => t.key === activeTab.value)
  return idx >= 0 ? idx : 0
})

watch(
  () => authStore.authOverlayInitialTab,
  (tab) => {
    activeTab.value = tab
  },
)

watch(
  () => authStore.showAuthOverlay,
  async (visible) => {
    if (visible) {
      isFadingOut.value = false
      if (!guestFingerprintPreview.value) {
        const fp = await getBrowserFingerprint()
        guestFingerprintPreview.value = fp
      }
    }
  },
  { immediate: true },
)

async function fadeOutAndClose(action?: () => Promise<void>) {
  if (action) {
    await action()
  }
  isFadingOut.value = true
  window.setTimeout(() => {
    authStore.closeAuthOverlay()
    isFadingOut.value = false
  }, 260)
}

async function handleGuestExperience() {
  if (authStore.isLoading || isFadingOut.value) return
  const fp = await getBrowserFingerprint()
  guestFingerprintPreview.value = fp
  authStore.fingerprintHash = fp

  isFadingOut.value = true
  try {
    await authStore.loginAsGuest(true)
  } finally {
    window.setTimeout(() => {
      authStore.closeAuthOverlay()
      isFadingOut.value = false
    }, 260)
  }
}

async function handleFormSubmit() {
  if (activeTab.value === 'guest') {
    await handleGuestExperience()
    return
  }

  const trimmedUser = username.value.trim()
  const trimmedPass = password.value.trim()
  if (!trimmedUser || !trimmedPass) return

  try {
    if (activeTab.value === 'login') {
      await authStore.login(trimmedUser, trimmedPass)
    } else {
      await authStore.register(
        trimmedUser,
        email.value.trim() || `${trimmedUser}@xcweb.ai`,
        trimmedPass,
      )
    }
  } catch {
    // 若后端离线或使用演示账号，平滑切换至对应角色以保障完整闭环演示
    const lower = trimmedUser.toLowerCase()
    const targetRole: UserRole =
      lower === 'admin' ? 'admin' : lower === 'dev' || lower === 'developer' ? 'dev' : 'user'
    await fadeOutAndClose(async () => {
      await authStore.switchDemoRole(targetRole)
    })
  }
}

async function handleQuickRoleSwitch(role: UserRole) {
  if (role === 'guest') {
    await handleGuestExperience()
    return
  }
  await fadeOutAndClose(async () => {
    await authStore.switchDemoRole(role)
  })
}
</script>

<template>
  <Transition name="auth-overlay-fade">
    <div
      v-if="authStore.showAuthOverlay"
      id="auth-overlay-mount"
      class="fixed inset-0 z-50 flex items-center justify-center p-4 overflow-y-auto transition-opacity duration-300"
      :class="isFadingOut ? 'opacity-0 pointer-events-none' : 'opacity-100'"
      role="dialog"
      aria-modal="true"
      aria-labelledby="xcweb-auth-title"
    >
      <!-- Canvas 2D 科技深邃蓝神经突触连线粒子场背景 (PRD 4.2) -->
      <NeuralCanvas />

      <!-- 径向暗角与毛玻璃环境遮罩 -->
      <div
        class="absolute inset-0 bg-black/45 backdrop-blur-[4px] pointer-events-none"
        aria-hidden="true"
      />

      <!-- 居中玻璃拟态卡片 (backdrop-blur-xl + 浅色微光内描边) -->
      <div
        class="relative z-10 w-full max-w-[440px] rounded-3xl bg-[#0F1629]/80 backdrop-blur-xl border border-white/15 shadow-[0_24px_64px_-12px_rgba(0,0,0,0.75),0_0_32px_-6px_rgba(37,99,235,0.4),inset_0_1px_0_0_rgba(255,255,255,0.18)] p-6 sm:p-7 space-y-5 transition-all duration-300"
        :class="isFadingOut ? 'scale-95 opacity-0' : 'scale-100 opacity-100 animate-slide-up'"
      >
        <!-- 右上角关闭按钮（允许直接返回游客工作台） -->
        <button
          type="button"
          class="absolute top-4 right-4 p-2 rounded-xl text-slate-400 hover:text-white hover:bg-white/10 transition-colors"
          title="关闭并继续使用"
          @click="fadeOutAndClose()"
        >
          <X class="w-4 h-4" />
        </button>

        <!-- 顶部品牌区：定制 XCWeb 矢量 SVG Logo + 标语 (PRD 4.2) -->
        <div class="text-center space-y-2 pt-1">
          <div class="relative inline-flex items-center justify-center mx-auto">
            <!-- 背后呼吸光晕 -->
            <div
              class="absolute -inset-2 rounded-2xl bg-gradient-to-tr from-[#2563EB]/45 via-[#3B82F6]/35 to-[#06B6D4]/45 blur-md animate-pulse-glow"
            />
            <!-- XCWeb 定制矢量 SVG Logo -->
            <div
              class="relative w-14 h-14 rounded-2xl bg-gradient-to-br from-[#0F172A] via-[#1E293B] to-[#0B0F19] border border-white/20 flex items-center justify-center shadow-cyber-glow"
            >
              <svg
                class="w-9 h-9"
                viewBox="0 0 64 64"
                fill="none"
                xmlns="http://www.w3.org/2000/svg"
                aria-label="XCWeb Vector Logo"
              >
                <defs>
                  <linearGradient id="xcwebLogoPrimary" x1="6" y1="6" x2="58" y2="58" gradientUnits="userSpaceOnUse">
                    <stop offset="0%" stop-color="#2563EB" />
                    <stop offset="50%" stop-color="#3B82F6" />
                    <stop offset="100%" stop-color="#06B6D4" />
                  </linearGradient>
                  <linearGradient id="xcwebLogoAccent" x1="54" y1="10" x2="12" y2="54" gradientUnits="userSpaceOnUse">
                    <stop offset="0%" stop-color="#06B6D4" />
                    <stop offset="100%" stop-color="#3B82F6" />
                  </linearGradient>
                </defs>
                <!-- 外层六边形认知神经环 -->
                <polygon
                  points="32,4 56,18 56,46 32,60 8,46 8,18"
                  stroke="url(#xcwebLogoPrimary)"
                  stroke-width="2.5"
                  fill="rgba(37, 99, 235, 0.12)"
                />
                <!-- X 型神经突触交叉轨迹 -->
                <path
                  d="M19 19L45 45M45 19L19 45"
                  stroke="url(#xcwebLogoAccent)"
                  stroke-width="3.2"
                  stroke-linecap="round"
                />
                <!-- C 型认知核心弧线 -->
                <path
                  d="M39 22C36.8 19.8 33.6 18.8 30.2 19.4C24.8 20.3 21 25.4 21 32C21 38.6 24.8 43.7 30.2 44.6C33.6 45.2 36.8 44.2 39 42"
                  stroke="#F8FAFC"
                  stroke-width="2.6"
                  stroke-linecap="round"
                />
                <!-- 神经突触节点 -->
                <circle cx="32" cy="32" r="4.2" fill="#06B6D4" />
                <circle cx="19" cy="19" r="2.6" fill="#3B82F6" />
                <circle cx="45" cy="19" r="2.6" fill="#06B6D4" />
                <circle cx="19" cy="45" r="2.6" fill="#2563EB" />
                <circle cx="45" cy="45" r="2.6" fill="#3B82F6" />
              </svg>
            </div>
          </div>

          <div class="space-y-1">
            <h2
              id="xcweb-auth-title"
              class="text-xl font-bold tracking-wider text-white flex items-center justify-center gap-2"
            >
              <span>XCWeb</span>
              <span
                class="text-[10px] font-mono font-semibold px-2 py-0.5 rounded-full bg-[#2563EB]/25 text-[#3B82F6] border border-[#3B82F6]/40"
              >
                COGNITIVE v1.0
              </span>
            </h2>
            <p class="text-xs text-slate-300 font-medium tracking-wide">
              智启未来 · AI 对话与智能体平台
            </p>
          </div>
        </div>

        <!-- 三栏胶囊 Tab 切换器（带 cubic-bezier(0.34, 1.56, 0.64, 1) 弹性阻尼滑块） (PRD 4.2) -->
        <div
          class="relative grid grid-cols-3 p-1 rounded-2xl bg-[#090D16]/90 border border-white/10 text-xs select-none"
          role="tablist"
        >
          <!-- 弹性阻尼胶囊滑块 -->
          <div
            class="auth-tab-pill pointer-events-none absolute top-1 bottom-1 left-1 w-[calc((100%-8px)/3)] rounded-xl cyber-btn-primary shadow-cyber-glow"
            :style="{
              transform: `translate3d(${activeTabIndex * 100}%, 0, 0)`,
            }"
          />

          <button
            v-for="tab in tabs"
            :key="tab.key"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab.key"
            class="relative z-10 py-2 px-2 rounded-xl font-semibold transition-colors duration-200 flex items-center justify-center gap-1"
            :class="
              activeTab === tab.key
                ? 'text-white'
                : 'text-slate-400 hover:text-slate-200'
            "
            @click="activeTab = tab.key"
          >
            <span>{{ tab.label }}</span>
            <span
              v-if="tab.badge"
              class="hidden sm:inline-block text-[9px] px-1 py-0.2 rounded bg-white/15 text-cyan-200 font-mono"
            >
              {{ tab.badge }}
            </span>
          </button>
        </div>

        <!-- 错误提示横幅 -->
        <div
          v-if="authStore.authError"
          class="p-3 rounded-xl bg-red-500/15 border border-red-500/35 text-xs text-red-200 flex items-center justify-between gap-2"
        >
          <span>{{ authStore.authError }}</span>
          <button
            type="button"
            class="text-red-300 hover:text-white"
            @click="authStore.authError = null"
          >
            <X class="w-3.5 h-3.5" />
          </button>
        </div>

        <!-- Tab 1 & 2：登录 / 注册表单 -->
        <form
          v-if="activeTab !== 'guest'"
          class="space-y-3.5"
          @submit.prevent="handleFormSubmit"
        >
          <div class="space-y-1.5">
            <label class="text-xs font-medium text-slate-300 block">用户名</label>
            <input
              v-model="username"
              type="text"
              required
              autocomplete="username"
              placeholder="输入用户名（如 alice / admin / dev）"
              class="w-full px-3.5 py-2.5 rounded-xl bg-[#0B0F19]/90 border border-white/12 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6]/40 transition-all"
            />
          </div>

          <div v-if="activeTab === 'register'" class="space-y-1.5">
            <label class="text-xs font-medium text-slate-300 block">
              电子邮箱 <span class="text-slate-500 font-normal">（选填，用于多端同步通知）</span>
            </label>
            <input
              v-model="email"
              type="email"
              autocomplete="email"
              placeholder="alice@xcweb.ai"
              class="w-full px-3.5 py-2.5 rounded-xl bg-[#0B0F19]/90 border border-white/12 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6]/40 transition-all"
            />
          </div>

          <div class="space-y-1.5">
            <label class="text-xs font-medium text-slate-300 block">登录密码</label>
            <input
              v-model="password"
              type="password"
              required
              autocomplete="current-password"
              placeholder="请输入安全密码"
              class="w-full px-3.5 py-2.5 rounded-xl bg-[#0B0F19]/90 border border-white/12 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6]/40 transition-all"
            />
          </div>

          <button
            type="submit"
            :disabled="authStore.isLoading"
            class="w-full py-2.5 px-4 rounded-xl cyber-btn-primary text-xs font-semibold flex items-center justify-center gap-2"
          >
            <LogIn v-if="activeTab === 'login'" class="w-4 h-4" />
            <UserPlus v-else class="w-4 h-4" />
            <span>
              {{
                activeTab === 'login'
                  ? '立即登录 · 开启云端双向同步'
                  : '注册普通账号 · 解锁 50 次/日与 16K 上下文'
              }}
            </span>
          </button>

          <!-- 角色快速体验小贴士 -->
          <div class="flex items-center justify-between pt-1 text-[11px] text-slate-400">
            <span>演示角色一键体验：</span>
            <div class="flex items-center gap-1.5">
              <button
                type="button"
                class="px-2 py-0.5 rounded bg-white/5 hover:bg-white/15 text-slate-300 font-mono transition-colors"
                @click="handleQuickRoleSwitch('user')"
              >
                User
              </button>
              <button
                type="button"
                class="px-2 py-0.5 rounded bg-cyan-500/15 hover:bg-cyan-500/25 text-cyan-300 font-mono transition-colors"
                @click="handleQuickRoleSwitch('admin')"
              >
                Admin
              </button>
              <button
                type="button"
                class="px-2 py-0.5 rounded bg-indigo-500/15 hover:bg-indigo-500/25 text-indigo-300 font-mono transition-colors"
                @click="handleQuickRoleSwitch('dev')"
              >
                Dev
              </button>
            </div>
          </div>
        </form>

        <!-- Tab 3：快捷体验详情面板 -->
        <div v-else class="space-y-3">
          <div
            class="p-3.5 rounded-2xl bg-[#0B0F19]/85 border border-cyan-500/25 text-xs space-y-2.5"
          >
            <div class="flex items-center justify-between">
              <div class="font-semibold text-white flex items-center gap-1.5">
                <Shield class="w-4 h-4 text-[#06B6D4]" />
                <span>免注册三重校验匿名会话</span>
              </div>
              <span
                class="text-[10px] font-mono px-2 py-0.5 rounded bg-[#06B6D4]/15 text-[#06B6D4]"
              >
                10 次/日 · 4K
              </span>
            </div>

            <p class="text-slate-300 leading-relaxed text-[11px]">
              系统自动采集浏览器硬件特征生成不可逆指纹哈希，向服务端换取 30 天有效期签名匿名令牌。会话存储在本地 IndexedDB。
            </p>

            <div
              class="flex items-center justify-between px-2.5 py-1.5 rounded-xl bg-white/5 border border-white/10 font-mono text-[11px]"
            >
              <span class="text-slate-400 flex items-center gap-1.5">
                <Fingerprint class="w-3.5 h-3.5 text-[#3B82F6]" />
                <span>设备指纹哈希:</span>
              </span>
              <span class="text-cyan-300">
                {{
                  (guestFingerprintPreview || authStore.fingerprintHash || 'computing...').slice(
                    0,
                    16,
                  )
                }}...
              </span>
            </div>

            <div class="grid grid-cols-2 gap-2 pt-0.5 text-[11px] text-slate-300">
              <div class="flex items-center gap-1.5">
                <CheckCircle2 class="w-3.5 h-3.5 text-emerald-400 shrink-0" />
                <span>深度思考思维链抽屉</span>
              </div>
              <div class="flex items-center gap-1.5">
                <Cpu class="w-3.5 h-3.5 text-[#3B82F6] shrink-0" />
                <span>内置 Mock 离线无缝降级</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 显著的【以游客身份体验】主入口按钮 (PRD 4.2 核心要求) -->
        <div class="pt-2 border-t border-white/10 space-y-2">
          <button
            type="button"
            data-testid="guest-experience-btn"
            :disabled="authStore.isLoading || isFadingOut"
            class="group relative w-full py-3 px-4 rounded-2xl bg-gradient-to-r from-[#2563EB]/25 via-[#3B82F6]/25 to-[#06B6D4]/25 hover:from-[#2563EB]/40 hover:via-[#3B82F6]/40 hover:to-[#06B6D4]/40 border border-[#06B6D4]/45 hover:border-[#06B6D4]/80 text-xs font-bold text-white shadow-[0_0_24px_-6px_rgba(6,182,212,0.45)] transition-all flex items-center justify-center gap-2"
            @click="handleGuestExperience"
          >
            <UserCheck class="w-4 h-4 text-[#06B6D4] group-hover:scale-110 transition-transform" />
            <span>【以游客身份体验】</span>
            <span class="text-[11px] font-normal text-cyan-200/90">
              （免登录领 10 次/日额度 · 即刻对话）
            </span>
            <Sparkles class="w-3.5 h-3.5 text-[#06B6D4] opacity-80" />
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
/* 胶囊滑块带弹性阻尼过渡 cubic-bezier(0.34, 1.56, 0.64, 1) (PRD 4.2) */
.auth-tab-pill {
  transition: transform 420ms cubic-bezier(0.34, 1.56, 0.64, 1);
  will-change: transform;
}

.auth-overlay-fade-enter-active,
.auth-overlay-fade-leave-active {
  transition: opacity 280ms cubic-bezier(0.22, 1, 0.36, 1);
}

.auth-overlay-fade-enter-from,
.auth-overlay-fade-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .auth-tab-pill {
    transition: transform 120ms ease-out !important;
  }
}
</style>
