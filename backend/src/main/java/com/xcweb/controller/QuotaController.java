package com.xcweb.controller;

import com.xcweb.security.AuthPrincipal;
import com.xcweb.service.QuotaService;
import com.xcweb.util.ClientIpUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus(@AuthenticationPrincipal AuthPrincipal principal,
                                         HttpServletRequest request) {
        String clientIp = ClientIpUtils.getClientIp(request);
        return quotaService.getQuotaStatus(principal, clientIp);
    }
}
