package com.steve.ai.llm;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.async.*;
import com.steve.ai.llm.resilience.LLMFallbackHandler;
import com.steve.ai.llm.resilience.ResilientLLMClient;
import com.steve.ai.memory.WorldKnowledge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class TaskPlanner {
    // Async resilient clients
    private final AsyncLLMClient asyncOpenAIClient;
    private final AsyncLLMClient asyncGroqClient;
    private final AsyncLLMClient asyncGeminiClient;
    private final LLMCache llmCache;
    private final LLMFallbackHandler fallbackHandler;

    public TaskPlanner() {
        // Initialize async infrastructure
        this.llmCache = new LLMCache();
        this.fallbackHandler = new LLMFallbackHandler();

        // Fetch configs independently to support multiple models properly
        String openAIApiKey = SteveConfig.OPENAI_API_KEY.get();
        String openAIModel = SteveConfig.OPENAI_MODEL.get();
        
        String groqApiKey = SteveConfig.GROQ_API_KEY.get();
        String groqModel = SteveConfig.GROQ_MODEL.get();
        int groqMaxRetries = SteveConfig.GROQ_MAX_RETRIES.get();

        // Bộ phận A: Xây danh sách key từ config
        // Ưu tiên groqApiKeys (list), fallback về groqApiKey (single)
        java.util.List<String> groqKeys = new java.util.ArrayList<>();
        java.util.List<? extends String> configuredKeys = SteveConfig.GROQ_API_KEYS.get();
        if (configuredKeys != null && !configuredKeys.isEmpty()) {
            groqKeys.addAll(configuredKeys);
        }
        if (groqKeys.isEmpty() && groqApiKey != null && !groqApiKey.isBlank()) {
            groqKeys.add(groqApiKey);
        }
        if (groqKeys.isEmpty()) {
            // Placeholder để tránh crash khi chưa cấu hình key
            groqKeys.add("UNCONFIGURED");
            SteveMod.LOGGER.warn("No Groq API key configured! Set groq.apiKey or groq.apiKeys in config.");
        }
        
        int maxTokens = SteveConfig.MAX_TOKENS.get();
        double temperature = SteveConfig.TEMPERATURE.get();

        // Create base async clients
        // Chỉ khởi tạo OpenAI/Gemini client nếu có key, tránh crash khi dùng Groq
        AsyncLLMClient baseOpenAI = (openAIApiKey != null && !openAIApiKey.isBlank())
            ? new AsyncOpenAIClient(openAIApiKey, openAIModel, maxTokens, temperature)
            : null;
        // Bộ phận B + C: ResilientGroqClient tích hợp ApiKeyPool + Retry Loop
        AsyncLLMClient baseGroq = new ResilientGroqClient(groqKeys, groqModel, 500, temperature, groqMaxRetries);
        // Note: For gemini we temporarily use openAIApiKey until it gets its own config (out of scope for now)
        AsyncLLMClient baseGemini = (openAIApiKey != null && !openAIApiKey.isBlank())
            ? new AsyncGeminiClient(openAIApiKey, "gemini-1.5-flash", maxTokens, temperature)
            : null;

        // Wrap with resilience patterns
        this.asyncOpenAIClient = baseOpenAI != null ? new ResilientLLMClient(baseOpenAI, llmCache, fallbackHandler) : null;
        this.asyncGroqClient = new ResilientLLMClient(baseGroq, llmCache, fallbackHandler);
        this.asyncGeminiClient = baseGemini != null ? new ResilientLLMClient(baseGemini, llmCache, fallbackHandler) : null;

        SteveMod.LOGGER.info("TaskPlanner initialized with async resilient clients");
    }

    /**
     * Asynchronously plans tasks for Steve using the configured LLM provider.
     *
     * <p>This method returns immediately with a CompletableFuture, allowing the game thread
     * to continue without blocking. The actual LLM call is executed on a separate thread pool
     * with full resilience patterns (circuit breaker, retry, rate limiting, caching).</p>
     *
     * <p><b>Non-blocking:</b> Game thread is never blocked</p>
     * <p><b>Resilient:</b> Automatic retry, circuit breaker, fallback on failure</p>
     * <p><b>Cached:</b> Repeated prompts may hit cache (40-60% hit rate)</p>
     *
     * @param steve   The Steve entity making the request
     * @param command The user command to plan
     * @return CompletableFuture that completes with the parsed response, or null on failure
     */
    public CompletableFuture<ResponseParser.ParsedResponse> planTasksAsync(SteveEntity steve, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(steve);
            // Chuẩn hóa lệnh tiếng Việt trước khi gửi LLM
            String normalizedCommand = VietnameseCommandNormalizer.normalize(command);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, normalizedCommand, worldKnowledge);

            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("[Async] Requesting AI plan for Steve '{}' using {}: {}",
                steve.getSteveName(), provider, command);

            // Build params map - dùng đúng model theo provider
            String selectedModel = switch (provider) {
                case "groq" -> SteveConfig.GROQ_MODEL.get();
                case "openai" -> SteveConfig.OPENAI_MODEL.get();
                default -> SteveConfig.GROQ_MODEL.get();
            };
            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", selectedModel,
                "maxTokens", SteveConfig.MAX_TOKENS.get(),
                "temperature", SteveConfig.TEMPERATURE.get()
            );

            // Select async client based on provider
            AsyncLLMClient client = getAsyncClient(provider);

            // Execute async request
            return client.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    String content = response.getContent();
                    if (content == null || content.isEmpty()) {
                        SteveMod.LOGGER.error("[Async] Empty response from LLM");
                        return null;
                    }

                    ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(content);
                    if (parsed == null) {
                        SteveMod.LOGGER.error("[Async] Failed to parse AI response");
                        return null;
                    }

                    SteveMod.LOGGER.info("[Async] Plan received: {} ({} tasks, {}ms, {} tokens, cache: {})",
                        parsed.getPlan(),
                        parsed.getTasks().size(),
                        response.getLatencyMs(),
                        response.getTokensUsed(),
                        response.isFromCache());

                    return parsed;
                })
                .exceptionally(throwable -> {
                    SteveMod.LOGGER.error("[Async] Error planning tasks: {}", throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            SteveMod.LOGGER.error("[Async] Error setting up task planning", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Returns the appropriate async client based on provider config.
     *
     * @param provider Provider name ("openai", "groq", "gemini")
     * @return Resilient async client
     */
    private AsyncLLMClient getAsyncClient(String provider) {
        return switch (provider) {
            case "openai" -> asyncOpenAIClient != null ? asyncOpenAIClient : asyncGroqClient;
            case "gemini" -> asyncGeminiClient != null ? asyncGeminiClient : asyncGroqClient;
            case "groq"   -> asyncGroqClient;
            default -> {
                SteveMod.LOGGER.warn("[Async] Unknown provider '{}', using Groq", provider);
                yield asyncGroqClient;
            }
        };
    }

    /** Public accessor so ReActEngine can get the correct client */
    public AsyncLLMClient getAsyncClientForProvider(String provider) {
        return getAsyncClient(provider);
    }

    /**
     * Returns the LLM cache for monitoring.
     *
     * @return LLM cache instance
     */
    public LLMCache getLLMCache() {
        return llmCache;
    }

    /**
     * Checks if the specified provider's async client is healthy.
     *
     * @param provider Provider name
     * @return true if healthy (circuit breaker not OPEN)
     */
    public boolean isProviderHealthy(String provider) {
        return getAsyncClient(provider).isHealthy();
    }

    public boolean validateTask(Task task) {
        String action = task.getAction();
        if (action == null) return false;
        
        return switch (action) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine"     -> task.hasParameters("block", "quantity");
            case "place"    -> task.hasParameters("block", "x", "y", "z");
            case "craft"    -> task.hasParameters("item", "quantity");
            case "attack"   -> task.hasParameters("target");
            case "follow"   -> task.hasParameters("player");
            case "gather"   -> task.hasParameters("resource", "quantity");
            case "build"    -> task.hasParameters("structure", "blocks", "dimensions");
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", action);
                yield false;
            }
        };
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(this::validateTask)
            .toList();
    }

    /**
     * Static validator — không cần TaskPlanner instance, dùng được trong unit test.
     */
    public static boolean validateTaskStatic(Task task) {
        if (task == null || task.getAction() == null) return false;
        return switch (task.getAction()) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine"     -> task.hasParameters("block", "quantity");
            case "place"    -> task.hasParameters("block", "x", "y", "z");
            case "craft"    -> task.hasParameters("item", "quantity");
            case "attack"   -> task.hasParameters("target");
            case "follow"   -> task.hasParameters("player");
            case "gather"   -> task.hasParameters("resource", "quantity");
            case "build"    -> task.hasParameters("structure", "blocks", "dimensions");
            default         -> false;
        };
    }
}

