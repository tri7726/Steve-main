package com.steve.ai.llm.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bộ phận C — Lính đánh thuê với Vòng lặp giải cứu (Retry Loop).
 *
 * Wrapper của AsyncGroqClient tích hợp ApiKeyPool:
 * - Mỗi request lấy key từ pool theo Round-Robin
 * - Nếu nhận 429 / 5xx: báo lỗi cho pool, xoay sang key tiếp theo và retry
 * - Sau max_retries lần thất bại: throw để caller kích hoạt fallback an toàn
 *
 * Implements AsyncLLMClient nên có thể thay thế trực tiếp AsyncGroqClient
 * ở bất kỳ chỗ nào trong codebase.
 */
public class ResilientGroqClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientGroqClient.class);
    private static final String PROVIDER_ID = "groq";

    private final ApiKeyPool keyPool;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int maxRetries;

    /**
     * @param apiKeys     danh sách Groq API key (Bộ phận A — Config)
     * @param model       model Groq (e.g. "llama-3.1-8b-instant")
     * @param maxTokens   giới hạn token mỗi request
     * @param temperature độ ngẫu nhiên (0.0 – 2.0)
     * @param maxRetries  số lần thử tối đa trước khi bó tay (khuyến nghị = số key)
     */
    public ResilientGroqClient(List<String> apiKeys, String model,
                               int maxTokens, double temperature, int maxRetries) {
        this.keyPool     = new ApiKeyPool(PROVIDER_ID, apiKeys);
        this.model       = model;
        this.maxTokens   = maxTokens;
        this.temperature = temperature;
        this.maxRetries  = Math.max(1, maxRetries);
        LOGGER.info("[groq-resilient] Initialized: {} key(s), maxRetries={}",
            keyPool.totalCount(), this.maxRetries);
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        return attemptAsync(prompt, params, 0);
    }

    /**
     * Đệ quy async: thử gửi request, nếu thất bại thì xoay key và thử lại.
     *
     * @param attempt số lần đã thử (bắt đầu từ 0)
     */
    private CompletableFuture<LLMResponse> attemptAsync(
            String prompt, Map<String, Object> params, int attempt) {

        if (attempt >= maxRetries) {
            LOGGER.error("[groq-resilient] All {} retries exhausted. Giving up.", maxRetries);
            return CompletableFuture.failedFuture(
                new LLMException(
                    "All Groq keys exhausted after " + maxRetries + " retries",
                    LLMException.ErrorType.RATE_LIMIT,
                    PROVIDER_ID,
                    false
                )
            );
        }

        // Bước 1 & 2: Xin key từ pool (Round-Robin, bỏ qua key đang cooldown)
        String key;
        try {
            key = keyPool.acquireKey();
        } catch (ApiKeyPool.AllKeysExhaustedException e) {
            LOGGER.warn("[groq-resilient] All keys in cooldown on attempt {}", attempt + 1);
            return CompletableFuture.failedFuture(
                new LLMException(e.getMessage(), LLMException.ErrorType.RATE_LIMIT, PROVIDER_ID, true)
            );
        }

        // Bước 3: Tạo client tạm với key này và gửi request
        AsyncGroqClient singleKeyClient = new AsyncGroqClient(key, model, maxTokens, temperature);

        LOGGER.debug("[groq-resilient] Attempt {}/{} with key {}...",
            attempt + 1, maxRetries, maskKey(key));

        return singleKeyClient.sendAsync(prompt, params)
            .thenApply(response -> {
                // Bước 4 Xanh: Thành công
                keyPool.reportSuccess(key);
                LOGGER.debug("[groq-resilient] Success on attempt {} (latency: {}ms)",
                    attempt + 1, response.getLatencyMs());
                return response;
            })
            .exceptionallyCompose(ex -> {
                // Bước 4 Đỏ: Thất bại — phân loại lỗi
                int statusCode = extractStatusCode(ex);

                if (statusCode == 429 || statusCode >= 500) {
                    // Bước 5: Báo lỗi cho pool, xoay key, retry
                    keyPool.reportFailure(key, statusCode);
                    LOGGER.warn("[groq-resilient] HTTP {} on attempt {}, rotating key...",
                        statusCode, attempt + 1);
                    return attemptAsync(prompt, params, attempt + 1);
                }

                // Lỗi không thể retry (401 auth, 400 bad request, parse error...)
                LOGGER.error("[groq-resilient] Non-retryable error (HTTP {}): {}",
                    statusCode, ex.getMessage());
                return CompletableFuture.failedFuture(ex);
            });
    }

    /** Trích HTTP status code từ LLMException nếu có, fallback về 0 */
    private int extractStatusCode(Throwable ex) {
        Throwable cause = ex;
        // CompletableFuture bọc exception trong CompletionException
        if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof LLMException llmEx) {
            return switch (llmEx.getErrorType()) {
                case RATE_LIMIT    -> 429;
                case SERVER_ERROR  -> 500;
                case AUTH_ERROR    -> 401;
                case TIMEOUT       -> 408;
                default            -> 0;
            };
        }
        return 0;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isHealthy() {
        return keyPool.availableCount() > 0;
    }

    /** Trả về số key đang sẵn sàng (dùng cho health check / monitoring) */
    public int availableKeys() {
        return keyPool.availableCount();
    }

    public int totalKeys() {
        return keyPool.totalCount();
    }
}
