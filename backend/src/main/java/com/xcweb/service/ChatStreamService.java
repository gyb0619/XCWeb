package com.xcweb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcweb.config.XcWebProperties;
import com.xcweb.dto.ChatCompletionRequest;
import com.xcweb.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final XcWebProperties properties;
    private final ModelService modelService;
    private final QuotaService quotaService;
    private final WebClient webClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, AtomicInteger> providerFailures = new ConcurrentHashMap<>();

    public ChatStreamService(XcWebProperties properties,
                             ModelService modelService,
                             QuotaService quotaService,
                             WebClient webClient,
                             JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.modelService = modelService;
        this.quotaService = quotaService;
        this.webClient = webClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public SseEmitter streamChatCompletion(AuthPrincipal principal,
                                           String clientIp,
                                           ChatCompletionRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        virtualExecutor.submit(() -> executeStream(emitter, principal, clientIp, request));
        return emitter;
    }

    private void executeStream(SseEmitter emitter,
                               AuthPrincipal principal,
                               String clientIp,
                               ChatCompletionRequest request) {
        long startTime = System.currentTimeMillis();
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String requestedModelId = (request.getModel() != null && !request.getModel().isBlank())
                ? request.getModel().trim()
                : modelService.getDefaultModelId();

        XcWebProperties.PresetModel presetModel = modelService.findPresetModel(requestedModelId);
        if (presetModel == null) {
            presetModel = modelService.findPresetModel(modelService.getDefaultModelId());
        }
        String modelId = presetModel != null ? presetModel.getId() : "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B";
        String provider = presetModel != null ? presetModel.getProvider() : "SiliconFlow";
        boolean isExplicitMock = "mock-cognitive-core".equalsIgnoreCase(modelId)
                || (presetModel != null && presetModel.isMock());

        boolean keyConfigured = modelService.isProviderKeyConfigured(provider);
        int failures = providerFailures.computeIfAbsent(provider, k -> new AtomicInteger(0)).get();
        int maxFailures = properties.getLlm().getMaxConsecutiveFailures();

        boolean useMock = isExplicitMock || !keyConfigured || failures >= maxFailures;
        String fallbackReason = null;
        if (isExplicitMock) {
            fallbackReason = "MOCK_MODEL_SELECTED";
        } else if (!keyConfigured) {
            fallbackReason = "NO_API_KEY";
        } else if (failures >= maxFailures) {
            fallbackReason = "UPSTREAM_CIRCUIT_OPEN";
        }

        int estimatedPromptTokens = Math.max(8, quotaService.estimateContextTokens(request));

        if (!useMock) {
            try {
                boolean upstreamSuccess = streamFromUpstream(
                        emitter, principal, clientIp, request, requestId, modelId, provider, estimatedPromptTokens, startTime
                );
                if (upstreamSuccess) {
                    providerFailures.get(provider).set(0);
                    return;
                }
            } catch (Exception ex) {
                int currentFailures = providerFailures.computeIfAbsent(provider, k -> new AtomicInteger(0)).incrementAndGet();
                log.warn("Upstream call failed for model={} provider={} (consecutiveFailures={}): {}",
                        modelId, provider, currentFailures, ex.getMessage());
                fallbackReason = "UPSTREAM_ERROR_FALLBACK";
            }
        }

        // Seamless fallback to Built-in Cognitive Mock Core
        try {
            streamFromBuiltInMock(
                    emitter, principal, clientIp, request, requestId, modelId, provider,
                    fallbackReason != null ? fallbackReason : "MOCK_FALLBACK",
                    estimatedPromptTokens, startTime
            );
        } catch (Exception mockEx) {
            log.error("Built-in Mock stream failed unexpectedly", mockEx);
            quotaService.rollbackQuotaCall(principal, clientIp);
            sendSseEvent(emitter, "error", Map.of(
                    "code", "STREAM_FAILED",
                    "message", "上游服务与本地兜底引擎均中断，本次不扣除对话配额"
            ));
            emitter.complete();
        }
    }

    private boolean streamFromUpstream(SseEmitter emitter,
                                       AuthPrincipal principal,
                                       String clientIp,
                                       ChatCompletionRequest request,
                                       String requestId,
                                       String modelId,
                                       String provider,
                                       int estimatedPromptTokens,
                                       long startTime) {
        XcWebProperties.ProviderConfig providerCfg = modelService.getProviderConfig(provider);
        if (providerCfg == null || providerCfg.getApiKey() == null || providerCfg.getApiKey().isBlank()) {
            return false;
        }

        List<Map<String, String>> upstreamMessages = buildUpstreamMessages(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelId);
        payload.put("messages", upstreamMessages);
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));
        if (request.getTemperature() != null) {
            payload.put("temperature", request.getTemperature());
        }

        AtomicBoolean metaSent = new AtomicBoolean(false);
        AtomicBoolean insideThinkTag = new AtomicBoolean(false);
        AtomicInteger upstreamPromptTokens = new AtomicInteger(0);
        AtomicInteger upstreamCompletionTokens = new AtomicInteger(0);
        AtomicInteger upstreamTotalTokens = new AtomicInteger(0);
        StringBuilder completionAccumulator = new StringBuilder();

        List<String> rawLines = webClient.post()
                .uri(providerCfg.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + providerCfg.getApiKey().trim())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofMillis(Math.max(2000, properties.getLlm().getFirstTokenTimeoutMs())))
                .collectList()
                .block();

        if (rawLines == null || rawLines.isEmpty()) {
            return false;
        }

        for (String rawChunk : rawLines) {
            String[] lines = rawChunk.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring(5).trim();
                }
                if ("[DONE]".equals(trimmed)) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(trimmed);
                    if (!metaSent.getAndSet(true)) {
                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("requestId", requestId);
                        meta.put("model", modelId);
                        meta.put("provider", provider);
                        meta.put("mock", false);
                        sendSseEvent(emitter, "meta", meta);
                    }

                    JsonNode choices = node.get("choices");
                    if (choices != null && choices.isArray() && !choices.isEmpty()) {
                        JsonNode delta = choices.get(0).get("delta");
                        if (delta != null) {
                            JsonNode reasoningNode = delta.get("reasoning_content");
                            if (reasoningNode != null && !reasoningNode.isNull() && !reasoningNode.asText().isEmpty()) {
                                String rText = reasoningNode.asText();
                                completionAccumulator.append(rText);
                                sendSseEvent(emitter, "thinking", Map.of("delta", rText));
                            }

                            JsonNode contentNode = delta.get("content");
                            if (contentNode != null && !contentNode.isNull() && !contentNode.asText().isEmpty()) {
                                String cText = contentNode.asText();
                                completionAccumulator.append(cText);
                                emitNormalizedContentOrThinking(emitter, cText, insideThinkTag);
                            }
                        }
                    }

                    JsonNode usageNode = node.get("usage");
                    if (usageNode != null && !usageNode.isNull()) {
                        if (usageNode.hasNonNull("prompt_tokens")) {
                            upstreamPromptTokens.set(usageNode.get("prompt_tokens").asInt());
                        }
                        if (usageNode.hasNonNull("completion_tokens")) {
                            upstreamCompletionTokens.set(usageNode.get("completion_tokens").asInt());
                        }
                        if (usageNode.hasNonNull("total_tokens")) {
                            upstreamTotalTokens.set(usageNode.get("total_tokens").asInt());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (!metaSent.get()) {
            return false;
        }

        boolean estimatedUsage = false;
        int promptTokens = upstreamPromptTokens.get();
        int completionTokens = upstreamCompletionTokens.get();
        int totalTokens = upstreamTotalTokens.get();

        if (totalTokens <= 0) {
            estimatedUsage = true;
            promptTokens = estimatedPromptTokens;
            completionTokens = Math.max(12, (int) Math.ceil(quotaService.estimateStringTokens(completionAccumulator.toString())));
            totalTokens = promptTokens + completionTokens;
        }

        long latencyMs = Math.max(10L, System.currentTimeMillis() - startTime);

        Map<String, Object> usageEvent = new LinkedHashMap<>();
        usageEvent.put("promptTokens", promptTokens);
        usageEvent.put("completionTokens", completionTokens);
        usageEvent.put("totalTokens", totalTokens);
        usageEvent.put("estimated", estimatedUsage);
        usageEvent.put("mock", false);
        sendSseEvent(emitter, "usage", usageEvent);

        quotaService.recordTokenUsage(principal, clientIp, totalTokens, false);
        recordRequestLog(principal.quotaIdentifier(), modelId, false, promptTokens, completionTokens, latencyMs, "200_OK");

        sendSseEvent(emitter, "done", Map.of(
                "finishReason", "stop",
                "latencyMs", latencyMs
        ));
        emitter.complete();
        return true;
    }

    private void emitNormalizedContentOrThinking(SseEmitter emitter, String text, AtomicBoolean insideThinkTag) throws Exception {
        String remaining = text;
        while (!remaining.isEmpty()) {
            if (insideThinkTag.get()) {
                int closeIdx = remaining.indexOf("</think>");
                if (closeIdx >= 0) {
                    String thinkPart = remaining.substring(0, closeIdx);
                    if (!thinkPart.isEmpty()) {
                        sendSseEvent(emitter, "thinking", Map.of("delta", thinkPart));
                    }
                    insideThinkTag.set(false);
                    remaining = remaining.substring(closeIdx + "</think>".length());
                } else {
                    sendSseEvent(emitter, "thinking", Map.of("delta", remaining));
                    remaining = "";
                }
            } else {
                int openIdx = remaining.indexOf("<think>");
                if (openIdx >= 0) {
                    String before = remaining.substring(0, openIdx);
                    if (!before.isEmpty()) {
                        sendSseEvent(emitter, "content", Map.of("delta", before));
                    }
                    insideThinkTag.set(true);
                    remaining = remaining.substring(openIdx + "<think>".length());
                } else {
                    sendSseEvent(emitter, "content", Map.of("delta", remaining));
                    remaining = "";
                }
            }
        }
    }

    private void streamFromBuiltInMock(SseEmitter emitter,
                                       AuthPrincipal principal,
                                       String clientIp,
                                       ChatCompletionRequest request,
                                       String requestId,
                                       String modelId,
                                       String provider,
                                       String fallbackReason,
                                       int estimatedPromptTokens,
                                       long startTime) throws Exception {
        String lastUserPrompt = extractLastUserMessage(request);
        List<ChatCompletionRequest.RagChunkDto> ragChunks = request.getRagContext() != null
                ? request.getRagContext()
                : List.of();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", requestId);
        meta.put("model", modelId);
        meta.put("provider", provider);
        meta.put("mock", true);
        meta.put("fallbackReason", fallbackReason);
        sendSseEvent(emitter, "meta", meta);

        boolean triggerAgent = Boolean.TRUE.equals(request.getEnableAgent()) || shouldTriggerAgentByPrompt(lastUserPrompt);
        if (triggerAgent) {
            emitMockAgentSteps(emitter, lastUserPrompt, ragChunks);
        }

        boolean enableThinking = request.getEnableThinking() == null || request.getEnableThinking();
        if (enableThinking) {
            List<String> thinkingChunks = buildMockThinkingChunks(lastUserPrompt, modelId, ragChunks);
            for (String chunk : thinkingChunks) {
                sendSseEvent(emitter, "thinking", Map.of("delta", chunk));
                Thread.sleep(15L);
            }
        }

        List<String> contentChunks = buildMockContentChunks(lastUserPrompt, ragChunks);
        StringBuilder fullContent = new StringBuilder();
        for (String chunk : contentChunks) {
            fullContent.append(chunk);
            sendSseEvent(emitter, "content", Map.of("delta", chunk));
            Thread.sleep(12L);
        }

        int completionTokens = Math.max(24, (int) Math.ceil(quotaService.estimateStringTokens(fullContent.toString())));
        int totalTokens = estimatedPromptTokens + completionTokens;
        long latencyMs = Math.max(45L, System.currentTimeMillis() - startTime);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptTokens", estimatedPromptTokens);
        usage.put("completionTokens", completionTokens);
        usage.put("totalTokens", totalTokens);
        usage.put("estimated", true);
        usage.put("mock", true);
        sendSseEvent(emitter, "usage", usage);

        // Mock responses count as a call (already incremented) but NOT towards daily token statistics
        quotaService.recordTokenUsage(principal, clientIp, totalTokens, true);
        recordRequestLog(principal.quotaIdentifier(), modelId, true, estimatedPromptTokens, completionTokens, latencyMs, "200_MOCK");

        sendSseEvent(emitter, "done", Map.of(
                "finishReason", "stop",
                "latencyMs", latencyMs
        ));
        emitter.complete();
    }

    private void emitMockAgentSteps(SseEmitter emitter,
                                    String userPrompt,
                                    List<ChatCompletionRequest.RagChunkDto> ragChunks) throws Exception {
        String lower = userPrompt.toLowerCase();
        String selectedTool = "web_search";
        String toolTitle = "执行白名单检索工具 (web_search)";
        String toolOutput = "命中 4 篇 2026 AI-Native 架构基准报告，提取关键吞吐量、延迟与多维对比指标。";

        if (lower.contains("天气") || lower.contains("weather") || lower.contains("气温")) {
            selectedTool = "weather_query";
            toolTitle = "调用气象感知白名单工具 (weather_query)";
            toolOutput = "{\"city\":\"Shanghai\",\"temp\":\"22°C\",\"condition\":\"晴朗微风\",\"humidity\":\"48%\",\"aqi\":32}";
        } else if (lower.contains("代码") || lower.contains("算法") || lower.contains("code") || lower.contains("sql")) {
            selectedTool = "code_sandbox";
            toolTitle = "执行安全沙箱静态分析 (code_sandbox)";
            toolOutput = "AST 语法树校验通过，时间复杂度 O(N log N)，无阻塞调用风险。";
        } else if (lower.contains("数据") || lower.contains("表格") || lower.contains("趋势") || lower.contains("对比")) {
            selectedTool = "data_analyzer";
            toolTitle = "调用结构化数据聚合引擎 (data_analyzer)";
            toolOutput = "生成多维指标矩阵：FCP 0.68s、稳态帧率 60 FPS、SQLite WAL 吞吐量 4,200 QPS。";
        }

        String snippet = userPrompt.length() > 60 ? userPrompt.substring(0, 60) + "..." : userPrompt;

        sendSseEvent(emitter, "agent_step", Map.of(
                "stepId", "step_1",
                "phase", "plan",
                "title", "任务规划与意图解析",
                "toolName", "planner",
                "status", "completed",
                "input", "用户提问：" + snippet,
                "output", "拆解为：1. 语义意图识别与上下文约束检查 -> 2. 调用白名单工具 " + selectedTool + " -> 3. 结构化数据回传与合成校验",
                "durationMs", 68
        ));
        Thread.sleep(15L);

        sendSseEvent(emitter, "agent_step", Map.of(
                "stepId", "step_2",
                "phase", "tool",
                "title", toolTitle,
                "toolName", selectedTool,
                "status", "completed",
                "input", "{\"query\":\"" + snippet.replace("\"", "'") + "\",\"topK\":3}",
                "output", toolOutput,
                "durationMs", 142
        ));
        Thread.sleep(15L);

        String obsOutput = ragChunks.isEmpty()
                ? "工具返回结构化指标校验完毕，置信度 0.96，准备注入推理上下文。"
                : "结合端侧 RAG 知识库 " + ragChunks.size() + " 个高相关度分块（Top Score: "
                  + (ragChunks.get(0).getScore() != null ? ragChunks.get(0).getScore() : 0.92) + "）完成交叉验证。";

        sendSseEvent(emitter, "agent_step", Map.of(
                "stepId", "step_3",
                "phase", "observation",
                "title", "数据回传与多源特征对齐",
                "toolName", selectedTool,
                "status", "completed",
                "input", "工具观测值 + 上下文窗口特征向量",
                "output", obsOutput,
                "durationMs", 95
        ));
        Thread.sleep(15L);

        sendSseEvent(emitter, "agent_step", Map.of(
                "stepId", "step_4",
                "phase", "synthesis",
                "title", "总结分析与生成式 UI 视图编排",
                "toolName", "synthesizer",
                "status", "completed",
                "input", "聚合推理结论与结构化表格数据",
                "output", "已生成包含 Markdown 结构化对比矩阵与可溯源引用的最终响应。",
                "durationMs", 74
        ));
        Thread.sleep(15L);
    }

    private List<String> buildMockThinkingChunks(String prompt,
                                                  String modelId,
                                                  List<ChatCompletionRequest.RagChunkDto> ragChunks) {
        List<String> chunks = new ArrayList<>();
        String snippet = prompt.length() > 48 ? prompt.substring(0, 48) + "..." : prompt;
        chunks.add("正在解析用户核心意图：「" + snippet + "」...\n");
        chunks.add("1. **上下文与约束评估**：当前运行于 `" + modelId + "` 认知引擎，已通过四级角色配额与上下文窗口预检。\n");
        if (!ragChunks.isEmpty()) {
            String docName = ragChunks.get(0).getDocName() != null ? ragChunks.get(0).getDocName() : "本地知识文档";
            chunks.add("2. **端侧 RAG 知识融合**：检测到客户端检索注入的 " + ragChunks.size()
                    + " 个知识切片（来源：`" + docName + "`），将在正文中建立精准锚点引用标记。\n");
        } else {
            chunks.add("2. **领域知识激活**：调取系统架构演进、流式 SSE 协议以及生成式交互 UI 指标基准库。\n");
        }
        chunks.add("3. **结构化输出编排**：采用「核心结论 -> 量化对比表格 -> 工程落地建议」三段式结构，便于前端动态渲染交互式表格与科技蓝图表。");
        return chunks;
    }

    private List<String> buildMockContentChunks(String prompt,
                                                 List<ChatCompletionRequest.RagChunkDto> ragChunks) {
        List<String> chunks = new ArrayList<>();
        chunks.add("### 深度认知分析报告\n\n");
        chunks.add("针对您提出的 **「" + (prompt.length() > 40 ? prompt.substring(0, 40) + "..." : prompt) + "」**，XCWeb 智能体核心给出如下结构化洞察：\n\n");

        if (!ragChunks.isEmpty()) {
            ChatCompletionRequest.RagChunkDto first = ragChunks.get(0);
            String firstDoc = first.getDocName() != null ? first.getDocName() : "知识库文档";
            String firstExcerpt = first.getContent() != null && first.getContent().length() > 60
                    ? first.getContent().substring(0, 60) + "..."
                    : (first.getContent() != null ? first.getContent() : "核心段落内容");
            chunks.add("> **端侧知识库增强检索结论**：根据上传文档 `" + firstDoc + "` 的语义分块匹配（*“" + firstExcerpt + "”*），系统已完成端侧向量对齐【引用来源 [1]】");
            if (ragChunks.size() >= 2) {
                chunks.add("，并结合次级关联切片完成了跨段落一致性印证【引用来源 [2]】。\n\n");
            } else {
                chunks.add("。\n\n");
            }
        }

        chunks.add("#### 1. 核心架构与性能量化对比\n\n");
        chunks.add("| 评估维度 | 传统对话套壳方案 | XCWeb AI-Native 方案 | 核心指标表现 |\n");
        chunks.add("| :--- | :--- | :--- | :--- |\n");
        chunks.add("| 首屏渲染耗时 (FCP) | 2,400 ms | 680 ms | 提升 71.6% |\n");
        chunks.add("| 动效稳态帧率 (FPS) | 38 FPS | 60 FPS | 零丢帧丝滑过渡 |\n");
        chunks.add("| 角色鉴权与防刷粒度 | 单一 Cookie 校验 | 令牌 + 指纹 + IP 三重防刷 | 100% 拦截越权请求 |\n");
        chunks.add("| 极端网络可用性 | 直接报错中断 | 自动无缝切换 Cognitive Mock | 100% 离线可演示 |\n\n");

        chunks.add("#### 2. 关键工程演进要点\n\n");
        chunks.add("1. **思维链与正文解耦**：后端自动将 `<think>` 标签与 `reasoning_content` 规范化为独立 `thinking` 事件流，前端驱动可折叠思考抽屉与 `600ms slideUp` 正文动效。\n");
        chunks.add("2. **四级角色与自然日配额**：基于 `Asia/Shanghai` 时区自然日原子计数，游客享每日 10 次 / 4K 上下文体验，注册用户升级至 50 次 / 16K 上下文并开启 SQLite 云端增量同步。\n");
        chunks.add("3. **生成式 UI 与智能体协同**：支持 `plan -> tool -> observation -> synthesis` 四阶段白名单工具追踪，并将 Markdown 表格自动升级为可筛选数据视图与科技蓝可视化图表。\n");

        return chunks;
    }

    private boolean shouldTriggerAgentByPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String lower = prompt.toLowerCase();
        return lower.contains("天气") || lower.contains("检索") || lower.contains("搜索")
                || lower.contains("对比") || lower.contains("分析") || lower.contains("趋势")
                || lower.contains("表格") || lower.contains("数据") || lower.contains("架构")
                || lower.contains("agent") || lower.contains("tool");
    }

    private String extractLastUserMessage(ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "请介绍 XCWeb 平台架构与核心亮点";
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.ChatMessageDto m = request.getMessages().get(i);
            if (m != null && "user".equalsIgnoreCase(m.getRole()) && m.getContent() != null) {
                return m.getContent();
            }
        }
        ChatCompletionRequest.ChatMessageDto last = request.getMessages().get(request.getMessages().size() - 1);
        return last != null && last.getContent() != null ? last.getContent() : "";
    }

    private List<Map<String, String>> buildUpstreamMessages(ChatCompletionRequest request) {
        List<Map<String, String>> result = new ArrayList<>();
        if (request.getRagContext() != null && !request.getRagContext().isEmpty()) {
            StringBuilder ragBuilder = new StringBuilder("以下是端侧 RAG 检索到的参考知识片段，请在回答时使用【引用来源 [序号]】进行标注：\n");
            for (int i = 0; i < request.getRagContext().size(); i++) {
                ChatCompletionRequest.RagChunkDto chunk = request.getRagContext().get(i);
                int idx = chunk.getIndex() != null ? chunk.getIndex() : (i + 1);
                ragBuilder.append("[").append(idx).append("] (")
                        .append(chunk.getDocName() != null ? chunk.getDocName() : "doc")
                        .append("): ").append(chunk.getContent()).append("\n");
            }
            result.add(Map.of("role", "system", "content", ragBuilder.toString()));
        }
        if (request.getMessages() != null) {
            for (ChatCompletionRequest.ChatMessageDto msg : request.getMessages()) {
                if (msg != null && msg.getContent() != null) {
                    result.add(Map.of(
                            "role", msg.getRole() != null ? msg.getRole() : "user",
                            "content", msg.getContent()
                    ));
                }
            }
        }
        return result;
    }

    private void recordRequestLog(String identifier,
                                  String model,
                                  boolean isMock,
                                  int promptTokens,
                                  int completionTokens,
                                  long latencyMs,
                                  String status) {
        try {
            jdbcTemplate.update("""
                INSERT INTO request_logs (identifier, model, is_mock, prompt_tokens, completion_tokens, latency_ms, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, identifier, model, isMock ? 1 : 0, promptTokens, completionTokens, latencyMs, status, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("Failed to write request_log: {}", e.getMessage());
        }
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(json, MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            throw new RuntimeException("SSE send failed for event " + eventName, e);
        }
    }
}
