/**
 * XCWeb 前后端架构与接口契约强类型定义 (Frozen Contract v1.0)
 * 严格对齐 docs/API_CONTRACT.md 与 docs/PRD.md，严禁使用 any。
 */

export type UserRole = 'guest' | 'user' | 'admin' | 'dev'

export type ThemeMode = 'dark' | 'light' | 'incognito'

export type MessageRole = 'user' | 'assistant' | 'system'

export interface UserInfo {
  id: number
  username: string
  email?: string | null
  role: UserRole
  createdAt?: string
}

export interface GuestSession {
  token: string
  guestId: string
  fingerprintHash: string
  role: 'guest'
  expiresAt: number
}

export interface AuthResponse {
  token: string
  user: UserInfo
}

export interface QuotaDimensions {
  guestUsed: number
  guestLimit: number
  fingerprintUsed: number
  fingerprintLimit: number
  ipUsed: number
  ipLimit: number
}

export interface QuotaStatus {
  role: UserRole
  used: number
  limit: number
  unlimited: boolean
  tokensUsedToday: number
  contextTokenLimit: number
  resetAt: string
  resetInSeconds: number
  dimensions?: QuotaDimensions
}

export type Quota = QuotaStatus

export interface MeResponse {
  user: UserInfo
  quota: QuotaStatus
}

export interface QuotaExceededError {
  code: 'QUOTA_EXCEEDED'
  role: UserRole
  used: number
  limit: number
  resetAt: string
  message: string
}

export interface ContextTooLongError {
  code: 'CONTEXT_TOO_LONG'
  role: UserRole
  estimatedTokens: number
  limit: number
  message: string
}

export interface ModelInfo {
  id: string
  name: string
  provider: string
  description: string
  supportsThinking: boolean
  enabled: boolean
  isDefault: boolean
  isMock: boolean
  keyConfigured?: boolean
  baseUrl?: string
  apiKey?: string
  hasApiKey?: boolean
}

export interface AdminModelInfo extends ModelInfo {
  keyConfigured: boolean
}

export interface ImportEnvResponse {
  success: boolean
  consentGranted: boolean
  detectedVariables: string[]
  updatedCount: number
  message: string
  models: AdminModelInfo[]
}

export interface ModelUpdatePayload {
  modelId?: string
  id?: string
  name?: string
  provider?: string
  baseUrl?: string
  apiKey?: string
  description?: string
  supportsThinking?: boolean
  enabled?: boolean
  isDefault?: boolean
  clearApiKey?: boolean
}

export interface ModelsListResponse {
  defaultModel: string
  allExternalKeysMissing: boolean
  models: ModelInfo[]
}

export interface AdminModelUpdatePayload {
  modelId: string
  enabled: boolean
  isDefault: boolean
}

export type AgentPhase = 'plan' | 'tool' | 'observation' | 'synthesis'

export interface AgentStep {
  stepId: string
  phase: AgentPhase
  title: string
  toolName: string
  status: 'running' | 'completed' | 'error'
  input: string
  output: string
  durationMs: number
}

export interface RagChunk {
  index: number
  docName: string
  chunkId: string
  content: string
  score: number
}

export interface Attachment {
  id: string
  name: string
  size: number
  type: string
  content?: string
  chunksCount?: number
}

export interface SSEMetaEvent {
  requestId: string
  model: string
  provider: string
  mock: boolean
  fallbackReason?: string | null
}

export interface SSEDeltaEvent {
  delta: string
}

export interface SSEUsageEvent {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimated: boolean
  mock: boolean
}

export interface SSEErrorEvent {
  code: string
  message: string
}

export interface SSEDoneEvent {
  finishReason: string
  latencyMs: number
}

export interface MessageVersion {
  versionId: string
  content: string
  thinkingContent?: string | null
  thinkingDurationMs?: number
  agentSteps?: AgentStep[]
  ragCitations?: RagChunk[]
  model?: string
  isMock?: boolean
  fallbackReason?: string | null
  usage?: SSEUsageEvent
  createdAt: number
}

export interface Message {
  id: string
  conversationId: string
  role: MessageRole
  content: string
  thinkingContent?: string | null
  thinkingDurationMs?: number
  isThinking?: boolean
  isStreaming?: boolean
  isRetrying?: boolean
  attachments?: Attachment[]
  attachmentsJson?: string
  versions?: MessageVersion[]
  versionsJson?: string
  currentVersionIndex?: number
  agentSteps?: AgentStep[]
  ragCitations?: RagChunk[]
  model?: string
  isMock?: boolean
  fallbackReason?: string | null
  usage?: SSEUsageEvent
  createdAt: number
}

export interface Conversation {
  id: string
  title: string
  model: string
  createdAt: number
  updatedAt: number
  isIncognito?: boolean
  messages: Message[]
}

export interface UserSettings {
  systemPrompt: string
  theme: ThemeMode
  defaultModel: string
  enableThinking: boolean
  enableAgent: boolean
  updatedAt?: number
}

export interface SyncSettingsResponse {
  settingsJson: string
  updatedAt: number
}

export interface SyncConversationResponse {
  status: 'synced' | 'server_newer'
  conversation?: Conversation
}

export interface QuotaOverview {
  totalCallsToday: number
  totalTokensToday: number
  activeUsersToday: number
  activeGuestsToday: number
  roleDistribution: Array<{
    role: UserRole
    calls: number
    tokens: number
  }>
  topIdentifiers: Array<{
    identifier: string
    role: UserRole
    calls: number
    tokens: number
  }>
  dailyTrend: Array<{
    date: string
    calls: number
    tokens: number
  }>
}

export interface DevMetrics {
  totalRequests: number
  avgLatencyMs: number
  mockFallbackRate: number
  throughputSeries: Array<{
    timestamp: string
    requestsPerMin: number
    tokensPerMin: number
  }>
  tokenHeatmap: Array<[number, number, number]>
  modelDistribution: Array<{
    model: string
    count: number
    tokens: number
  }>
}

export interface RequestLogItem {
  id: number
  identifier: string
  model: string
  isMock: boolean
  promptTokens: number
  completionTokens: number
  latencyMs: number
  status: string
  createdAt: number
}

export interface ChatCompletionRequest {
  conversationId: string
  model: string
  messages: Array<{
    role: MessageRole
    content: string
  }>
  enableThinking: boolean
  enableAgent: boolean
  ragContext?: RagChunk[]
  temperature?: number
}
