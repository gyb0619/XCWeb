# XCWeb (Xtreme Cognitive Web) —— 提示词工程与开发指令手册 (PROMPTS)

> 本文档收录了 **XCWeb** 项目所需的两类核心提示词：
> 1. **大模型系统级提示词 (AI System Prompts)**：供 XCWeb 运行时注入大模型，实现极客问答、深度思考及 Agent 工具规划。
> 2. **AI 辅助工程化研发指令 (Coding Agent Prompts)**：供参赛开发者在 Cursor、Antigravity、Claude Code 等 AI 编程工具中直接分步执行，高保真生成 Vue 3 前端与 Spring Boot 后端工程代码。

---

# 第一部分：XCWeb 运行时 AI 系统提示词 (System Prompts)

## 1.1 XCWeb 核心极客助手系统提示词 (Default System Prompt)

> **使用场景**：未开启深度思考时的默认对话 Prompt，确保回答兼具极客严谨性、优雅排版与现代 Web 交互亲和力。

```markdown
You are XCWeb Assistant, a modern, highly capable, and intelligent AI companion designed for the "Xtreme Cognitive Web" platform.

### Core Guidelines:
1. **Communication Style**:
   - Respond in professional, clean, and concise Chinese (or the language chosen by the user).
   - Maintain a friendly, geeky, and technically rigorous tone.
   - When explaining complex concepts, use structured Markdown with bullet points, comparison tables, or code snippets.

2. **Formatting & Code Presentation**:
   - Always specify the programming language tag for code fences (e.g., ```typescript, ```java, ```sql).
   - Write clean, production-ready, and well-commented code. Follow modern best practices (e.g., Vue 3 Composition API, Spring Boot 3 idioms).
   - For mathematical equations, use LaTeX syntax inline with `$ ... $` or display blocks with `$$ ... $$`.

3. **Multi-Modal & File Understanding**:
   - When context includes uploaded source code, logs, or documents, reference specific line numbers or sections accurately.
   - If asked to design architectures, provide clear Mermaid diagrams using ```mermaid syntax.

4. **Safety & Objectivity**:
   - Respect user privacy. Never hallucinate private credentials.
   - When uncertain, state limitations clearly rather than fabricating facts.
```

---

## 1.2 DeepSeek 深度思考模型引导提示词 (Reasoning Prompt)

> **使用场景**：当用户开启“深度思考”或调用 DeepSeek-R1 / Qwen-Reasoning 蒸馏模型时注入。

```markdown
You are XCWeb Cognitive Engine. You are in deep reasoning mode.

### Reasoning Instructions:
1. **Thinking Process**:
   - Wrap your complete, unstructured internal reasoning, hypothesis testing, edge-case evaluation, and logical deductions inside `<think>` and `</think>` tags.
   - In your thinking section, explore multiple perspectives, double-check algorithmic edge cases, identify potential pitfalls, and formulate a step-by-step resolution path.
   - Write your thoughts naturally and thoroughly without censorship of the intermediate exploratory steps.

2. **Final Answer Delivery**:
   - Immediately after the `</think>` tag, provide the structured, definitive, and polished answer directly to the user.
   - Do NOT repeat the thinking steps verbatim in the final answer; deliver clean conclusions, executable code, and actionable insights.
```

---

## 1.3 AI Agent 工具规划与 Function Calling 提示词 (Agent Workflow Prompt)

> **使用场景**：竞赛加分亮点——当用户需要实时联网检索、数据图表生成或代码沙箱执行时使用。

```markdown
You are XCWeb Agent Core, an autonomous cognitive entity capable of using specialized tools to accomplish complex web and data tasks.

### Available Tools:
1. `web_search(query: string)`: Searches the live web for recent information, documentation, and news.
2. `code_sandbox(language: string, code: string)`: Executes sandboxed Python/JS code to verify logic or perform mathematical calculations.
3. `render_chart(type: string, title: string, data: object)`: Renders an interactive ECharts visualization in the user's viewport.

### Response Protocol:
When a user task requires external data, calculation, or visualization, follow the ReAct (Reason-Action-Observation) loop:
1. Output your thought: `Thought: <Your reasoning on why a tool is needed>`
2. Call the tool in a structured JSON block:
```json
{
  "tool": "web_search",
  "arguments": {
    "query": "Vue 3.5 new features and release notes"
  }
}
```
3. Await observation, then synthesize the final answer.
```

---

# 第二部分：AI 辅助全栈工程落地指令 (Coding Prompts)

开发者可将以下指令按顺序输入给 AI 辅助编程工具（如 Antigravity / Cursor），一步步构建出完整的前后端工程。

---

## 🚀 指令 1：前端工程脚手架与“科技深邃蓝”设计系统搭建

```markdown
请在当前项目目录创建 XCWeb 前端工程，技术栈要求：Vite 6 + Vue 3 (Composition API / <script setup lang="ts">) + TypeScript + Tailwind CSS + Pinia + Lucide Icons。

### 详细要求：
1. **设计系统 (Design System)**：
   - 配置 Tailwind CSS，基于 CSS 变量实现三套全局主题（浅色 Light、深色 Dark、无痕 Incognito）。
   - 主色调采用“科技深邃蓝 (Electric Cyber Blue)”：
     - Primary: #2563EB (明亮模式) / #3B82F6 (深色模式)
     - Glow Accent: #06B6D4 (霓虹青)
     - Dark Background: #0B0F19 (宇宙暗夜蓝黑) / Card: #111827
     - Incognito Accent: #6366F1 (星空靛蓝)
   - 包含毛玻璃特效类 (.glass-panel, .glass-modal)，支持 backdrop-blur-md 与科技微光内描边。

2. **状态管理 (Pinia)**：
   - 创建 `useAuthStore`（维护 token、userInfo、四级角色 'guest'|'user'|'admin'|'dev'）；
   - 创建 `useChatStore`（维护当前对话、消息列表、流式状态 isStreaming、版本索引）；
   - 创建 `useConfigStore`（维护主题 theme: 'light'|'dark'|'auto'、思考深度、模型列表、无痕开关）。

3. **基础组件**：
   - 封装可复用的科技蓝拟物按钮（`.btn-cyber-primary`）、带平滑微动效的 Switch 开关、自定义带阻尼拉伸的 CustomSelect 下拉组件。
```

---

## 🚀 指令 2：Spring Boot 3 独立后端基础框架搭建与 SQLite 集成

```markdown
请在 `backend` 目录下初始化一个完全独立的 Spring Boot 3 单体后端项目（Java 17 或 21），采用 Maven 构建，不依赖任何第三方未经验证的外部微服务。

### 详细要求：
1. **依赖选型**：
   - `spring-boot-starter-web` (Web MVC)
   - `spring-boot-starter-security` (基础安全与 JWT 过滤)
   - `sqlite-jdbc` 与 `mybatis-plus-boot-starter` (轻量无须安装的本地 SQLite 数据库支持)
   - `spring-boot-starter-webflux` (用于底层 WebClient 发起流式 SSE 请求向大模型上游代理)
   - `jjwt-api` / `jjwt-impl` / `jjwt-jackson` (JWT 签名与验证)
   - `lombok` (简化实体代码)

2. **SQLite 自动化初始化**：
   - 配置文件 `application.yml` 配置 SQLite 本地文件数据源 `jdbc:sqlite:xcweb.db`；
   - 编写 `DatabaseInitializer`，在 Spring Boot 启动时自动检查并创建 4 张核心表：
     - `users` (id, username, email, password_hash, role, created_at)
     - `quota_logs` (id, identifier, role, date_str, call_count, token_count, updated_at)
     - `conversations` (id, user_id, title, token, model, created_at, updated_at)
     - `messages` (id, conversation_id, role, content, thinking_content, attachments_json, versions_json, created_at)
   - 预置默认测试账号：
     - 普通用户：`user` / `User123456` (role: user)
     - 管理员：`admin` / `Admin123456` (role: admin)
     - 开发者：`dev` / `Dev123456` (role: dev)
```

---

## 🚀 指令 3：独立 JWT 鉴权与四级权限过滤器实现

```markdown
请在 Spring Boot 后端中实现完整的四级权限鉴权与 Token 管理机制。

### 详细要求：
1. **鉴权端点 (`AuthController`)**：
   - `POST /api/auth/register`：用户注册，BCrypt 加密密码，分配默认角色 `user`。
   - `POST /api/auth/login`：用户登录验证，成功后生成 7 天有效的 JWT，Payload 携带 `userId`, `username`, `role`。
   - `GET /api/auth/me`：通过请求头 `Authorization: Bearer <token>` 解析当前用户信息。

2. **四级角色认定中间件 (`JwtAuthenticationFilter`)**：
   - 若请求头带有有效 JWT：解析出真实用户身份及角色（`user` / `admin` / `dev`）；
   - 若请求头未带 JWT：读取客户端请求头 `X-Guest-Fingerprint`（由前端生成的浏览器唯一指纹 UUID）与客户端 IP，自动识别并赋予 **游客身份 (`guest`)**。
   - 确保游客模式无需任何注册或登录凭证即可顺畅访问基础会话 API。
```

---

## 🚀 指令 4：免费大模型聚合代理与滑动窗口 Token 配额拦截器

```markdown
请在 Spring Boot 后端实现核心流式对话代理与配额限制拦截器。

### 详细要求：
1. **配额与调用限制拦截器 (`QuotaInterceptor`)**：
   - 对 `/api/chat/completions` 请求进行拦截：
     - **游客 (Guest)**：限制 **10 次/日**，单次最大 Token 4K。
     - **普通用户 (User)**：限制 **50 次/日**，单次最大 Token 16K。
     - **管理员 (Admin) & 开发者 (Dev)**：完全无限制放行。
   - 校验今日（按自然日 `YYYY-MM-DD`）在 `quota_logs` 表中的使用频次。若超额，返回 HTTP 429 状态码及优雅的 JSON 提示信息。

2. **流式聚合代理服务 (`LLMChatService`)**：
   - 对接第三方 OpenAI 兼容端点（如硅基流动 SiliconFlow 免费模型或智谱 `glm-4-flash`）；
   - 使用 WebClient 发起流式请求，向前端输出 `text/event-stream` (SSE) 分块数据；
   - 在流式传输完成回调中，统计本次回答的估算 Token 消耗量，异步累加写入当前用户的 `quota_logs` 表；
   - **离线兜底机制**：若外部网络不可用或未配置 API Key，自动回退到内置的 Mock 响应生成器，模拟 DeepSeek `<think>` 思考过程和打字机效果输出，保证评委离线评审时系统稳定可用。
```

---

## 🚀 指令 5：全新极客科技风登录/注册与一键游客体验卡片

```markdown
请在 Vue 3 前端项目中重构全新的登录与身份选择面板（组件：`AuthOverlay.vue`）。

### 详细要求：
1. **背景视觉**：
   - 移除原旧项目的卡通吉祥物；
   - 使用 HTML5 Canvas 绘制高品质的“神经突触粒子网格连线”动态背景（微粒缓动漂浮，光标靠近时产生连线流光）；
   - 叠加深邃科技蓝向宇宙黑过渡的渐变蒙层。

2. **卡片结构与交互**：
   - 居中悬浮毛玻璃拟态卡片（`backdrop-blur-xl bg-slate-900/80 border border-blue-500/20 shadow-2xl rounded-3xl`）；
   - 顶部呈现 XCWeb 极客光标 Logo 与“全国高校 AI Web 开发挑战赛参赛作品”标识；
   - 提供平滑阻尼滑块切换【登录】与【注册】表单；
   - 表单底部提供极具视觉分量的专属按钮：**【一键免登 · 以游客身份极速体验】**，点击后生成本地设备指纹，直接以丝滑淡出过渡进入主聊天工作台。
```

---

## 🚀 指令 6：核心流式对话渲染、思维链折叠与正文滑升组件

```markdown
请在 Vue 3 前端中实现核心消息气泡流（`MessageList.vue`）与单个消息渲染卡片（`MessageItem.vue`）。

### 详细要求：
1. **气泡排版与色彩**：
   - 采用去头像极简现代设计；
   - 用户提问气泡：采用深邃科技蓝背景（`bg-blue-600 dark:bg-blue-700 text-white rounded-2xl rounded-tr-sm`）；
   - AI 回复气泡：采用低对比度透亮暗灰蓝背景（`bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800/80`）。

2. **DeepSeek 思维链组件 (`ThinkingBlock.vue`)**：
   - 流式解析返回文本中的 `<think>` 与 `</think>` 标签；
   - 思考中状态：科技蓝旋转菊花图标 + 毫秒级思考计时器；
   - 思考结束瞬时：菊花模糊淡出，绿色对勾微光放大浮入；
   - 抽屉采用 CSS Grid 0fr -> 1fr 实现无断裂平滑折叠/展开；
   - **正文滑升**：思考块闭合完成后，正文区域自动执行 `animation: slideUp 600ms cubic-bezier(0.25, 1, 0.5, 1)`（从下方 20px 向上浮现），消除突兀感。

3. **Markdown 与代码高亮**：
   - 集成 `marked` 与 `highlight.js`，支持代码块一键复制按钮、语法高亮与行号显示；
   - 集成数学公式 LaTeX 解析渲染。
```

---

## 🚀 指令 7：复刻四大极致微动效 (对角线扫屏、无损重试飞入、视差翻页、动态编辑)

```markdown
请在 Vue 3 前端中完整复刻并升级原项目的四大高阶物理微动效：

### 详细要求：
1. **对角线一步式扫屏主题切换 (`useThemeSweep.ts`)**：
   - 在顶层插入 `<svg>` 与克隆层，点击切换主题卡片时，使用 SVG `polygon` 驱动 `clip-path` 沿 135° 对角线从左上角扫过全屏（500ms）；
   - 扫屏过程中克隆层保持静止，扫屏完毕瞬时无缝切换全局主题并重载消息，保证 60fps 丝滑无卡顿。

2. **无损历史重试与物理飞移动画 (`handleRetryMessage`)**：
   - 点击中间历史消息的“重试”按钮时，绝不截断或删除后续对话记录；
   - 原提问文字向左滑动 150px 偏置隐去（`.retry-exiting`），同时生成一个 fixed 定位的副本卡片；
   - 副本以 600ms 平滑贝塞尔曲线从原位置物理“飞入”到底部新追加的输入框位置；
   - 新生成的 AI 消息卡片自动初始化为两页版本（Page 1 存放被重试的原回答，Page 2 流式接收新回答）。

3. **大摆幅视差换页切换 (`VersionSwitcher.vue`)**：
   - 翻页采用“先出后入”时序：旧页文本以 150ms 向侧边滑出并淡化，新页内容以 150px 大摆幅带弹性阻尼滑入；
   - 翻页控制按钮栏就地微视差微调，页码数字在切换瞬间带有 `blur(3px)` 模糊消散过渡，杜绝整块 DOM 重绘跳闪。

4. **自适应平滑尺寸展开编辑 (`handleInlineEdit`)**：
   - 用户编辑提问时，JS 动态测量起始小气泡尺寸与目标全宽 textarea 尺寸；
   - 通过 CSS transition 驱动气泡高宽平滑舒展铺满；
   - 保存或取消时，按钮原位缩放淡出，动态测量目标文本尺寸，平滑折叠回小气泡。
```

---

## 🚀 指令 8：云端数据双向增量同步与设置持久化

```markdown
请在前端与 Spring Boot 后端中实现完整的双向增量同步机制（`sync.ts` & `SyncController.java`）。

### 详细要求：
1. **增量比对规则**：
   - 会话与设置均包含 `updatedAt` 毫秒级时间戳；
   - 登录成功后，前端触发并发同步：
     - 获取云端会话列表与本地比对，谁的时间戳最新则保留谁；
     - 自动补全云端存在但本地缺失的会话；
     - 上传本地存在但云端未录入的会话。

2. **自动同步钩子**：
   - 用户发起新对话、发送新消息或重命名会话时，防抖（Debounce 500ms）静默推送至 `POST /api/sync/conversations`；
   - 删除对话时静默推送 `DELETE /api/sync/conversations/{id}`；
   - 保存个性化系统设置时静默推送 `PUT /api/sync/settings`；
   - **游客模式与无痕模式保护**：在此两类状态下，严格禁用同步请求，仅在内存或本地运行。
```

---

## 🚀 指令 9：竞赛创新加分亮点——Agent 思考时间线与端侧轻量 RAG

```markdown
为了在全国高校 AI Web 开发挑战赛中冲刺“AI 技术深度 (35%)”和“创新性 (25%)”高分，请在前端实现两大杀手级亮点模块：

### 详细要求：
1. **Agent 思考决策时间轴组件 (`AgentTimeline.vue`)**：
   - 在 AI 回答顶部以可折叠时间轴展示智能体行动步骤：
     - 步骤 1：`🎯 意图拆解与任务规划`
     - 步骤 2：`🔍 调用工具 [web_search] 检索最新技术前沿`
     - 步骤 3：`⚡ 校验数据准确性并生成可视化图表`
   - 支持直观查看每个步骤的入参与返回结果，将传统黑盒等待变为透明专业的智能体交互。

2. **端侧轻量 RAG 知识检索增强 (`useLocalRAG.ts`)**：
   - 支持拖入 PDF/Markdown/代码附件，前端使用轻量文本分块算法（按段落与固定 Token 窗口切片）；
   - 用户提问时自动计算关键词相关度评分，提取 Top-3 最相关切片注入 Prompt 上下文；
   - 回答中自动生成带微光的【引用来源 [1] [2]】胶囊标签，悬停可浮动预览原始切片段落。
```

---

## 🚀 指令 10：开发者控制台 (Dev Console) 与仪表盘

```markdown
请为第四级权限“开发者 (Developer)”定制独享的开发与调试面板（组件：`DevConsoleDrawer.vue`）。

### 详细要求：
1. **入口控制**：仅当当前用户角色为 `dev` 时，顶栏右侧展示代码终端图标 `</>` 按钮。
2. **仪表盘能力**：
   - **Raw Request/Response 监视器**：查看上一次 AI 调用的完整 Payload、Headers 与 SSE 原始流分块数据；
   - **Token 消耗热力图**：展示今日与近 7 天的 Token 消耗趋势折线图；
   - **Prompt 沙箱与热重载**：支持在不刷新页面的情况下实时修改当前模型的 Temperature、Top_P 与 System Prompt 并快速测试生成效果。
```
