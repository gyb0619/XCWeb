<script setup lang="ts">
import { ref, watch } from 'vue'
import { Settings, X, Cloud, Save, RotateCcw, Sparkles } from 'lucide-vue-next'
import { useConfigStore } from '@/stores/configStore'
import { useAuthStore } from '@/stores/authStore'

const configStore = useConfigStore()
const authStore = useAuthStore()

const draftPrompt = ref<string>(configStore.systemPrompt)
const draftThinking = ref<boolean>(configStore.enableThinking)
const draftAgent = ref<boolean>(configStore.enableAgent)
const saveSuccess = ref<boolean>(false)

watch(
  () => configStore.showSettingsModal,
  (open) => {
    if (open) {
      draftPrompt.value = configStore.systemPrompt
      draftThinking.value = configStore.enableThinking
      draftAgent.value = configStore.enableAgent
      saveSuccess.value = false
    }
  },
)

function handleResetPrompt() {
  draftPrompt.value =
    '你是 XCWeb（Xtreme Cognitive Web）智能认知中枢，请以严谨、清晰、富有洞察力的结构化方式回答用户问题。'
}

async function handleSave() {
  await configStore.updateSettings({
    systemPrompt: draftPrompt.value.trim(),
    enableThinking: draftThinking.value,
    enableAgent: draftAgent.value,
  })
  saveSuccess.value = true
  setTimeout(() => {
    saveSuccess.value = false
    configStore.showSettingsModal = false
  }, 600)
}
</script>

<template>
  <div
    v-if="configStore.showSettingsModal"
    class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/65 backdrop-blur-md"
  >
    <div
      class="w-full max-w-lg rounded-2xl glass-modal p-6 border border-subtle space-y-5 animate-slide-up"
    >
      <!-- 标题栏 -->
      <div class="flex items-center justify-between border-b border-subtle pb-3.5">
        <div class="flex items-center gap-2.5">
          <div
            class="w-8 h-8 rounded-xl bg-primary/15 border border-primary/30 flex items-center justify-center text-primary-light"
          >
            <Settings class="w-4 h-4" />
          </div>
          <div>
            <h3 class="text-sm font-bold text-main">全局个性化设置与云端同步</h3>
            <p class="text-[11px] text-muted">
              配置全局 System Prompt、默认推理偏好与多端实时同步
            </p>
          </div>
        </div>
        <button
          type="button"
          class="p-1.5 rounded-lg text-muted hover:text-main hover:bg-elevated"
          @click="configStore.showSettingsModal = false"
        >
          <X class="w-4 h-4" />
        </button>
      </div>

      <!-- 全局个性化 System Prompt -->
      <div class="space-y-2">
        <div class="flex items-center justify-between text-xs">
          <label class="font-semibold text-main">全局 System Prompt（系统指令）</label>
          <button
            type="button"
            class="inline-flex items-center gap-1 text-[11px] text-primary-light hover:underline"
            @click="handleResetPrompt"
          >
            <RotateCcw class="w-3 h-3" />
            <span>恢复默认</span>
          </button>
        </div>
        <textarea
          v-model="draftPrompt"
          rows="4"
          class="w-full rounded-xl bg-elevated/70 border border-subtle p-3 text-xs text-main placeholder-muted focus:outline-none focus:border-primary/60 leading-relaxed resize-none"
          placeholder="输入全局系统角色设定..."
        />
      </div>

      <!-- 推理与 Agent 开关 -->
      <div class="space-y-2.5">
        <label class="flex items-center justify-between p-3 rounded-xl bg-elevated/50 border border-subtle cursor-pointer">
          <div>
            <div class="text-xs font-semibold text-main">默认开启深度思考（Thinking Drawer）</div>
            <div class="text-[11px] text-muted">在回答前展示推理模型的思维链与实时计时</div>
          </div>
          <input
            v-model="draftThinking"
            type="checkbox"
            class="w-4 h-4 accent-blue-600 rounded"
          />
        </label>

        <label class="flex items-center justify-between p-3 rounded-xl bg-elevated/50 border border-subtle cursor-pointer">
          <div>
            <div class="text-xs font-semibold text-main">默认开启 Agent 可视化思考时间轴</div>
            <div class="text-[11px] text-muted">触发白名单工具调用时展示 规划 → 调用 → 回传 → 总结 时间轴</div>
          </div>
          <input
            v-model="draftAgent"
            type="checkbox"
            class="w-4 h-4 accent-blue-600 rounded"
          />
        </label>
      </div>

      <!-- 云端同步状态说明 -->
      <div
        class="p-3 rounded-xl border text-xs flex items-center justify-between gap-2"
        :class="
          authStore.isLoggedIn
            ? 'bg-cyber-cyan/10 border-cyber-cyan/30 text-cyber-cyan'
            : 'bg-amber-500/10 border-amber-500/30 text-amber-300'
        "
      >
        <div class="flex items-center gap-2">
          <Cloud class="w-4 h-4 shrink-0" />
          <span v-if="authStore.isLoggedIn">
            已开启 SQLite 云端双向同步（/api/sync/settings）
          </span>
          <span v-else>
            当前为游客模式，设置仅保存在本地浏览器；登录后可开启多端同步
          </span>
        </div>
        <Sparkles class="w-4 h-4 shrink-0 opacity-75" />
      </div>

      <!-- 底部操作栏 -->
      <div class="flex items-center justify-end gap-2.5 pt-2">
        <button
          type="button"
          class="px-4 py-2 rounded-xl bg-elevated text-sub hover:text-main text-xs"
          @click="configStore.showSettingsModal = false"
        >
          取消
        </button>
        <button
          type="button"
          class="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl cyber-btn-primary text-xs font-semibold"
          @click="handleSave"
        >
          <Save class="w-3.5 h-3.5" />
          <span>{{ saveSuccess ? '已保存' : '保存设置' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>
