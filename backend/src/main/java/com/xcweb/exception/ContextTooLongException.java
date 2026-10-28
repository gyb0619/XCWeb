package com.xcweb.exception;

public class ContextTooLongException extends RuntimeException {

    private final String role;
    private final int estimatedTokens;
    private final int limit;

    public ContextTooLongException(String role, int estimatedTokens, int limit, String message) {
        super(message);
        this.role = role;
        this.estimatedTokens = estimatedTokens;
        this.limit = limit;
    }

    public String getRole() {
        return role;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public int getLimit() {
        return limit;
    }
}
