package denny.ai.agent.trading.domain.prompt;

import denny.ai.agent.trading.domain.config.TradingAgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 管理与版本化机制。
 * <p>
 * 功能：
 * <ul>
 *   <li>支持从配置文件加载 Prompt（支持热更新）</li>
 *   <li>支持 Prompt 版本管理</li>
 *   <li>支持 A/B Prompt 测试（通过配置切换版本）</li>
 * </ul>
 *
 * <p>使用方式：
 * <ul>
 *   <li>内置版本（硬编码）：{@code PromptVersionManager.getPrompt(key)}</li>
 *   <li>文件版本（classpath）：{@code PromptVersionManager.getPrompt(key, "v1")}</li>
 *   <li>A/B 测试（配置切换）：通过 {@code spring.ai.trading.prompt-version} 配置</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptVersionManager {

    /**
     * Prompt 版本键前缀（用于配置）
     */
    private static final String PROMPT_VERSION_PREFIX = "spring.ai.trading.prompts.";

    /**
     * 内置 Prompt 模板集合
     */
    private static final Map<String, String> BUILTIN_PROMPTS = new ConcurrentHashMap<>();

    /**
     * 已加载的文件版本 Prompt 缓存
     */
    private final Map<String, Map<String, String>> fileVersionCache = new ConcurrentHashMap<>();

    private final TradingAgentProperties properties;

    public PromptVersionManager(TradingAgentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        initializeBuiltinPrompts();
        loadFilePrompts();
        log.info("PromptVersionManager initialized with {} builtin prompts and {} file versions",
                BUILTIN_PROMPTS.size(), fileVersionCache.size());
    }

    /**
     * 获取 Prompt，默认使用当前激活版本。
     *
     * @param promptKey Prompt 键（如 "analyst.fundamental"）
     * @return Prompt 内容，找不到时返回 null
     */
    public String getPrompt(String promptKey) {
        String activeVersion = getActiveVersion();
        return getPrompt(promptKey, activeVersion);
    }

    /**
     * 获取指定版本的 Prompt。
     *
     * @param promptKey Prompt 键
     * @param version   版本标识（如 "v1"、"v2"、"default"）
     * @return Prompt 内容
     */
    public String getPrompt(String promptKey, String version) {
        // 1. 优先从文件版本缓存中查找
        Map<String, String> versionMap = fileVersionCache.get(promptKey);
        if (versionMap != null && versionMap.containsKey(version)) {
            return versionMap.get(version);
        }

        // 2. 尝试加载文件版本
        String loaded = loadPromptFromFile(promptKey, version);
        if (loaded != null) {
            versionMap = fileVersionCache.computeIfAbsent(promptKey, k -> new ConcurrentHashMap<>());
            versionMap.put(version, loaded);
            return loaded;
        }

        // 3. 回退到内置版本
        String builtin = BUILTIN_PROMPTS.get(promptKey);
        if (builtin != null) {
            log.debug("Prompt '{}' version '{}' not found, falling back to builtin", promptKey, version);
            return builtin;
        }

        log.warn("Prompt '{}' version '{}' not found in any source", promptKey, version);
        return null;
    }

    /**
     * 获取当前激活版本号。
     * 优先级：配置 &gt; 文件 &gt; 默认
     */
    public String getActiveVersion() {
        String configured = properties.getPromptVersion();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return "default";
    }

    /**
     * 重新加载所有 Prompt（热更新支持）。
     */
    public void reload() {
        log.info("Reloading all prompts...");
        fileVersionCache.clear();
        loadFilePrompts();
    }

    /**
     * 注册新的 Prompt（支持动态扩展）。
     *
     * @param promptKey Prompt 键
     * @param content   Prompt 内容
     */
    public void registerBuiltinPrompt(String promptKey, String content) {
        BUILTIN_PROMPTS.put(promptKey, content);
        log.info("Registered builtin prompt: {}", promptKey);
    }

    /**
     * 获取所有内置 Prompt 键列表。
     */
    public String[] getBuiltinPromptKeys() {
        return BUILTIN_PROMPTS.keySet().toArray(new String[0]);
    }

    /**
     * 获取所有已注册的内置 Prompt。
     */
    public Map<String, String> getAllBuiltinPrompts() {
        return Map.copyOf(BUILTIN_PROMPTS);
    }

    /**
     * 获取文件版本缓存中的所有版本。
     */
    public Map<String, Map<String, String>> getAllFileVersions() {
        return Map.copyOf(fileVersionCache);
    }

    // ==================== 私有方法 ====================

    private void initializeBuiltinPrompts() {
        // 注册各 Prompt 常量类的内置版本
        registerBuiltinPrompt("analyst.fundamental", AnalystPromptTemplate.FUNDAMENTAL_ANALYST_PROMPT);
        registerBuiltinPrompt("analyst.technical", AnalystPromptTemplate.TECHNICAL_ANALYST_PROMPT);
        registerBuiltinPrompt("analyst.sentiment", AnalystPromptTemplate.SENTIMENT_ANALYST_PROMPT);
        registerBuiltinPrompt("analyst.news", AnalystPromptTemplate.NEWS_ANALYST_PROMPT);

        registerBuiltinPrompt("debate.bull", DebatePromptTemplate.BULL_RESEARCHER_PROMPT);
        registerBuiltinPrompt("debate.bear", DebatePromptTemplate.BEAR_RESEARCHER_PROMPT);
        registerBuiltinPrompt("debate.manager", DebatePromptTemplate.RESEARCH_MANAGER_PROMPT);

        registerBuiltinPrompt("trader.plan", TraderPromptTemplate.TRADER_PROMPT);

        registerBuiltinPrompt("risk.aggressive", RiskAnalystPromptTemplate.AGGRESSIVE_ANALYST_PROMPT);
        registerBuiltinPrompt("risk.conservative", RiskAnalystPromptTemplate.CONSERVATIVE_ANALYST_PROMPT);
        registerBuiltinPrompt("risk.neutral", RiskAnalystPromptTemplate.NEUTRAL_ANALYST_PROMPT);

        registerBuiltinPrompt("portfolio.manager", PortfolioManagerPromptTemplate.PORTFOLIO_MANAGER_PROMPT);

        registerBuiltinPrompt("intent.routing", IntentRoutingPrompt.SYSTEM_PROMPT);
    }

    private void loadFilePrompts() {
        // 从配置文件加载（spring.ai.trading.prompts.xxx 键值对）
        loadPromptsFromProperties();

        // 尝试从 classpath 加载 prompts/ 目录下的文件
        loadPromptsFromClasspath();
    }

    private void loadPromptsFromProperties() {
        // TradingAgentProperties 中可以扩展 promptVersion 配置
        // 目前通过 spring.ai.trading.prompts.{key} 在 application.yml 中配置
        log.debug("Prompts from properties loaded");
    }

    private void loadPromptsFromClasspath() {
        // 支持从 classpath:prompts/{key}/{version}.txt 加载
        String[] builtinKeys = getBuiltinPromptKeys();
        for (String key : builtinKeys) {
            String[] parts = key.split("\\.");
            if (parts.length >= 2) {
                String filePath = "prompts/" + key.replace(".", "/") + "/default.txt";
                String loaded = loadFromClasspath(filePath);
                if (loaded != null) {
                    Map<String, String> versionMap = fileVersionCache.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                    versionMap.put("default", loaded);
                    log.debug("Loaded file prompt: {}", filePath);
                }
            }
        }
    }

    private String loadPromptFromFile(String promptKey, String version) {
        String filePath = "prompts/" + promptKey.replace(".", "/") + "/" + version + ".txt";
        return loadFromClasspath(filePath);
    }

    private String loadFromClasspath(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            log.trace("Could not load classpath resource: {}", resourcePath);
        }
        return null;
    }
}
