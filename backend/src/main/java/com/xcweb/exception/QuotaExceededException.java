package com.xcweb.exception;

public class QuotaExceededException extends RuntimeException {

    private final String role;
    private final int used;
    private final int limit;
    private final String resetAt;

    public QuotaExceededException(String role, int used, int limit, String resetAt, String message) {
        super(message);
        this.role = role;
        this.used = used;
        this.limit = limit;
        this.resetAt = resetAt;
    }

    public String getRole() {
        return role;
    }

    public int getUsed() {
        return used;
    }

    public int getLimit() {
        return limit;
    }

    public String getResetAt() {
        return resetAt;
    }
}
