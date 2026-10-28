package com.xcweb.controller;

import com.xcweb.exception.ApiException;
import com.xcweb.security.AuthPrincipal;
import com.xcweb.security.TokenService;
import com.xcweb.service.QuotaService;
import com.xcweb.util.ClientIpUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final QuotaService quotaService;

    public AuthController(JdbcTemplate jdbcTemplate,
                          PasswordEncoder passwordEncoder,
                          TokenService tokenService,
                          QuotaService quotaService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.quotaService = quotaService;
    }

    @PostMapping("/guest")
    public Map<String, Object> issueGuestToken(@RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        String fingerprintHash = body != null && body.get("fingerprintHash") != null
                ? String.valueOf(body.get("fingerprintHash")).trim()
                : "";
        if (fingerprintHash.isBlank() || fingerprintHash.length() < 8) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FINGERPRINT", "必须提供有效的浏览器指纹哈希（fingerprintHash）");
        }

        String clientIp = ClientIpUtils.getClientIp(request);
        quotaService.checkAndIncrementTokenIssue(fingerprintHash, clientIp);

        String guestId = "guest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update(
                "INSERT INTO guest_sessions (guest_id, fingerprint_hash, issue_ip, revoked) VALUES (?, ?, ?, 0)",
                guestId, fingerprintHash, clientIp
        );

        TokenService.IssuedToken issued = tokenService.issueGuestToken(guestId, fingerprintHash);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", issued.token());
        resp.put("guestId", guestId);
        resp.put("fingerprintHash", fingerprintHash);
        resp.put("role", "guest");
        resp.put("expiresAt", issued.expiresAt());
        return resp;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        String username = body != null && body.get("username") != null ? String.valueOf(body.get("username")).trim() : "";
        String email = body != null && body.get("email") != null ? String.valueOf(body.get("email")).trim() : "";
        String password = body != null && body.get("password") != null ? String.valueOf(body.get("password")) : "";

        if (username.length() < 2 || username.length() > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME", "用户名长度须在 2–50 个字符之间");
        }
        if (password.length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码长度不能少于 6 位");
        }
        if (email.isBlank()) {
            email = username + "@user.xcweb.local";
        }

        Integer existingUser = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM users WHERE username = ? OR email = ?",
                Integer.class,
                username, email
        );
        if (existingUser != null && existingUser > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", "用户名或邮箱已被注册");
        }

        // Public registration strictly creates 'user' role only
        String role = "user";
        String passwordHash = passwordEncoder.encode(password);
        String finalEmail = email;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (username, email, password_hash, role) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, username);
            ps.setString(2, finalEmail);
            ps.setString(3, passwordHash);
            ps.setString(4, role);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        long userId = key != null ? key.longValue() : jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username
        );

        TokenService.IssuedToken issued = tokenService.issueUserToken(userId, username, finalEmail, role);

        Map<String, Object> userMap = new LinkedHashMap<>();
        userMap.put("id", userId);
        userMap.put("username", username);
        userMap.put("email", finalEmail);
        userMap.put("role", role);
        userMap.put("createdAt", Instant.now().toString());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", issued.token());
        resp.put("user", userMap);
        return resp;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String usernameOrEmail = body != null && body.get("username") != null
                ? String.valueOf(body.get("username")).trim()
                : "";
        String password = body != null && body.get("password") != null
                ? String.valueOf(body.get("password"))
                : "";

        if (usernameOrEmail.isBlank() || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_CREDENTIALS", "请输入用户名和密码");
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, username, email, password_hash, role, created_at FROM users WHERE username = ? OR email = ?",
                usernameOrEmail, usernameOrEmail
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        }

        Map<String, Object> userRow = rows.get(0);
        String passwordHash = String.valueOf(userRow.get("password_hash"));
        if (!passwordEncoder.matches(password, passwordHash)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        }

        long userId = ((Number) userRow.get("id")).longValue();
        String username = String.valueOf(userRow.get("username"));
        String email = userRow.get("email") != null ? String.valueOf(userRow.get("email")) : "";
        String role = String.valueOf(userRow.get("role"));
        String createdAt = userRow.get("created_at") != null ? String.valueOf(userRow.get("created_at")) : Instant.now().toString();

        TokenService.IssuedToken issued = tokenService.issueUserToken(userId, username, email, role);

        Map<String, Object> userMap = new LinkedHashMap<>();
        userMap.put("id", userId);
        userMap.put("username", username);
        userMap.put("email", email);
        userMap.put("role", role);
        userMap.put("createdAt", createdAt);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", issued.token());
        resp.put("user", userMap);
        return resp;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthPrincipal principal,
                                  HttpServletRequest request) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录");
        }
        String clientIp = ClientIpUtils.getClientIp(request);
        Map<String, Object> quota = quotaService.getQuotaStatus(principal, clientIp);

        Map<String, Object> userMap = new LinkedHashMap<>();
        if (principal.isGuest()) {
            userMap.put("id", null);
            userMap.put("guestId", principal.guestId());
            userMap.put("username", "游客 (" + principal.guestId().substring(0, Math.min(10, principal.guestId().length())) + ")");
            userMap.put("email", null);
            userMap.put("role", "guest");
        } else {
            userMap.put("id", principal.userId());
            userMap.put("username", principal.username());
            userMap.put("email", principal.email());
            userMap.put("role", principal.role());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("user", userMap);
        resp.put("quota", quota);
        return resp;
    }
}
