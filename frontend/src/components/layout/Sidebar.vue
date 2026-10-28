<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  Plus,
  MessageSquare,
  Trash2,
  EyeOff,
  FileText,
  Upload,
  Settings,
  Shield,
  Terminal,
  LogIn,
  LogOut,
  ChevronLeft,
  ChevronRight,
  Sparkles,
  X,
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

const fileInputRef = ref<HTMLInputElement | null>(null)
const incognitoBtnRef = ref<HTMLElement | null>(null)

const roleBadgeMeta = computed(() => {
  const map: Record<
    UserRole,
    { label: string; colorClass: string; desc: string }
  > = {
    guest: {
      label: 'GUEST · 游客体验',
      colorClass: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
      desc: '10 次/日 · 4K 上下文 · 仅本地存储',
    },
    user: {
      label: 'USER · 注册用户',
      colorClass: 'bg-cyber-electric/15 text-cyber-electric border-cyber-electric/30',
      desc: '50 次/日 · 16K 上下文 · 云端同步',
    },
    admin: {
      label: 'ADMIN · 管理员',
      colorClass: 'bg-cyber-cyan/15 text-cyber-cyan border-cyber-cyan/30',
      desc: '无限额 · 模型管控与全局配额看板',
    },
    dev: {
      label: 'DEV · 开发者',
      colorClass: 'bg-indigo-500/15 text-indigo-400 border-indigo-500/30',
      desc: '无限额 · Dev Console 与性能追踪',
    },
  }
  return map[authStore.role]
})

// 按今日 / 历史分组会话
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
  chatStore.toggleIncognito(incognitoBtnRef.value)
}

function triggerRagFileSelect() {
  fileInputRef.value?.click()
}

async function handleRagFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || files.length === 0) return

  for (let i = 0; i < files.length; i++) {
    await chatStore.addRagDocument(files[i])
  }
  input.value = ''
}
</script>

<template>
  <aside
    class="relative flex flex-col h-full border-r border-subtle bg-card/85 backdrop-blur-xl transition-all duration-300 select-none z-20"
    :class="configStore.isSidebarCollapsed ? 'w-16' : 'w-72'"
  >
    <!-- 顶部品牌与折叠按钮 -->
    <div class="flex items-center justify-between h-14 px-3.5 border-b border-subtle">
      <div
        v-if="!configStore.isSidebarCollapsed"
        class="flex items-center gap-2.5 overflow-hidden"
      >
        <div
          class="w-8 h-8 rounded-lg cyber-btn-primary flex items-center justify-center shadow-cyber-glow shrink-0"
        >
          <Sparkles class="w-4 h-4 text-white" />
        </div>
        <div class="truncate">
          <div class="text-sm font-bold tracking-wide text-main flex items-center gap-1.5">
            <span>XCWeb</span>
            <span
              class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-primary/15 text-primary-light border border-primary/30"
            >
              AI Native
            </span>
          </div>
          <p class="text-[11px] text-muted truncate">智启未来 · 智能体平台</p>
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

    <!-- 新建对话 & 无痕模式开关 -->
    <div class="p-3 space-y-2">
      <button
        type="button"
        class="w-full flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl cyber-btn-primary text-xs font-medium"
        @click="handleNewChat"
      >
        <Plus class="w-4 h-4 shrink-0" />
        <span v-if="!configStore.isSidebarCollapsed">新建认知对话</span>
      </button>

      <button
        ref="incognitoBtnRef"
        type="button"
        data-motion-anchor="incognito-sweep-trigger"
        class="w-full flex items-center justify-between gap-2 py-2 px-3 rounded-xl border text-xs transition-all"
        :class="
          chatStore.isIncognito
            ? 'bg-indigo-500/20 border-indigo-400/50 text-indigo-300 shadow-incognito-glow'
            : 'bg-elevated/50 border-subtle text-sub hover:text-main hover:border-primary/40'
        "
        @click="handleToggleIncognito"
      >
        <div class="flex items-center gap-2 mx-auto sm:mx-0">
          <EyeOff class="w-3.5 h-3.5 shrink-0" />
          <span v-if="!configStore.isSidebarCollapsed">
            {{ chatStore.isIncognito ? '无痕临时模式已开启' : '开启无痕临时对话' }}
          </span>
        </div>
        <span
          v-if="!configStore.isSidebarCollapsed && authStore.isGuest"
          class="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-400"
        >
          需登录
        </span>
      </button>

      <!-- 游客点击无痕模式提示气泡 -->
      <div
        v-if="chatStore.incognitoNotice && !configStore.isSidebarCollapsed"
        class="p-2.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-[11px] text-amber-300 leading-relaxed flex items-start justify-between gap-2"
      >
        <span>{{ chatStore.incognitoNotice }}</span>
        <button
          type="button"
          class="text-amber-400 hover:text-white underline shrink-0"
          @click="authStore.openAuthOverlay('login')"
        >
          去登录
        </button>
      </div>
    </div>

    <!-- 会话历史分组列表 -->
    <div class="flex-1 overflow-y-auto px-2.5 space-y-4 py-1">
      <template v-if="!configStore.isSidebarCollapsed">
        <!-- 今日对话 -->
        <div v-if="groupedConversations.today.length > 0" class="space-y-1">
          <div class="px-2 py-1 text-[10px] font-semibold uppercase tracking-wider text-muted">
            今日对话
          </div>
          <div
            v-for="conv in groupedConversations.today"
            :key="conv.id"
            class="group flex items-center justify-between gap-2 px-2.5 py-2 rounded-xl text-xs cursor-pointer transition-all"
            :class="
              !chatStore.isIncognito && chatStore.activeConversationId === conv.id
                ? 'bg-primary/15 text-main border border-primary/30 font-medium'
                : 'text-sub hover:bg-elevated/60 hover:text-main border border-transparent'
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

        <!-- 更早对话 -->
        <div v-if="groupedConversations.earlier.length > 0" class="space-y-1">
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
                : 'text-sub hover:bg-elevated/60 hover:text-main border border-transparent'
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
      </template>
    </div>

    <!-- 端侧轻量 RAG 本地知识库入口 (PRD 6.2) -->
    <div
      v-if="!configStore.isSidebarCollapsed"
      class="mx-3 mb-2.5 p-2.5 rounded-xl bg-elevated/50 border border-subtle space-y-2"
    >
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-1.5 text-xs font-medium text-main">
          <FileText class="w-3.5 h-3.5 text-cyber-cyan" />
          <span>端侧 RAG 知识库</span>
        </div>
        <button
          type="button"
          class="flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-md bg-cyber-cyan/15 text-cyber-cyan hover:bg-cyber-cyan/25 transition-colors"
          @click="triggerRagFileSelect"
        >
          <Upload class="w-3 h-3" />
          <span>挂载文档</span>
        </button>
        <input
          ref="fileInputRef"
          type="file"
          accept=".txt,.md,.json,.csv,.ts,.js,.py,.java,.sql"
          multiple
          class="hidden"
          @change="handleRagFileChange"
        />
      </div>

      <p v-if="chatStore.ragDocuments.length === 0" class="text-[11px] text-muted leading-tight">
        本地内存语义切片，文档不出浏览器
      </p>
      <div v-else class="space-y-1 max-h-20 overflow-y-auto">
        <div
          v-for="doc in chatStore.ragDocuments"
          :key="doc.id"
          class="flex items-center justify-between text-[11px] px-2 py-1 rounded bg-card/80 border border-subtle"
        >
          <span class="truncate text-sub">{{ doc.name }}</span>
          <div class="flex items-center gap-1.5 shrink-0">
            <span class="text-[10px] text-cyber-cyan">{{ doc.chunksCount }} 块</span>
            <button
              type="button"
              class="text-muted hover:text-red-400"
              @click="chatStore.removeRagDocument(doc.id)"
            >
              <X class="w-3 h-3" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 配额进度条卡片 (PRD 2.1 & 2.3) -->
    <div
      v-if="!configStore.isSidebarCollapsed"
      class="mx-3 mb-2.5 p-3 rounded-xl glass-panel space-y-2"
    >
      <div class="flex items-center justify-between text-xs">
        <span class="text-sub font-medium">今日对话配额</span>
        <span class="font-mono text-main font-semibold">
          <template v-if="quotaStore.unlimited">∞ 不限次数</template>
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

      <div class="flex items-center justify-between text-[11px] text-muted">
        <span>今日累计 Token: {{ quotaStore.tokensUsedToday.toLocaleString() }}</span>
        <span>单次上限 {{ (quotaStore.contextTokenLimit / 1000).toFixed(0) }}K</span>
      </div>
    </div>

    <!-- 底部角色徽章、快捷演示切换与设置/Admin/Dev入口 -->
    <div class="p-3 border-t border-subtle space-y-2.5 bg-card/60">
      <div v-if="!configStore.isSidebarCollapsed" class="space-y-1.5">
        <div class="flex items-center justify-between gap-2">
          <span
            class="text-[10px] font-mono font-semibold px-2 py-0.5 rounded-md border"
            :class="roleBadgeMeta.colorClass"
          >
            {{ roleBadgeMeta.label }}
          </span>

          <button
            v-if="authStore.isGuest"
            type="button"
            class="flex items-center gap-1 text-xs text-primary-light hover:underline font-medium"
            @click="authStore.openAuthOverlay('login')"
          >
            <LogIn class="w-3.5 h-3.5" />
            <span>登录 / 注册</span>
          </button>
          <button
            v-else
            type="button"
            class="flex items-center gap-1 text-xs text-muted hover:text-red-400 transition-colors"
            @click="authStore.logout()"
          >
            <LogOut class="w-3.5 h-3.5" />
            <span>退出</span>
          </button>
        </div>

        <div class="text-[11px] text-muted truncate">
          {{ authStore.displayName }} · {{ roleBadgeMeta.desc }}
        </div>

        <!-- 快捷四级角色体验切换器 -->
        <div class="grid grid-cols-4 gap-1 pt-1">
          <button
            v-for="r in (['guest', 'user', 'admin', 'dev'] as UserRole[])"
            :key="r"
            type="button"
            class="py-1 rounded text-[10px] font-mono uppercase border transition-all"
            :class="
              authStore.role === r
                ? 'bg-primary text-white border-primary font-semibold'
                : 'bg-elevated/60 text-muted border-subtle hover:text-main'
            "
            @click="authStore.switchDemoRole(r)"
          >
            {{ r }}
          </button>
        </div>
      </div>

      <!-- 系统功能入口栏 -->
      <div
        class="flex items-center gap-1.5"
        :class="configStore.isSidebarCollapsed ? 'flex-col' : 'justify-between'"
      >
        <button
          type="button"
          class="flex-1 flex items-center justify-center gap-1.5 py-1.5 px-2 rounded-lg text-xs text-sub hover:text-main hover:bg-elevated transition-colors"
          title="全局 Prompt 与同步设置"
          @click="configStore.showSettingsModal = true"
        >
          <Settings class="w-3.5 h-3.5" />
          <span v-if="!configStore.isSidebarCollapsed">设置</span>
        </button>

        <button
          v-if="authStore.isAdmin"
          type="button"
          class="flex-1 flex items-center justify-center gap-1.5 py-1.5 px-2 rounded-lg text-xs text-cyber-cyan bg-cyber-cyan/10 hover:bg-cyber-cyan/20 border border-cyber-cyan/30 transition-colors"
          title="管理员全局配额与模型看板"
          @click="configStore.showAdminModal = true"
        >
          <Shield class="w-3.5 h-3.5" />
          <span v-if="!configStore.isSidebarCollapsed">管理看板</span>
        </button>

        <button
          v-if="authStore.isDev"
          type="button"
          class="flex-1 flex items-center justify-center gap-1.5 py-1.5 px-2 rounded-lg text-xs text-indigo-300 bg-indigo-500/15 hover:bg-indigo-500/25 border border-indigo-500/30 transition-colors"
          title="开发者控制台 Dev Console"
          @click="configStore.showDevConsoleModal = true"
        >
          <Terminal class="w-3.5 h-3.5" />
          <span v-if="!configStore.isSidebarCollapsed">Dev 台</span>
        </button>
      </div>
    </div>
  </aside>
</template>
