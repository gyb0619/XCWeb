package com.xcweb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Order(1)
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final XcWebProperties properties;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate,
                               PasswordEncoder passwordEncoder,
                               XcWebProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        initPragmasAndTables();
        initPresetModelStates();
        initPrivilegedAccounts();
        seedTelemetrySampleDataIfEmpty();
    }

    public void initPragmasAndTables() {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL;");
        jdbcTemplate.execute("PRAGMA busy_timeout=5000;");
        jdbcTemplate.execute("PRAGMA foreign_keys=ON;");

        try {
            jdbcTemplate.update("DELETE FROM quota_logs WHERE identifier LIKE 'token_%'");
        } catch (Exception ignored) {
            // Table might not exist on very first run, handled below
        }

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username VARCHAR(50) UNIQUE NOT NULL,
                email VARCHAR(100) UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                role VARCHAR(20) NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin', 'dev')),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS guest_sessions (
                guest_id VARCHAR(64) PRIMARY KEY,
                fingerprint_hash VARCHAR(128) NOT NULL,
                issue_ip VARCHAR(64) NOT NULL,
                revoked INTEGER NOT NULL DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS quota_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                identifier VARCHAR(160) NOT NULL,
                role VARCHAR(20) NOT NULL,
                date_str VARCHAR(10) NOT NULL,
                call_count INTEGER DEFAULT 0,
                token_count INTEGER DEFAULT 0,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(identifier, date_str)
            );
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversations (
                id VARCHAR(64) PRIMARY KEY,
                user_id INTEGER NOT NULL,
                title VARCHAR(200) NOT NULL,
                model VARCHAR(100) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            );
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS messages (
                id VARCHAR(64) PRIMARY KEY,
                conversation_id VARCHAR(64) NOT NULL,
                role VARCHAR(20) NOT NULL,
                content TEXT NOT NULL,
                thinking_content TEXT,
                attachments_json TEXT,
                versions_json TEXT,
                created_at BIGINT NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            );
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS user_settings (
                user_id INTEGER PRIMARY KEY,
                settings_json TEXT NOT NULL,
                updated_at BIGINT NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            );
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS request_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                identifier VARCHAR(160) NOT NULL,
                model VARCHAR(100) NOT NULL,
                is_mock INTEGER NOT NULL DEFAULT 0,
                prompt_tokens INTEGER,
                completion_tokens INTEGER,
                latency_ms INTEGER,
                status VARCHAR(20) NOT NULL,
                created_at BIGINT NOT NULL
            );
            """);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_conversations_user_updated ON conversations(user_id, updated_at);");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, created_at);");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_guest_fp ON guest_sessions(fingerprint_hash);");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS model_states (
                model_id VARCHAR(120) PRIMARY KEY,
                name VARCHAR(120),
                provider VARCHAR(80),
                api_key VARCHAR(255),
                base_url VARCHAR(255),
                description TEXT,
                supports_thinking INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                is_default INTEGER NOT NULL DEFAULT 0,
                is_mock INTEGER NOT NULL DEFAULT 0,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """);

        ensureColumnExists("model_states", "name", "VARCHAR(120)");
        ensureColumnExists("model_states", "provider", "VARCHAR(80)");
        ensureColumnExists("model_states", "api_key", "VARCHAR(255)");
        ensureColumnExists("model_states", "base_url", "VARCHAR(255)");
        ensureColumnExists("model_states", "description", "TEXT");
        ensureColumnExists("model_states", "supports_thinking", "INTEGER DEFAULT 0");
        ensureColumnExists("model_states", "is_mock", "INTEGER DEFAULT 0");
        ensureColumnExists("model_states", "is_custom", "INTEGER DEFAULT 0");
        ensureColumnExists("model_states", "deleted", "INTEGER DEFAULT 0");
    }

    private void ensureColumnExists(String tableName, String columnName, String columnType) {
        try {
            boolean exists = false;
            List<Map<String, Object>> cols = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");
            for (Map<String, Object> col : cols) {
                if (columnName.equalsIgnoreCase(String.valueOf(col.get("name")))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType);
                log.info("Added column {} to table {}", columnName, tableName);
            }
        } catch (Exception e) {
            log.warn("Failed ensuring column {} on {}: {}", columnName, tableName, e.getMessage());
        }
    }

    private void initPresetModelStates() {
        List<XcWebProperties.PresetModel> models = properties.getLlm().getModels();
        for (XcWebProperties.PresetModel model : models) {
            String provider = model.getProvider();
            String defaultBaseUrl = "";
            String defaultApiKey = "";
            if (properties.getLlm().getProviders() != null) {
                String key = provider != null ? provider.toLowerCase() : "";
                if (key.contains("silicon")) key = "siliconflow";
                else if (key.contains("zhipu") || key.contains("bigmodel")) key = "zhipu";
                else if (key.contains("groq")) key = "groq";
                XcWebProperties.ProviderConfig pCfg = properties.getLlm().getProviders().get(key);
                if (pCfg != null) {
                    if (pCfg.getBaseUrl() != null) defaultBaseUrl = pCfg.getBaseUrl();
                    if (pCfg.getApiKey() != null) defaultApiKey = pCfg.getApiKey();
                }
            }

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM model_states WHERE model_id = ?",
                    Integer.class,
                    model.getId()
            );
            if (count == null || count == 0) {
                jdbcTemplate.update("""
                    INSERT INTO model_states (model_id, name, provider, api_key, base_url, description, supports_thinking, enabled, is_default, is_mock)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    model.getId(),
                    model.getName(),
                    model.getProvider(),
                    defaultApiKey,
                    defaultBaseUrl,
                    model.getDescription(),
                    model.isSupportsThinking() ? 1 : 0,
                    model.isEnabled() ? 1 : 0,
                    model.isDefault() ? 1 : 0,
                    model.isMock() ? 1 : 0
                );
            } else {
                jdbcTemplate.update("""
                    UPDATE model_states
                    SET name = CASE WHEN name IS NULL OR name = '' THEN ? ELSE name END,
                        provider = CASE WHEN provider IS NULL OR provider = '' THEN ? ELSE provider END,
                        base_url = CASE WHEN base_url IS NULL OR base_url = '' THEN ? ELSE base_url END,
                        description = CASE WHEN description IS NULL OR description = '' THEN ? ELSE description END,
                        supports_thinking = COALESCE(supports_thinking, ?),
                        is_mock = COALESCE(is_mock, ?)
                    WHERE model_id = ?
                    """,
                    model.getName(),
                    model.getProvider(),
                    defaultBaseUrl,
                    model.getDescription(),
                    model.isSupportsThinking() ? 1 : 0,
                    model.isMock() ? 1 : 0,
                    model.getId()
                );
            }
        }
    }

    private void initPrivilegedAccounts() {
        XcWebProperties.Security sec = properties.getSecurity();

        String adminUsername = sec.getAdminUsername();
        String adminSecret = resolveAccountSecret(sec.getAdminPassword(), sec.getDemoAdminFallback(), sec.isInitDemoAccounts());
        upsertPrivilegedAccount(adminUsername, adminUsername + "@xcweb.local", adminSecret, "admin");

        String devUsername = sec.getDevUsername();
        String devSecret = resolveAccountSecret(sec.getDevPassword(), sec.getDemoDevFallback(), sec.isInitDemoAccounts());
        upsertPrivilegedAccount(devUsername, devUsername + "@xcweb.local", devSecret, "dev");

        log.info("XCWeb privileged accounts initialized [admin='{}', dev='{}']", adminUsername, devUsername);
    }

    private String resolveAccountSecret(String configuredSecret, String fallbackSecret, boolean allowFallback) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            return configuredSecret.trim();
        }
        if (allowFallback && fallbackSecret != null && !fallbackSecret.isBlank()) {
            return fallbackSecret.trim();
        }
        return UUID.randomUUID().toString();
    }

    private void upsertPrivilegedAccount(String username, String email, String rawSecret, String role) {
        String passwordHash = passwordEncoder.encode(rawSecret);
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM users WHERE username = ?",
                Integer.class,
                username
        );
        if (existing != null && existing > 0) {
            jdbcTemplate.update(
                    "UPDATE users SET password_hash = ?, role = ? WHERE username = ?",
                    passwordHash, role, username
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO users (username, email, password_hash, role) VALUES (?, ?, ?, ?)",
                    username, email, passwordHash, role
            );
        }
    }

    private void seedTelemetrySampleDataIfEmpty() {
        Integer logCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM request_logs", Integer.class);
        if (logCount != null && logCount > 0) {
            return;
        }

        ZoneId zoneId = ZoneId.of(properties.getTimezone());
        LocalDate today = LocalDate.now(zoneId);
        long nowMs = System.currentTimeMillis();

        String[] sampleModels = {
                "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
                "Qwen/Qwen2.5-7B-Instruct",
                "glm-4-flash",
                "llama-3.3-70b-versatile",
                "mock-cognitive-core"
        };

        for (int dayOffset = 6; dayOffset >= 0; dayOffset--) {
            LocalDate d = today.minusDays(dayOffset);
            String dateStr = d.format(DateTimeFormatter.ISO_LOCAL_DATE);

            int guestCalls = 6 + (dayOffset % 4);
            int userCalls = 18 + (dayOffset * 3);
            int devCalls = 8 + (dayOffset * 2);

            jdbcTemplate.update("""
                INSERT OR IGNORE INTO quota_logs (identifier, role, date_str, call_count, token_count)
                VALUES (?, ?, ?, ?, ?)
                """, "seed:guest_cluster_" + dayOffset, "guest", dateStr, guestCalls, guestCalls * 620);

            jdbcTemplate.update("""
                INSERT OR IGNORE INTO quota_logs (identifier, role, date_str, call_count, token_count)
                VALUES (?, ?, ?, ?, ?)
                """, "seed:user_cluster_" + dayOffset, "user", dateStr, userCalls, userCalls * 940);

            jdbcTemplate.update("""
                INSERT OR IGNORE INTO quota_logs (identifier, role, date_str, call_count, token_count)
                VALUES (?, ?, ?, ?, ?)
                """, "seed:dev_cluster_" + dayOffset, "dev", dateStr, devCalls, devCalls * 1280);

            for (int h = 0; h < 24; h += 3) {
                long ts = nowMs - (dayOffset * 86_400_000L) - ((23 - h) * 3_600_000L);
                String model = sampleModels[(dayOffset + h) % sampleModels.length];
                int isMock = model.equals("mock-cognitive-core") ? 1 : 0;
                int promptTokens = 140 + ((dayOffset * 19 + h * 11) % 320);
                int completionTokens = 280 + ((dayOffset * 29 + h * 17) % 680);
                int latencyMs = 420 + ((dayOffset * 83 + h * 41) % 1400);
                String identifier = (h % 6 == 0) ? "guest:demo_g" + (h / 6) : "user:demo_u" + (h % 4 + 1);

                jdbcTemplate.update("""
                    INSERT INTO request_logs (identifier, model, is_mock, prompt_tokens, completion_tokens, latency_ms, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, identifier, model, isMock, promptTokens, completionTokens, latencyMs, "200_OK", ts);
            }
        }
    }
}
