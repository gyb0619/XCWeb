<script setup lang="ts">
import { onMounted } from 'vue'
import Sidebar from '@/components/layout/Sidebar.vue'
import TopBar from '@/components/layout/TopBar.vue'
import MessageList from '@/components/chat/MessageList.vue'
import InputArea from '@/components/chat/InputArea.vue'
import QuotaExceededModal from '@/components/modals/QuotaExceededModal.vue'
import AdminModal from '@/components/modals/AdminModal.vue'
import SettingsModal from '@/components/modals/SettingsModal.vue'
import DevConsoleModal from '@/components/modals/DevConsoleModal.vue'
import ModelConfigModal from '@/components/modals/ModelConfigModal.vue'
import AuthDialog from '@/components/auth/AuthDialog.vue'
import ThemeSweepOverlay from '@/components/motion/ThemeSweepOverlay.vue'
import { useChatStream } from '@/composables/useChatStream'
import { useAuthStore } from '@/stores/authStore'
import { useConfigStore } from '@/stores/configStore'
import { useChatStore } from '@/stores/chatStore'

const authStore = useAuthStore()
const configStore = useConfigStore()
const chatStore = useChatStore()
const { executeChatStream } = useChatStream()

// 注册阶段 3 (@AI-LLM-Engineer) 全链路 SSE 流式解析与断网高仿真降级执行器
chatStore.registerCustomStreamExecutor(executeChatStream)

onMounted(async () => {
  await Promise.all([
    authStore.initAuth(),
    configStore.fetchModels(),
  ])
  await chatStore.loadConversations()
})
</script>

<template>
  <div
    id="xcweb-app-root"
    class="relative flex h-screen w-screen overflow-hidden bg-page text-main transition-colors duration-300"
  >
    <!-- 阶段 2 (@Motion-FX-Engineer) 135° SVG Polygon 对角线扫屏图层挂载点 -->
    <div id="theme-sweep-overlay-mount" class="pointer-events-none fixed inset-0 z-40">
      <ThemeSweepOverlay />
    </div>

    <!-- 左侧可折叠导航与配额栏 -->
    <Sidebar />

    <!-- 右侧主工作区 -->
    <div class="flex-1 flex flex-col min-w-0 h-full relative">
      <TopBar />
      <MessageList />
      <InputArea />
    </div>

    <!-- 系统弹窗层 -->
    <QuotaExceededModal />
    <AdminModal />
    <SettingsModal />
    <DevConsoleModal />
    <ModelConfigModal v-if="configStore.showModelConfigModal" />
    <AuthDialog />
  </div>
</template>
