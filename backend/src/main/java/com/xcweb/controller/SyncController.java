package com.xcweb.controller;

import com.xcweb.dto.SyncDtos;
import com.xcweb.security.AuthPrincipal;
import com.xcweb.service.SyncService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/conversations")
    public Map<String, Object> getConversations(@AuthenticationPrincipal AuthPrincipal principal,
                                                @RequestParam(name = "since", defaultValue = "0") long since) {
        List<SyncDtos.ConversationSyncDto> list = syncService.getConversationsSince(principal.userId(), since);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversations", list);
        resp.put("serverTimestamp", System.currentTimeMillis());
        return resp;
    }

    @PostMapping("/conversations")
    public Map<String, Object> saveConversation(@AuthenticationPrincipal AuthPrincipal principal,
                                                @RequestBody SyncDtos.ConversationSyncDto body) {
        return syncService.upsertConversationWithArbitration(principal.userId(), body);
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(@AuthenticationPrincipal AuthPrincipal principal,
                                                  @PathVariable("id") String id) {
        syncService.deleteConversation(principal.userId(), id);
        return Map.of("status", "deleted", "id", id);
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings(@AuthenticationPrincipal AuthPrincipal principal) {
        return syncService.getUserSettings(principal.userId());
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@AuthenticationPrincipal AuthPrincipal principal,
                                            @RequestBody Map<String, Object> body) {
        return syncService.saveUserSettings(principal.userId(), body);
    }
}
