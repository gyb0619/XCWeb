<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  Plus,
  MessageSquare,
  Trash2,
  EyeOff,
  Settings,
  Shield,
  Terminal,
  LogIn,
  LogOut,
  ChevronLeft,
  ChevronRight,
  Sparkles,
  MoreVertical,
  User,
  Zap,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/authStore'
import { useChatStore } from '@/stores/chatStore'
import { useConfigStore } from '@/stores/configStore'
import { useQuotaStore } from '@/stores/quotaStore'
import type { Conversation, UserRole } from '@/types/api'

const authStore = useAuthStore()
const chatStore = useChatStore()
const configStore = useConfigStore()
const quotaStore = useQuotaStore()

const isUserMenuOpen = ref<boolean>(false)
const userMenuContainerRef = ref<HTMLElement | null>(null)

const roleMetaMap: Record<
  UserRole,
  { label: string; colorClass: string; desc: string; ringClass: string }
> = {
  guest: {
    label: '游客',
    colorClass: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
    desc: '10次/日 · 4K上下文',
    ringClass: 'ring-amber-500/50',
  },
  user: {
    label: '用户',
    colorClass: 'bg-cyber-electric/15 text-cyber-electric border-cyber-electric/30',
    desc: '50次/日 · 16K上下文',
    ringClass: 'ring-blue-500/50',
  },
  admin: {
    label: '管理',
    colorClass: 'bg-cyber-cyan/15 text-cyber-cyan border-cyber-cyan/30',
    desc: '无限额 · 模型与全局看板',
    ringClass: 'ring-cyan-500/50',
  },
  dev: {
    label: '开发',
    colorClass: 'bg-indigo-500/15 text-indigo-400 border-indigo-500/30',
    desc: '无限额 · Dev Console',
    ringClass: 'ring-indigo-500/50',
  },
}

const currentRoleMeta = computed(() => roleMetaMap[authStore.role])

// 按今日 / 历史存档分组会话
const groupedConversations = computed(() => {
  const startOfToday = new Date()
  startOfToday.setHours(0, 0, 0, 0)
  const todayTs = startOfToday.getTime()

  const today: Conversation[] = []
  const earlier: Conversation[] = []

  for (const conv of chatStore.conversations) {
    if (conv.updatedAt >= todayTs) {
      today.push(conv)
    } else {
      earlier.push(conv)
    }
  }
  return { today, earlier }
})

function handleNewChat() {
  chatStore.createNewConversation(true)
}

function handleToggleIncognito() {
  chatStore.toggleIncognito()
}

function handleClickOutside(e: MouseEvent) {
  if (
    userMenuContainerRef.value &&
    !userMenuContainerRef.value.contains(e.target as Node)
  ) {
    isUserMenuOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <aside
    class="relative flex flex-col h-full border-r border-subtle bg-card/85 backdrop-blur-xl transition-all duration-300 select-none z-20"
    :class="configStore.isSidebarCollapsed ? 'w-16' : 'w-64 sm:w-72'"
  >
    <!-- 顶部品牌与折叠按钮 -->
    <div class="flex items-center justify-between h-14 px-3 border-b border-subtle shrink-0">
      <div
        v-if="!configStore.isSidebarCollapsed"
        class="flex items-center gap-2.5 overflow-hidden"
      >
        <div
          class="w-7 h-7 rounded-lg cyber-btn-primary flex items-center justify-center shadow-cyber-glow shrink-0"
        >
          <Sparkles class="w-3.5 h-3.5 text-white" />
        </div>
        <div class="truncate">
          <div class="text-sm font-bold tracking-wide text-main flex items-center gap-1.5">
            <span>XCWeb</span>
            <span
              class="text-[10px] font-mono px-1.5 py-0.2 rounded bg-primary/15 text-primary-light border border-primary/30"
            >
              AI Native
            </span>
          </div>
        </div>
      </div>

      <button
        type="button"
        class="p-1.5 rounded-lg text-sub hover:text-main hover:bg-elevated transition-colors mx-auto"
        :title="configStore.isSidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'"
        @click="configStore.isSidebarCollapsed = !configStore.isSidebarCollapsed"
      >
        <ChevronRight v-if="configStore.isSidebarCollapsed" class="w-4 h-4" />
        <ChevronLeft v-else class="w-4 h-4" />
      </button>
    </div>

    <!-- 新建对话按钮 -->
    <div class="p-2.5 shrink-0">
      <button
        type="button"
        class="w-full flex items-center justify-center gap-2 py-2 px-3 rounded-xl cyber-btn-primary text-xs font-medium shadow-cyber-glow transition-all"
        :title="configStore.isSidebarCollapsed ? '新建认知对话' : undefined"
        @click="handleNewChat"
      >
        <Plus class="w-4 h-4 shrink-0" />
        <span v-if="!configStore.isSidebarCollapsed">新建认知对话</span>
      </button>
    </div>

    <!-- 核心会话历史列表 (享受侧边栏绝大部分垂直空间，滚动流畅) -->
    <div class="flex-1 overflow-y-auto px-2 space-y-3 py-1">
      <template v-if="!configStore.isSidebarCollapsed">
        <!-- 今日对话 -->
        <div v-if="groupedConversations.today.length > 0" class="space-y-0.5">
          <div class="px-2 py-1 text-[10px] font-semibold uppercase tracking-wider text-muted">
            今日
          </div>
          <div
            v-for="conv in groupedConversations.today"
            :key="conv.id"
            class="group flex items-center justify-between gap-2 px-2.5 py-2 rounded-xl text-xs cursor-pointer transition-all"
            :class="
              !chatStore.isIncognito && chatStore.activeConversationId === conv.id
                ? 'bg-primary/15 text-main border border-primary/30 font-medium'
                : 'text-sub hover:bg-elevated/70 hover:text-main border border-transparent'
            "
            @click="chatStore.selectConversation(conv.id)"
          >
            <div class="flex items-center gap-2 min-w-0">
              <MessageSquare class="w-3.5 h-3.5 shrink-0 text-primary-light" />
              <span class="truncate">{{ conv.title }}</span>
            </div>
            <button
              type="button"
              class="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-red-500/20 hover:text-red-400 transition-opacity"
              title="删除会话"
              @click.stop="chatStore.deleteConversation(conv.id)"
            >
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        <!-- 历史存档 -->
        <div v-if="groupedConversations.earlier.length > 0" class="space-y-0.5">
          <div class="px-2 py-1 text-[10px] font-semibold uppercase tracking-wider text-muted">
            历史存档
          </div>
          <div
            v-for="conv in groupedConversations.earlier"
            :key="conv.id"
            class="group flex items-center justify-between gap-2 px-2.5 py-2 rounded-xl text-xs cursor-pointer transition-all"
            :class="
              !chatStore.isIncognito && chatStore.activeConversationId === conv.id
                ? 'bg-primary/15 text-main border border-primary/30 font-medium'
                : 'text-sub hover:bg-elevated/70 hover:text-main border border-transparent'
            "
            @click="chatStore.selectConversation(conv.id)"
          >
            <div class="flex items-center gap-2 min-w-0">
              <MessageSquare class="w-3.5 h-3.5 shrink-0 text-muted" />
              <span class="truncate">{{ conv.title }}</span>
            </div>
            <button
              type="button"
              class="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-red-500/20 hover:text-red-400 transition-opacity"
              title="删除会话"
              @click.stop="chatStore.deleteConversation(conv.id)"
            >
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        <!-- 空会话提示 -->
        <div
          v-if="chatStore.conversations.length === 0"
          class="py-10 text-center text-xs text-muted space-y-1"
        >
          <p>暂无历史会话</p>
          <p class="text-[11px]">点击上方新建对话即可开启</p>
        </div>
      </template>

      <!-- 折叠态简易图标列 -->
      <template v-else>
        <div class="flex flex-col items-center space-y-2 py-2">
          <button
            v-for="conv in chatStore.conversations.slice(0, 6)"
            :key="conv.id"
            type="button"
            class="p-2.5 rounded-xl transition-all"
            :class="
              chatStore.activeConversationId === conv.id
                ? 'bg-primary/20 text-primary-light border border-primary/40'
                : 'text-sub hover:bg-elevated hover:text-main'
            "
            :title="conv.title"
            @click="chatStore.selectConversation(conv.id)"
          >
            <MessageSquare class="w-4 h-4" />
          </button>
        </div>
      </template>
    </div>

    <!-- 底部：二级用户功能弹出菜单容器 (Profile & Secondary Menu Popover) -->
    <div
      ref="userMenuContainerRef"
      class="relative p-2.5 border-t border-subtle bg-card/60 shrink-0"
    >
      <!-- 常驻的用户信息条 (触发二级浮层菜单) -->
      <button
        type="button"
        class="w-full flex items-center justify-between gap-2 p-1.5 rounded-xl hover:bg-elevated/70 transition-all text-left"
        :class="isUserMenuOpen ? 'bg-elevated/80 border border-subtle' : ''"
        @click="isUserMenuOpen = !isUserMenuOpen"
      >
        <div class="flex items-center gap-2.5 min-w-0">
          <!-- 用户头像 / 角色光环 -->
          <div
            class="w-8 h-8 rounded-full bg-elevated border border-subtle flex items-center justify-center shrink-0 ring-1"
            :class="currentRoleMeta.ringClass"
          >
            <User class="w-4 h-4 text-main" />
          </div>

          <div v-if="!configStore.isSidebarCollapsed" class="truncate min-w-0">
            <div class="text-xs font-semibold text-main truncate flex items-center gap-1.5">
              <span>{{ authStore.displayName }}</span>
              <span
                class="text-[9px] font-mono px-1.5 py-0.2 rounded border font-normal"
                :class="currentRoleMeta.colorClass"
              >
                {{ currentRoleMeta.label }}
              </span>
            </div>
            <div class="text-[10px] text-muted truncate">
              <template v-if="quotaStore.unlimited">无限额度</template>
              <template v-else>{{ quotaStore.used }}/{{ quotaStore.limit }} 次</template>
            </div>
          </div>
        </div>

        <MoreVertical
          v-if="!configStore.isSidebarCollapsed"
          class="w-4 h-4 text-muted shrink-0"
        />
      </button>

      <!-- 二级弹出功能面板 (Secondary Dropdown Menu Popover) -->
      <div
        v-if="isUserMenuOpen"
        class="absolute left-2 bottom-16 w-64 p-2.5 rounded-2xl glass-modal shadow-2xl border border-subtle space-y-2.5 z-50 text-xs animate-slide-up"
      >
        <!-- 1. 配额用量卡片 -->
        <div class="p-2.5 rounded-xl bg-elevated/60 border border-subtle space-y-1.5">
          <div class="flex items-center justify-between text-[11px]">
            <span class="text-muted flex items-center gap-1">
              <Zap class="w-3 h-3 text-cyber-cyan" />
              今日配额
            </span>
            <span class="font-mono text-main font-semibold">
              <template v-if="quotaStore.unlimited">∞ 不限</template>
              <template v-else>{{ quotaStore.used }} / {{ quotaStore.limit }} 次</template>
            </span>
          </div>

          <div
            v-if="!quotaStore.unlimited"
            class="w-full h-1.5 rounded-full bg-elevated overflow-hidden"
          >
            <div
              class="h-full rounded-full transition-all duration-300"
              :class="
                quotaStore.usagePercentage >= 90
                  ? 'bg-red-500'
                  : quotaStore.usagePercentage >= 70
                    ? 'bg-amber-400'
                    : 'bg-gradient-to-r from-cyber-royal to-cyber-cyan'
              "
              :style="{ width: `${quotaStore.usagePercentage}%` }"
            />
          </div>

          <div class="flex items-center justify-between text-[10px] text-muted pt-0.5">
            <span>已耗 Token: {{ quotaStore.tokensUsedToday.toLocaleString() }}</span>
            <span>单次 {{ (quotaStore.contextTokenLimit / 1000).toFixed(0) }}K</span>
          </div>
        </div>

        <!-- 2. 无痕临时模式切换项 -->
        <button
          type="button"
          class="w-full flex items-center justify-between p-2 rounded-xl hover:bg-elevated/70 transition-colors text-sub hover:text-main"
          @click="handleToggleIncognito"
        >
          <div class="flex items-center gap-2">
            <EyeOff class="w-4 h-4 text-indigo-400" />
            <span>无痕临时对话</span>
          </div>
          <span
            class="text-[10px] px-2 py-0.5 rounded-full border transition-all"
            :class="
              chatStore.isIncognito
                ? 'bg-indigo-500/20 text-indigo-300 border-indigo-400/40'
                : 'bg-elevated text-muted border-subtle'
            "
          >
            {{ chatStore.isIncognito ? '已开启' : '关闭' }}
          </span>
        </button>

        <!-- 3. 演示角色快捷切换 (收拢在二级菜单中，不再霸占侧边栏) -->
        <div class="p-2 rounded-xl bg-elevated/40 border border-subtle space-y-1.5">
          <div class="text-[10px] font-semibold text-muted">快速切换体验角色:</div>
          <div class="grid grid-cols-4 gap-1">
            <button
              v-for="r in (['guest', 'user', 'admin', 'dev'] as UserRole[])"
              :key="r"
              type="button"
              class="py-1 rounded text-[10px] font-mono uppercase border transition-all text-center"
              :class="
                authStore.role === r
                  ? 'bg-primary text-white border-primary font-bold'
                  : 'bg-elevated/80 text-sub border-subtle hover:text-main'
              "
              @click="authStore.switchDemoRole(r)"
            >
              {{ r }}
            </button>
          </div>
        </div>

        <!-- 4. 系统功能菜单项列表 -->
        <div class="space-y-0.5 pt-1 border-t border-subtle">
          <button
            type="button"
            class="w-full flex items-center gap-2.5 px-2.5 py-1.5 rounded-lg hover:bg-elevated text-sub hover:text-main transition-colors"
            @click="
              configStore.showSettingsModal = true;
              isUserMenuOpen = false;
            "
          >
            <Settings class="w-4 h-4 text-muted" />
            <span>全局设置 (System Prompt)</span>
          </button>

          <button
            v-if="authStore.isAdmin"
            type="button"
            class="w-full flex items-center gap-2.5 px-2.5 py-1.5 rounded-lg hover:bg-elevated text-cyber-cyan transition-colors"
            @click="
              configStore.showAdminModal = true;
              isUserMenuOpen = false;
            "
          >
            <Shield class="w-4 h-4" />
            <span>管理员模型与看板</span>
          </button>

          <button
            v-if="authStore.isDev"
            type="button"
            class="w-full flex items-center gap-2.5 px-2.5 py-1.5 rounded-lg hover:bg-elevated text-indigo-300 transition-colors"
            @click="
              configStore.showDevConsoleModal = true;
              isUserMenuOpen = false;
            "
          >
            <Terminal class="w-4 h-4" />
            <span>开发者控制台 (Dev)</span>
          </button>

          <button
            v-if="authStore.isGuest"
            type="button"
            class="w-full flex items-center gap-2.5 px-2.5 py-1.5 rounded-lg hover:bg-elevated text-primary-light font-medium transition-colors"
            @click="
              authStore.openAuthOverlay('login');
              isUserMenuOpen = false;
            "
          >
            <LogIn class="w-4 h-4" />
            <span>注册 / 登录 (领50次配额)</span>
          </button>
          <button
            v-else
            type="button"
            class="w-full flex items-center gap-2.5 px-2.5 py-1.5 rounded-lg hover:bg-elevated text-muted hover:text-red-400 transition-colors"
            @click="
              authStore.logout();
              isUserMenuOpen = false;
            "
          >
            <LogOut class="w-4 h-4" />
            <span>退出登录</span>
          </button>
        </div>
      </div>
    </div>
  </aside>
</template>