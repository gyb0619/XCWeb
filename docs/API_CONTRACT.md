# XCWeb 前后端架构与接口契约规范（Frozen Contract v1.0）

> 本文档由 `@Lead-Architect` 依据 `docs/PRD.md` 与 `docs/PROMPTS.md` 制定并冻结。所有子代理（`@Backend-Specialist`、`@Frontend-Specialist`、`@Motion-FX-Engineer`、`@AI-LLM-Engineer`、`@Quality-Auditor-QA`）须严格遵守本契约。

---

## 1. 工程目录与端口约定

- **后端工程目录**：`D:\Code\Other\XCWeb\backend`
  - 构建工具：Maven（Spring Boot `4.1.1`，JDK `21`）
  - 服务端口：`8080`
  - 数据库文件：`xcweb.db`（SQLite 嵌入式，首次启动自动执行 DDL 初始化）
- **前端工程目录**：`D:\Code\Other\XCWeb\frontend`
  - 构建工具：Vite 6 + Vue 3.5+ + TypeScript（严格模式）+ Tailwind CSS + Pinia
  - 开发端口：`5173`（配置 `/api` 代理转发至 `http://localhost:8080`）

---

## 2. 鉴权与请求头规范

### 2.1 请求头约定

| 请求头 | 适用角色 | 说明 |
| :--- | :--- | :--- |
| `Authorization` | 全部已鉴权请求 | `Bearer <token>`（用户/管理员/开发者为 JWT，有效期 7 天；游客为签名匿名令牌，有效期 30 天） |
| `X-Fingerprint` | 游客（Guest）必传，其他角色可选 | 浏览器指纹 SHA-256 哈希字符串（64 位十六进制或 32+ 字符哈希） |

### 2.2 四级角色定义

- `guest`：游客（每日 10 次对话，单次上下文上限 4,000 Tokens，仅本地 IndexedDB 存储，不支持云端同步与无痕模式）
- `user`：普通用户（每日 50 次对话，单次上下文上限 16,000 Tokens，支持无痕模式、云端同步、全局个性化 Prompt、多版本回答管理）
- `admin`：管理员（不限次数与上下文，支持预置模型启停与默认模型设置、全局配额监控看板）
- `dev`：开发者（不限次数与上下文，支持 Dev Console：脱敏 API 请求记录、Prompt 调优沙箱、Token 消耗热力图、Agent 决策追踪、导出调试数据）

### 2.3 初始特权账号机制

- 公开注册接口 `POST /api/auth/register` **仅允许创建 `user` 角色**。
- `admin` 与 `dev` 账号由后端 `DatabaseInitializer` 在启动时读取环境变量创建（或更新密码哈希）：
  - `XCWEB_ADMIN_USERNAME`（默认 `admin`）、`XCWEB_ADMIN_PASSWORD`
  - `XCWEB_DEV_USERNAME`（默认 `developer`）、`XCWEB_DEV_PASSWORD`
  - 若环境变量未注入密码，为了支持开箱即用本地验收测试，仅在开发环境（`xcweb.security.init-demo-accounts=true`）使用随机或配置注入的初始口令并在启动控制台提示，Java 源码中严禁写死明文密码常量。

---

## 3. RESTful API 详细契约

### 3.1 认证接口（`/api/auth`）

#### 1) `POST /api/auth/guest`（公开）
- **功能**：上报浏览器指纹哈希，签发游客签名匿名令牌（有效期 30 天）。
- **防刷限制**：同一指纹或同一 IP 每日最多签发 `5` 个匿名令牌（可配置）。超出时返回 `429`（`code: "TOKEN_ISSUE_LIMIT_EXCEEDED"`）。
- **Request Body**：
  ```json
  {
    "fingerprintHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
  ```
- **Response 200**：
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "guestId": "guest_9f8a7b6c5d4e",
    "fingerprintHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "role": "guest",
    "expiresAt": 1795766400000
  }
  ```

#### 2) `POST /api/auth/register`（公开）
- **功能**：注册普通用户账号（强制 `role = "user"`）。
- **Request Body**：
  ```json
  {
    "username": "alice",
    "email": "alice@example.com",
    "password": "Password123!"
  }
  ```
- **Response 200**：
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "username": "alice",
      "email": "alice@example.com",
      "role": "user",
      "createdAt": "2026-10-28T16:00:00Z"
    }
  }
  ```

#### 3) `POST /api/auth/login`（公开）
- **功能**：账号密码登录（支持用户名或邮箱），下发 7 天有效期 JWT。
- **Request Body**：
  ```json
  {
    "username": "alice",
    "password": "Password123!"
  }
  ```
- **Response 200**：同 `/api/auth/register`。

#### 4) `GET /api/auth/me`（需鉴权：User / Admin / Dev，若 Guest 调用也可返回当前 Guest 身份与配额）
- **Response 200**：
  ```json
  {
    "user": {
      "id": 1,
      "username": "alice",
      "email": "alice@example.com",
      "role": "user",
      "createdAt": "2026-10-28T16:00:00Z"
    },
    "quota": {
      "role": "user",
      "used": 5,
      "limit": 50,
      "unlimited": false,
      "tokensUsedToday": 4210,
      "contextTokenLimit": 16000,
      "resetAt": "2026-10-29T00:00:00+08:00",
      "resetInSeconds": 25800
    }
  }
  ```

---

### 3.2 配额查询接口（`/api/quota`）

#### `GET /api/quota/status`（全部角色）
- **Response 200**：
  ```json
  {
    "role": "guest",
    "used": 3,
    "limit": 10,
    "unlimited": false,
    "tokensUsedToday": 1280,
    "contextTokenLimit": 4000,
    "resetAt": "2026-10-29T00:00:00+08:00",
    "resetInSeconds": 25800,
    "dimensions": {
      "guestUsed": 3,
      "guestLimit": 10,
      "fingerprintUsed": 3,
      "fingerprintLimit": 10,
      "ipUsed": 3,
      "ipLimit": 30
    }
  }
  ```
- **配额超限响应（HTTP `429 Too Many Requests`）**：
  ```json
  {
    "code": "QUOTA_EXCEEDED",
    "role": "guest",
    "used": 10,
    "limit": 10,
    "resetAt": "2026-10-29T00:00:00+08:00",
    "message": "今日游客体验额度已用尽（10/10 次）。注册登录即可获得 5 倍额度，并开启云端存档。"
  }
  ```
- **上下文超限响应（HTTP `413 Payload Too Large`）**：
  ```json
  {
    "code": "CONTEXT_TOO_LONG",
    "role": "guest",
    "estimatedTokens": 4350,
    "limit": 4000,
    "message": "单次提交上下文超出当前角色上限（4,000 Tokens），不消耗对话次数。"
  }
  ```

---

### 3.3 模型接口（`/api/models`）

#### `GET /api/models`（全部角色）
- **功能**：返回当前启用的预置模型列表与默认模型 ID。
- **规则**：
  - 预置模型矩阵：
    1. `deepseek-ai/DeepSeek-R1-Distill-Qwen-7B`（硅基流动 SiliconFlow，支持深度思考，默认主力模型）
    2. `Qwen/Qwen2.5-7B-Instruct`（硅基流动 SiliconFlow，通用对话模型）
    3. `glm-4-flash`（智谱 AI BigModel，长上下文与快速问答）
    4. `llama-3.3-70b-versatile`（Groq Cloud，高速代码与响应）
    5. `mock-cognitive-core`（内置 Mock，无需网络与 Key，模拟深度思考与打字机输出）
  - 未配置 Key 的外部模型不出现在普通可用列表中（但当所有外部模型均未配置 Key 时，`mock-cognitive-core` 始终可用，且后端支持将针对默认模型的请求平滑降级至 Mock 并标记 `mock = true`）。
- **Response 200**：
  ```json
  {
    "defaultModel": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
    "allExternalKeysMissing": true,
    "models": [
      {
        "id": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
        "name": "DeepSeek-R1-Distill-Qwen-7B",
        "provider": "SiliconFlow",
        "description": "默认主力推理模型，支持深度思考思维链输出",
        "supportsThinking": true,
        "enabled": true,
        "isDefault": true,
        "isMock": false,
        "keyConfigured": false
      },
      {
        "id": "mock-cognitive-core",
        "name": "XCWeb Cognitive Mock Core",
        "provider": "BuiltInMock",
        "description": "无需网络与 Key，内置模拟深度思考、Agent 工具链与流式输出",
        "supportsThinking": true,
        "enabled": true,
        "isDefault": false,
        "isMock": true,
        "keyConfigured": true
      }
    ]
  }
  ```

---

### 3.4 核心流式对话接口（`POST /api/chat/completions`）

- **鉴权**：全部角色（游客需通过令牌 + `X-Fingerprint` + IP 三重校验）。
- **响应类型**：`text/event-stream;charset=UTF-8`（若在进入流前触发鉴权失败、413 上下文超限、429 配额超限，则直接返回对应 HTTP 状态码与 JSON 错误体）。
- **Request Body**：
  ```json
  {
    "conversationId": "conv_01",
    "model": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
    "messages": [
      { "role": "system", "content": "You are a helpful AI assistant." },
      { "role": "user", "content": "请分析 2026 年 AI Native Web 架构趋势并给出对比表格" }
    ],
    "enableThinking": true,
    "enableAgent": true,
    "ragContext": [
      {
        "index": 1,
        "docName": "architecture_notes.md",
        "chunkId": "c1",
        "content": "分块原文内容...",
        "score": 0.91
      }
    ],
    "temperature": 0.7
  }
  ```
- **SSE 事件规范**：
  每个事件使用标准 SSE 格式 `event: <eventName>\ndata: <jsonString>\n\n`：
  1. `meta`：首包元信息
     ```json
     {"requestId":"req_123","model":"deepseek-ai/DeepSeek-R1-Distill-Qwen-7B","provider":"SiliconFlow","mock":true,"fallbackReason":"NO_API_KEY"}
     ```
  2. `agent_step`：当触发 Agent 工具调用时输出（白名单工具：`weather_query`、`web_search`、`data_analyzer`、`code_sandbox`）
     ```json
     {"stepId":"step_1","phase":"plan","title":"任务规划与意图解析","toolName":"planner","status":"completed","input":"用户请求分析...","output":"拆解为检索、数据聚合与结构化生成三步","durationMs":85}
     ```
     `phase` 取值依次为：`plan`（规划） -> `tool`（工具调用） -> `observation`（数据回传） -> `synthesis`（总结分析）。
  3. `thinking`：深度思考增量片段（后端自动将 `<think>...</think>` 或 `reasoning_content` 规范化为此事件）
     ```json
     {"delta":"首先我们需要拆解用户的问题..."}
     ```
  4. `content`：正文回答增量片段
     ```json
     {"delta":"以下是结构化对比分析：\n\n"}
     ```
  5. `usage`：流末尾 Token 统计
     ```json
     {"promptTokens":156,"completionTokens":412,"totalTokens":568,"estimated":false,"mock":true}
     ```
  6. `error`：流式过程中发生异常
     ```json
     {"code":"STREAM_INTERRUPTED","message":"上游连接中断"}
     ```
  7. `done`：流结束标识
     ```json
     {"finishReason":"stop","latencyMs":1850}
     ```

---

### 3.5 云端同步接口（`/api/sync`，仅限 User / Admin / Dev）

- **`GET /api/sync/conversations?since=0`**：增量拉取 `updated_at > since` 的会话列表（含每个会话下的 `messages`）。
- **`POST /api/sync/conversations`**：上报或更新单个会话。
  - Request Body：
    ```json
    {
      "id": "conv_123",
      "title": "AI 架构探讨",
      "model": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
      "createdAt": 1793190000000,
      "updatedAt": 1793190500000,
      "messages": [
        {
          "id": "msg_1",
          "conversationId": "conv_123",
          "role": "user",
          "content": "你好",
          "thinkingContent": null,
          "attachmentsJson": "[]",
          "versionsJson": "[]",
          "createdAt": 1793190000000
        }
      ]
    }
    ```
  - 冲突仲裁：若数据库中该 `id` 的 `updated_at > request.updatedAt`，返回 `status: "server_newer"` 及服务端最新会话数据；否则写入数据库并返回 `status: "synced"`。
- **`DELETE /api/sync/conversations/{id}`**：删除指定云端会话。
- **`GET /api/sync/settings`**：获取用户云端个性化设置（`settingsJson`、`updatedAt`）。
- **`PUT /api/sync/settings`**：保存用户云端个性化设置（含 `systemPrompt`、`theme`、`defaultModel`、`enableThinking`、`enableAgent` 等）。

---

### 3.6 管理员接口（`/api/admin`，仅限 Admin）

- **`GET /api/admin/models`**：获取全部预置模型及其启停状态、默认模型标识、Key 是否已配置。
- **`PUT /api/admin/models` 与 `PUT /api/admin/models/{modelId}`**：
  - 注意：因 `modelId` 可能包含 `/`（如 `deepseek-ai/DeepSeek-R1-Distill-Qwen-7B`），前端可将 `modelId` 进行 `encodeURIComponent` 或同时在 Request Body 中传递 `modelId`，后端需同时支持路径匹配与 Body 读取。
  - Request Body：
    ```json
    {
      "modelId": "glm-4-flash",
      "enabled": true,
      "isDefault": false
    }
    ```
  - 权限边界：只能修改预置模型的 `enabled` 和 `isDefault`，**绝不允许新增模型、修改 endpoint 或传入 API Key**（若请求体含 `apiKey` 或 `endpoint` 等非法字段或未知 `modelId`，直接拒绝并返回 `400 Bad Request`）。
- **`GET /api/admin/quota/overview`**：全局配额监控看板数据（今日总调用次数、今日总 Token、活跃用户/游客数、各角色调用分布、Top 排行、近 7 日趋势）。

---

### 3.7 开发者接口（`/api/dev`，仅限 Dev）

- **`GET /api/dev/metrics`**：返回 API 吞吐量序列、24 小时 × 7 天 Token 消耗热力图数据、模型调用占比、平均延迟与 Mock 触发率。
- **`GET /api/dev/requests?limit=50`**：返回最近原始 API 请求记录（来自于 `request_logs` 表，严格脱敏，不含任何 API Key、密码或 JWT 原文）。
