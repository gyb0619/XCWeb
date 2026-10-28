package com.xcweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "xcweb")
public class XcWebProperties {

    private String timezone = "Asia/Shanghai";
    private Security security = new Security();
    private Quota quota = new Quota();
    private Llm llm = new Llm();

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public static class Security {
        private String jwtSecret;
        private int userTokenDays = 7;
        private int guestTokenDays = 30;
        private boolean initDemoAccounts = true;
        private String adminUsername = "admin";
        private String adminPassword = "";
        private String devUsername = "developer";
        private String devPassword = "";
        private String demoAdminFallback = "";
        private String demoDevFallback = "";

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public int getUserTokenDays() {
            return userTokenDays;
        }

        public void setUserTokenDays(int userTokenDays) {
            this.userTokenDays = userTokenDays;
        }

        public int getGuestTokenDays() {
            return guestTokenDays;
        }

        public void setGuestTokenDays(int guestTokenDays) {
            this.guestTokenDays = guestTokenDays;
        }

        public boolean isInitDemoAccounts() {
            return initDemoAccounts;
        }

        public void setInitDemoAccounts(boolean initDemoAccounts) {
            this.initDemoAccounts = initDemoAccounts;
        }

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getDevUsername() {
            return devUsername;
        }

        public void setDevUsername(String devUsername) {
            this.devUsername = devUsername;
        }

        public String getDevPassword() {
            return devPassword;
        }

        public void setDevPassword(String devPassword) {
            this.devPassword = devPassword;
        }

        public String getDemoAdminFallback() {
            return demoAdminFallback;
        }

        public void setDemoAdminFallback(String demoAdminFallback) {
            this.demoAdminFallback = demoAdminFallback;
        }

        public String getDemoDevFallback() {
            return demoDevFallback;
        }

        public void setDemoDevFallback(String demoDevFallback) {
            this.demoDevFallback = demoDevFallback;
        }
    }

    public static class Quota {
        private int guestDailyLimit = 10;
        private int guestContextLimit = 4000;
        private int userDailyLimit = 50;
        private int userContextLimit = 16000;
        private int ipDailyLimit = 30;
        private int tokenIssueDailyLimit = 5;

        public int getGuestDailyLimit() {
            return guestDailyLimit;
        }

        public void setGuestDailyLimit(int guestDailyLimit) {
            this.guestDailyLimit = guestDailyLimit;
        }

        public int getGuestContextLimit() {
            return guestContextLimit;
        }

        public void setGuestContextLimit(int guestContextLimit) {
            this.guestContextLimit = guestContextLimit;
        }

        public int getUserDailyLimit() {
            return userDailyLimit;
        }

        public void setUserDailyLimit(int userDailyLimit) {
            this.userDailyLimit = userDailyLimit;
        }

        public int getUserContextLimit() {
            return userContextLimit;
        }

        public void setUserContextLimit(int userContextLimit) {
            this.userContextLimit = userContextLimit;
        }

        public int getIpDailyLimit() {
            return ipDailyLimit;
        }

        public void setIpDailyLimit(int ipDailyLimit) {
            this.ipDailyLimit = ipDailyLimit;
        }

        public int getTokenIssueDailyLimit() {
            return tokenIssueDailyLimit;
        }

        public void setTokenIssueDailyLimit(int tokenIssueDailyLimit) {
            this.tokenIssueDailyLimit = tokenIssueDailyLimit;
        }
    }

    public static class Llm {
        private String defaultModel = "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B";
        private int connectTimeoutMs = 5000;
        private int firstTokenTimeoutMs = 8000;
        private int maxConsecutiveFailures = 3;
        private Map<String, ProviderConfig> providers = new HashMap<>();
        private List<PresetModel> models = new ArrayList<>();

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getFirstTokenTimeoutMs() {
            return firstTokenTimeoutMs;
        }

        public void setFirstTokenTimeoutMs(int firstTokenTimeoutMs) {
            this.firstTokenTimeoutMs = firstTokenTimeoutMs;
        }

        public int getMaxConsecutiveFailures() {
            return maxConsecutiveFailures;
        }

        public void setMaxConsecutiveFailures(int maxConsecutiveFailures) {
            this.maxConsecutiveFailures = maxConsecutiveFailures;
        }

        public Map<String, ProviderConfig> getProviders() {
            return providers;
        }

        public void setProviders(Map<String, ProviderConfig> providers) {
            this.providers = providers;
        }

        public List<PresetModel> getModels() {
            return models;
        }

        public void setModels(List<PresetModel> models) {
            this.models = models;
        }
    }

    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class PresetModel {
        private String id;
        private String name;
        private String provider;
        private String description;
        private boolean supportsThinking;
        private boolean enabled = true;
        private boolean isDefault = false;
        private boolean isMock = false;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isSupportsThinking() {
            return supportsThinking;
        }

        public void setSupportsThinking(boolean supportsThinking) {
            this.supportsThinking = supportsThinking;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDefault() {
            return isDefault;
        }

        public void setIsDefault(boolean isDefault) {
            this.isDefault = isDefault;
        }

        public void setDefault(boolean isDefault) {
            this.isDefault = isDefault;
        }

        public boolean isMock() {
            return isMock;
        }

        public void setIsMock(boolean isMock) {
            this.isMock = isMock;
        }

        public void setMock(boolean isMock) {
            this.isMock = isMock;
        }
    }
}
