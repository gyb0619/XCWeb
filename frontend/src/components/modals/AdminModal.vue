<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  Shield,
  X,
  Activity,
  Users,
  Cpu,
  CheckCircle2,
  Star,
  RefreshCw,
} from 'lucide-vue-next'
import { useConfigStore } from '@/stores/configStore'
import { apiRequest } from '@/utils/apiClient'
import type {
  AdminModelInfo,
  QuotaOverview,
} from '@/types/api'

const configStore = useConfigStore()

const isLoading = ref<boolean>(false)
const activeTab = ref<'overview' | 'models'>('overview')
const statusMessage = ref<string | null>(null)

const overview = ref<QuotaOverview>({
  totalCallsToday: 142,
  totalTokensToday: 89420,
  activeUsersToday: 18,
  activeGuestsToday: 34,
  roleDistribution: [
    { role: 'guest', calls: 64, tokens: 24500 },
    { role: 'user', calls: 58, tokens: 49200 },
    { role: 'admin', calls: 12, tokens: 8900 },
    { role: 'dev', calls: 8, tokens: 6820 },
  ],
  topIdentifiers: [
    { identifier: 'user:101', role: 'user', calls: 24, tokens: 19400 },
    { identifier: 'guest:guest_9f8a7b', role: 'guest', calls: 10, tokens: 3820 },
    { identifier: 'fp:e3b0c44298fc', role: 'guest', calls: 9, tokens: 3410 },
  ],
  dailyTrend: [
    { date: '10-24', calls: 96, tokens: 54000 },
    { date: '10-25', calls: 118, tokens: 68200 },
    { date: '10-26', calls: 135, tokens: 79100 },
    { date: '10-27', calls: 129, tokens: 74500 },
    { date: '10-28', calls: 142, tokens: 89420 },
  ],
})

const adminModels = ref<AdminModelInfo[]>([])

async function fetchAdminData() {
  isLoading.value = true
  statusMessage.value = null
  try {
    const [overviewRes, modelsRes] = await Promise.allSettled([
      apiRequest<QuotaOverview>('/api/admin/quota/overview'),
      apiRequest<{ models?: AdminModelInfo[] } | AdminModelInfo[]>('/api/admin/models'),
    ])

    if (overviewRes.status === 'fulfilled' && overviewRes.value) {
      overview.value = overviewRes.value
    }

    if (modelsRes.status === 'fulfilled' && modelsRes.value) {
      const val = modelsRes.value
      const list = Array.isArray(val) ? val : (val.models ?? [])
      if (list.length > 0) {
        adminModels.value = list
        return
      }
    }

    // 离线或后端未启动时，使用 configStore 中的预置模型矩阵展示
    adminModels.value = configStore.models.map((m) => ({
      ...m,
      keyConfigured: Boolean(m.keyConfigured ?? m.isMock),
    }))
  } finally {
    isLoading.value = false
  }
}

async function handleToggleModelEnabled(model: AdminModelInfo) {
  const nextEnabled = !model.enabled
  const payload = {
    id: model.id,
    modelId: model.id,
    enabled: nextEnabled,
    isDefault: model.isDefault,
  }

  try {
    await apiRequest<unknown>('/api/admin/models', {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  } catch {
    // 离线演示同步更新本地状态
  }

  model.enabled = nextEnabled
  const storeModel = configStore.models.find((m) => m.id === model.id)
  if (storeModel) storeModel.enabled = nextEnabled
  statusMessage.value = `已${nextEnabled ? '启用' : '停用'}预置模型：${model.name}`
}

async function handleSetDefaultModel(model: AdminModelInfo) {
  const payload = {
    id: model.id,
    modelId: model.id,
    enabled: true,
    isDefault: true,
  }

  try {
    await apiRequest<unknown>('/api/admin/models', {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  } catch {
    // 离线演示同步更新本地状态
  }

  for (const item of adminModels.value) {
    item.isDefault = item.id === model.id
    if (item.id === model.id) item.enabled = true
  }
  for (const item of configStore.models) {
    item.isDefault = item.id === model.id
    if (item.id === model.id) item.enabled = true
  }
  configStore.selectModel(model.id)
  statusMessage.value = `已将「${model.name}」设为全局默认预置模型`
}

watch(
  () => configStore.showAdminModal,
  (visible) => {
    if (visible) {
      void fetchAdminData()
    }
  },
)

onMounted(() => {
  if (configStore.showAdminModal) {
    void fetchAdminData()
  }
})
</script>

<template>
  <div
    v-if="configStore.showAdminModal"
    class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/65 backdrop-blur-md"
  >
    <div
      class="w-full max-w-3xl max-h-[86vh] flex flex-col rounded-2xl glass-modal border border-cyber-cyan/35 overflow-hidden animate-slide-up"
    >
      <!-- 顶栏 -->
      <div class="flex items-center justify-between px-6 py-4 border-b border-subtle">
        <div class="flex items-center gap-2.5">
          <div
            class="w-9 h-9 rounded-xl bg-cyber-cyan/15 border border-cyber-cyan/40 flex items-center justify-center text-cyber-cyan"
          >
            <Shield class="w-5 h-5" />
          </div>
          <div>
            <h2 class="text-sm font-bold text-main flex items-center gap-2">
              <span>管理员全局管控中心 (Admin Console)</span>
              <span
                class="text-[10px] font-mono px-2 py-0.5 rounded bg-cyber-cyan/15 text-cyber-cyan"
              >
                ROLE = ADMIN
              </span>
            </h2>
            <p class="text-xs text-muted">
              全局配额监控看板与服务端预置模型启停管理（API Key 由服务端环境变量安全托管）
            </p>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <button
            type="button"
            class="p-2 rounded-lg text-sub hover:text-main hover:bg-elevated transition-colors"
            title="刷新数据"
            @click="fetchAdminData"
          >
            <RefreshCw class="w-4 h-4" :class="isLoading ? 'animate-spin' : ''" />
          </button>
          <button
            type="button"
            class="p-2 rounded-lg text-sub hover:text-main hover:bg-elevated transition-colors"
            @click="configStore.showAdminModal = false"
          >
            <X class="w-4 h-4" />
          </button>
        </div>
      </div>

      <!-- Tab 切换栏 -->
      <div class="flex items-center gap-2 px-6 pt-3 border-b border-subtle bg-elevated/30">
        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 text-xs font-semibold border-b-2 transition-colors"
          :class="
            activeTab === 'overview'
              ? 'border-cyber-cyan text-cyber-cyan'
              : 'border-transparent text-sub hover:text-main'
          "
          @click="activeTab = 'overview'"
        >
          <Activity class="w-3.5 h-3.5" />
          <span>全局配额监控看板 (/api/admin/quota/overview)</span>
        </button>

        <button
          type="button"
          class="flex items-center gap-1.5 px-4 py-2 text-xs font-semibold border-b-2 transition-colors"
          :class="
            activeTab === 'models'
              ? 'border-cyber-cyan text-cyber-cyan'
              : 'border-transparent text-sub hover:text-main'
          "
          @click="activeTab = 'models'"
        >
          <Cpu class="w-3.5 h-3.5" />
          <span>预置模型启停与默认配置 (/api/admin/models)</span>
        </button>
      </div>

      <!-- 内容区 -->
      <div class="flex-1 overflow-y-auto p-6 space-y-5">
        <div
          v-if="statusMessage"
          class="px-3.5 py-2 rounded-xl bg-cyber-cyan/15 border border-cyber-cyan/30 text-xs text-cyber-cyan flex items-center justify-between"
        >
          <span>{{ statusMessage }}</span>
          <button type="button" @click="statusMessage = null">
            <X class="w-3.5 h-3.5" />
          </button>
        </div>

        <!-- Tab 1: 全局配额监控看板 -->
        <template v-if="activeTab === 'overview'">
          <div class="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div class="p-3.5 rounded-xl bg-elevated/50 border border-subtle">
              <div class="text-[11px] text-muted">今日总对话调用</div>
              <div class="text-xl font-bold font-mono text-main mt-1">
                {{ overview.totalCallsToday.toLocaleString() }}
              </div>
            </div>
            <div class="p-3.5 rounded-xl bg-elevated/50 border border-subtle">
              <div class="text-[11px] text-muted">今日实际消耗 Token</div>
              <div class="text-xl font-bold font-mono text-cyber-cyan mt-1">
                {{ overview.totalTokensToday.toLocaleString() }}
              </div>
            </div>
            <div class="p-3.5 rounded-xl bg-elevated/50 border border-subtle">
              <div class="text-[11px] text-muted">今日活跃注册用户</div>
              <div class="text-xl font-bold font-mono text-primary-light mt-1">
                {{ overview.activeUsersToday }}
              </div>
            </div>
            <div class="p-3.5 rounded-xl bg-elevated/50 border border-subtle">
              <div class="text-[11px] text-muted">今日活跃游客会话</div>
              <div class="text-xl font-bold font-mono text-amber-400 mt-1">
                {{ overview.activeGuestsToday }}
              </div>
            </div>
          </div>

          <!-- 角色维度分布表 -->
          <div class="rounded-xl border border-subtle overflow-hidden">
            <div
              class="px-4 py-2.5 bg-elevated/60 border-b border-subtle text-xs font-semibold text-main flex items-center gap-2"
            >
              <Users class="w-3.5 h-3.5 text-cyber-cyan" />
              <span>四级角色当日调用与 Token 审计分布</span>
            </div>
            <table class="w-full text-left text-xs">
              <thead class="bg-elevated/30 text-muted border-b border-subtle">
                <tr>
                  <th class="py-2 px-4">角色等级</th>
                  <th class="py-2 px-4">当日调用次数</th>
                  <th class="py-2 px-4">当日累计 Token</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-subtle/60">
                <tr v-for="row in overview.roleDistribution" :key="row.role">
                  <td class="py-2.5 px-4 font-mono uppercase text-main">{{ row.role }}</td>
                  <td class="py-2.5 px-4 font-mono text-sub">{{ row.calls }} 次</td>
                  <td class="py-2.5 px-4 font-mono text-cyber-cyan">
                    {{ row.tokens.toLocaleString() }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>

        <!-- Tab 2: 预置模型启停与默认切换 -->
        <template v-else>
          <div
            class="p-3.5 rounded-xl bg-primary/10 border border-primary/30 text-xs text-sub flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3"
          >
            <div class="flex items-center gap-2.5">
              <Cpu class="w-4 h-4 text-primary-light shrink-0" />
              <span>
                <strong>模型配置与管理</strong>：已全面对所有用户开放自定义端点、API Key 与系统环境变量授权读取。
              </span>
            </div>
            <button
              type="button"
              class="cyber-btn-primary px-3 py-1.5 rounded-lg text-xs font-semibold shrink-0 shadow-cyber-glow"
              @click="
                configStore.showModelConfigModal = true;
                configStore.showAdminModal = false;
              "
            >
              打开 AI 模型详细配置面板
            </button>
          </div>

          <div class="space-y-2.5">
            <div
              v-for="model in adminModels"
              :key="model.id"
              class="p-4 rounded-xl bg-elevated/50 border border-subtle flex flex-col sm:flex-row sm:items-center justify-between gap-3"
            >
              <div class="space-y-1">
                <div class="flex items-center gap-2 flex-wrap">
                  <span class="text-xs font-bold text-main">{{ model.name }}</span>
                  <span class="text-[10px] font-mono px-2 py-0.5 rounded bg-card text-sub">
                    {{ model.provider }}
                  </span>
                  <span
                    v-if="model.isDefault"
                    class="inline-flex items-center gap-1 text-[10px] px-2 py-0.5 rounded bg-primary/20 text-primary-light border border-primary/40"
                  >
                    <Star class="w-3 h-3 fill-current" />
                    默认模型
                  </span>
                  <span
                    class="text-[10px] px-2 py-0.5 rounded font-mono"
                    :class="
                      model.keyConfigured || model.isMock
                        ? 'bg-emerald-500/15 text-emerald-400'
                        : 'bg-amber-500/15 text-amber-400'
                    "
                  >
                    {{
                      model.isMock
                        ? '内置免 Key'
                        : model.keyConfigured
                          ? '环境变量 Key 已就绪'
                          : '未配置 Key (自动 Mock 降级)'
                    }}
                  </span>
                </div>
                <p class="text-[11px] text-muted">{{ model.description }}</p>
                <div class="text-[10px] font-mono text-muted">ID: {{ model.id }}</div>
              </div>

              <div class="flex items-center gap-2 shrink-0">
                <button
                  v-if="!model.isDefault"
                  type="button"
                  class="px-3 py-1.5 rounded-lg text-xs bg-elevated hover:bg-primary/20 text-sub hover:text-primary-light border border-subtle transition-colors"
                  @click="handleSetDefaultModel(model)"
                >
                  设为默认
                </button>

                <button
                  type="button"
                  class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors"
                  :class="
                    model.enabled
                      ? 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/25'
                      : 'bg-red-500/15 text-red-400 border-red-500/30 hover:bg-red-500/25'
                  "
                  @click="handleToggleModelEnabled(model)"
                >
                  <CheckCircle2 class="w-3.5 h-3.5" />
                  <span>{{ model.enabled ? '已启用' : '已停用' }}</span>
                </button>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
