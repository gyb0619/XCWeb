package com.xcweb.controller;

import com.xcweb.dto.ChatCompletionRequest;
import com.xcweb.exception.ApiException;
import com.xcweb.security.AuthPrincipal;
import com.xcweb.service.ChatStreamService;
import com.xcweb.service.QuotaService;
import com.xcweb.util.ClientIpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final QuotaService quotaService;
    private final ChatStreamService chatStreamService;

    public ChatController(QuotaService quotaService, ChatStreamService chatStreamService) {
        this.quotaService = quotaService;
        this.chatStreamService = chatStreamService;
    }

    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completions(@AuthenticationPrincipal AuthPrincipal principal,
                                  @RequestBody ChatCompletionRequest request,
                                  HttpServletRequest httpRequest,
                                  HttpServletResponse httpResponse) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未提供有效身份令牌");
        }
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGES", "对话消息列表不能为空");
        }

        // 1. Validate single-turn context token limit (413 Payload Too Large if exceeded; does NOT consume daily quota)
        quotaService.checkContextLimit(principal, request);

        // 2. Check & atomically increment daily call count across dimensions (429 Too Many Requests if exceeded)
        String clientIp = ClientIpUtils.getClientIp(httpRequest);
        quotaService.checkAndAcquireQuota(principal, clientIp);

        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("X-Accel-Buffering", "no");
        httpResponse.setCharacterEncoding("UTF-8");

        return chatStreamService.streamChatCompletion(principal, clientIp, request);
    }
}
