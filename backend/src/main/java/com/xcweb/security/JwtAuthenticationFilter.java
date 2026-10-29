package com.xcweb.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(TokenService tokenService,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7).trim();
        AuthPrincipal principal = tokenService.parseAndVerify(token);
        if (principal == null && token.startsWith("guest_offline_")) {
            String reqFp = request.getHeader("X-Fingerprint");
            if (reqFp != null && !reqFp.isBlank()) {
                String cleanFp = reqFp.trim();
                String gid = "guest_" + cleanFp.substring(0, Math.min(12, cleanFp.length()));
                principal = new AuthPrincipal(null, gid, null, gid, cleanFp, "guest", System.currentTimeMillis() + 86_400_000L);
            }
        }
        if (principal == null) {
            if (isPublicPath(request.getRequestURI())) {
                filterChain.doFilter(request, response);
                return;
            }
            writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN", "身份令牌无效、被篡改或已过期");
            return;
        }

        if (principal.isGuest()) {
            String requestFingerprint = request.getHeader("X-Fingerprint");
            if (requestFingerprint == null || requestFingerprint.isBlank()
                    || !requestFingerprint.trim().equals(principal.fingerprintHash())) {
                if (isPublicPath(request.getRequestURI())) {
                    filterChain.doFilter(request, response);
                    return;
                }
                writeErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "FINGERPRINT_MISMATCH",
                        "游客身份三重校验失败：请求头 X-Fingerprint 与匿名令牌内指纹不一致");
                return;
            }

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT fingerprint_hash, revoked FROM guest_sessions WHERE guest_id = ?",
                    principal.guestId()
            );
            if (rows.isEmpty()) {
                jdbcTemplate.update(
                        "INSERT OR IGNORE INTO guest_sessions (guest_id, fingerprint_hash, issue_ip, revoked) VALUES (?, ?, '127.0.0.1', 0)",
                        principal.guestId(), principal.fingerprintHash()
                );
                rows = jdbcTemplate.queryForList(
                        "SELECT fingerprint_hash, revoked FROM guest_sessions WHERE guest_id = ?",
                        principal.guestId()
                );
            }
            Map<String, Object> sessionRow = rows.get(0);
            String dbFp = String.valueOf(sessionRow.get("fingerprint_hash"));
            Number revokedNum = (Number) sessionRow.get("revoked");
            boolean revoked = revokedNum != null && revokedNum.intValue() != 0;

            if (revoked || !dbFp.equals(principal.fingerprintHash())) {
                if (isPublicPath(request.getRequestURI())) {
                    filterChain.doFilter(request, response);
                    return;
                }
                writeErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "GUEST_SESSION_REVOKED",
                        "游客会话已被吊销或设备指纹异常");
                return;
            }

            jdbcTemplate.update(
                    "UPDATE guest_sessions SET last_seen_at = CURRENT_TIMESTAMP WHERE guest_id = ?",
                    principal.guestId()
            );
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().toUpperCase()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private boolean isPublicPath(String uri) {
        if (uri == null) return false;
        return uri.startsWith("/api/models") || uri.startsWith("/api/auth/");
    }

    private void writeErrorResponse(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "code", code,
                "message", message
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
