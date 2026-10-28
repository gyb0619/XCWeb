package com.xcweb.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcweb.config.XcWebProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TokenService {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final XcWebProperties properties;
    private final ObjectMapper objectMapper;

    public TokenService(XcWebProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public record IssuedToken(String token, long expiresAt) {
    }

    public IssuedToken issueUserToken(Long userId, String username, String email, String role) {
        long now = System.currentTimeMillis();
        long expiresAt = now + Duration.ofDays(properties.getSecurity().getUserTokenDays()).toMillis();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "user:" + userId);
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("email", email);
        claims.put("role", role);
        claims.put("iat", now / 1000);
        claims.put("exp", expiresAt / 1000);

        return new IssuedToken(signClaims(claims), expiresAt);
    }

    public IssuedToken issueGuestToken(String guestId, String fingerprintHash) {
        long now = System.currentTimeMillis();
        long expiresAt = now + Duration.ofDays(properties.getSecurity().getGuestTokenDays()).toMillis();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "guest:" + guestId);
        claims.put("guestId", guestId);
        claims.put("fingerprintHash", fingerprintHash);
        claims.put("role", "guest");
        claims.put("iat", now / 1000);
        claims.put("exp", expiresAt / 1000);

        return new IssuedToken(signClaims(claims), expiresAt);
    }

    public AuthPrincipal parseAndVerify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String signingInput = parts[0] + "." + parts[1];
            byte[] expectedSig = hmacSha256(signingInput);
            byte[] providedSig = URL_DECODER.decode(parts[2]);

            if (!MessageDigest.isEqual(expectedSig, providedSig)) {
                return null;
            }

            byte[] payloadBytes = URL_DECODER.decode(parts[1]);
            Map<String, Object> claims = objectMapper.readValue(payloadBytes, new TypeReference<>() {
            });

            Number expNum = (Number) claims.get("exp");
            if (expNum == null) {
                return null;
            }
            long expiresAtMs = expNum.longValue() * 1000L;
            if (System.currentTimeMillis() > expiresAtMs) {
                return null;
            }

            String role = (String) claims.get("role");
            if (role == null || role.isBlank()) {
                return null;
            }

            if ("guest".equalsIgnoreCase(role)) {
                String guestId = (String) claims.get("guestId");
                String fpHash = (String) claims.get("fingerprintHash");
                if (guestId == null || guestId.isBlank() || fpHash == null || fpHash.isBlank()) {
                    return null;
                }
                return new AuthPrincipal(null, guestId, null, guestId, fpHash, "guest", expiresAtMs);
            } else {
                Number userIdNum = (Number) claims.get("userId");
                String username = (String) claims.get("username");
                String email = (String) claims.get("email");
                if (userIdNum == null || username == null) {
                    return null;
                }
                return new AuthPrincipal(userIdNum.longValue(), username, email, null, null, role.toLowerCase(), expiresAtMs);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String signClaims(Map<String, Object> claims) {
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            String headerB64 = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(header));
            String payloadB64 = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signingInput = headerB64 + "." + payloadB64;
            String signatureB64 = URL_ENCODER.encodeToString(hmacSha256(signingInput));
            return signingInput + "." + signatureB64;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign token", e);
        }
    }

    private byte[] hmacSha256(String input) throws Exception {
        String secret = properties.getSecurity().getJwtSecret();
        if (secret == null || secret.isBlank()) {
            secret = "xcweb-fallback-hmac-sha256-secret-key-2026";
        }
        Mac mac = Mac.getInstance(HMAC_ALGO);
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
        mac.init(keySpec);
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }
}
