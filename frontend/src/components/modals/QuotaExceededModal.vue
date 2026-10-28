<script setup lang="ts">
import {
  Sparkles,
  LogIn,
  Clock,
  AlertTriangle,
  X,
  ShieldCheck,
  Cloud,
  Zap,
} from 'lucide-vue-next'
import { useQuotaStore } from '@/stores/quotaStore'
import { useAuthStore } from '@/stores/authStore'

const quotaStore = useQuotaStore()
const authStore = useAuthStore()

function handleGoToLogin() {
  quotaStore.dismissModals()
  authStore.openAuthOverlay('register')
}
</script>

<template>
  <!-- 1. 游客超额：科技蓝玻璃拟态卡片引导弹窗 (PRD 2.3) -->
  <div
    v-if="quotaStore.showGuestUpgradeModal"
    class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/65 backdrop-blur-md"
  >
    <div
      class="relative w-full max-w-md rounded-2xl glass-modal p-6 border border-primary/40 shadow-cyber-glow space-y-5 animate-slide-up"
    >
      <button
        type="button"
        class="absolute top-4 right-4 p-1.5 rounded-lg text-muted hover:text-main hover:bg-elevated transition-colors"
        @click="quotaStore.dismissModals()"
      >
        <X class="w-4 h-4" />
      </button>

      <!-- 顶部微光图标 -->
      <div class="flex items-center gap-3">
        <div
          class="w-11 h-11 rounded-2xl cyber-btn-primary flex items-center justify-center shadow-cyber-glow shrink-0"
        >
          <Sparkles class="w-5 h-5 text-white" />
        </div>
        <div>
          <span
            class="text-[10px] font-mono uppercase tracking-wider px-2 py-0.5 rounded bg-primary/20 text-primary-light border border-primary/30"
          >
            HTTP 429 · QUOTA_EXCEEDED
          </span>
          <h3 class="text-base font-bold text-main mt-1">游客每日体验配额已用尽</h3>
        </div>
      </div>

      <!-- PRD 2.3 严格规定文案 -->
      <p class="text-sm text-sub leading-relaxed bg-elevated/60 p-3.5 rounded-xl border border-subtle">
        今日游客体验额度已用尽（{{ quotaStore.used || 10 }}/{{ quotaStore.limit || 10 }} 次）。注册登录即可获得 5 倍额度，并开启云端存档。
      </p>

      <!-- 升级权益对比列表 -->
      <div class="grid grid-cols-3 gap-2 text-center">
        <div class="p-2.5 rounded-xl bg-elevated/40 border border-subtle">
          <Zap class="w-4 h-4 text-cyber-cyan mx-auto mb-1" />
          <div class="text-xs font-semibold text-main">50 次 / 日</div>
          <div class="text-[10px] text-muted">5 倍对话额度</div>
        </div>
        <div class="p-2.5 rounded-xl bg-elevated/40 border border-subtle">
          <ShieldCheck class="w-4 h-4 text-primary-light mx-auto mb-1" />
          <div class="text-xs font-semibold text-main">16K Tokens</div>
          <div class="text-[10px] text-muted">4 倍长文上下文</div>
        </div>
        <div class="p-2.5 rounded-xl bg-elevated/40 border border-subtle">
          <Cloud class="w-4 h-4 text-indigo-400 mx-auto mb-1" />
          <div class="text-xs font-semibold text-main">云端同步</div>
          <div class="text-[10px] text-muted">无痕模式与多端存档</div>
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="flex items-center gap-3 pt-1">
        <button
          type="button"
          class="flex-1 flex items-center justify-center gap-2 py-2.5 px-4 rounded-xl cyber-btn-primary text-xs font-semibold"
          @click="handleGoToLogin"
        >
          <LogIn class="w-4 h-4" />
          <span>立即登录 / 注册</span>
        </button>
        <button
          type="button"
          class="py-2.5 px-4 rounded-xl bg-elevated text-sub hover:text-main text-xs transition-colors"
          @click="quotaStore.dismissModals()"
        >
          稍后再说
        </button>
      </div>
    </div>
  </div>

  <!-- 2. 普通用户超额：顶部/右上科技风气泡提示 (PRD 2.3) -->
  <div
    v-if="quotaStore.showUserQuotaToast"
    class="fixed top-16 right-6 z-50 max-w-md rounded-2xl glass-modal p-4 border border-amber-500/40 shadow-lg flex items-start gap-3 animate-slide-up"
  >
    <div class="p-2 rounded-xl bg-amber-500/15 text-amber-400 shrink-0">
      <Clock class="w-4 h-4" />
    </div>
    <div class="space-y-1 text-xs">
      <div class="font-semibold text-main flex items-center gap-2">
        <span>今日免费配额已达上限</span>
        <span class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300">
          429 QUOTA_EXCEEDED
        </span>
      </div>
      <!-- PRD 2.3 严格规定文案 -->
      <p class="text-sub leading-relaxed">
        今日免费对话配额已达上限（{{ quotaStore.used || 50 }}/{{ quotaStore.limit || 50 }} 次），将于明日 00:00 自动重置。
      </p>
    </div>
    <button
      type="button"
      class="p-1 text-muted hover:text-main shrink-0"
      @click="quotaStore.dismissModals()"
    >
      <X class="w-4 h-4" />
    </button>
  </div>

  <!-- 3. 413 CONTEXT_TOO_LONG 上下文超限提示气泡 (PRD 2.1) -->
  <div
    v-if="quotaStore.contextOverflowAlert"
    class="fixed top-16 left-1/2 -translate-x-1/2 z-50 max-w-lg w-[92%] rounded-2xl glass-modal p-3.5 border border-red-500/40 flex items-center justify-between gap-3 animate-slide-up"
  >
    <div class="flex items-center gap-2.5 text-xs">
      <AlertTriangle class="w-4 h-4 text-red-400 shrink-0" />
      <div class="text-sub">
        <strong class="text-red-300 font-mono mr-1.5">[413 CONTEXT_TOO_LONG]</strong>
        <span>{{ quotaStore.contextOverflowAlert.message }}</span>
      </div>
    </div>
    <button
      type="button"
      class="p-1 text-muted hover:text-main shrink-0"
      @click="quotaStore.dismissContextAlert()"
    >
      <X class="w-4 h-4" />
    </button>
  </div>
</template>
