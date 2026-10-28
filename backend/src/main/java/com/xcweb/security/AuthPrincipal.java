package com.xcweb.security;

public record AuthPrincipal(
        Long userId,
        String username,
        String email,
        String guestId,
        String fingerprintHash,
        String role,
        long expiresAt
) {
    public boolean isGuest() {
        return "guest".equalsIgnoreCase(role);
    }

    public String quotaIdentifier() {
        if (isGuest()) {
            return "guest:" + guestId;
        }
        return "user:" + userId;
    }
}
