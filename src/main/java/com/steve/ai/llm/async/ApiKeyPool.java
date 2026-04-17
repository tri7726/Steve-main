package com.steve.ai.llm.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ phận B — API Key Manager (Người điều phối).
 *
 * Quản lý một pool nhiều API key theo cơ chế Round-Robin.
 * Key bị 429/500 sẽ bị đưa vào cooldown tạm thời và tự động phục hồi.
 *
 * Thread-safe: dùng AtomicInteger cho round-robin counter,
 * ConcurrentHashMap cho cooldown map.
 */
public class ApiKeyPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyPool.class);

    /** Mặc định cooldown 60 giây khi key bị rate-limit (429) */
    private static final long DEFAULT_COOLDOWN_MS = 60_000L;
    /** Cooldown ngắn hơn cho lỗi server 5xx (thường thoáng qua) */
    private static final long SERVER_ERROR_COOLDOWN_MS = 15_000L;

    private final String providerId;
    private final List<String> keys;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    /** key → thời điểm (epoch ms) key được phép dùng lại */
    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    /**
     * @param providerId tên provider để log (e.g. "groq", "openai")
     * @param keys       danh sách API key, ít nhất 1 key
     */
    public ApiKeyPool(String providerId, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("ApiKeyPool requires at least one key for: " + providerId);
        }
        this.providerId = providerId;
        // Lọc key rỗng
        this.keys = new ArrayList<>(keys.stream()
            .filter(k -> k != null && !k.isBlank())
            .toList());
        if (this.keys.isEmpty()) {
            throw new IllegalArgumentException("ApiKeyPool: all keys are blank for: " + providerId);
        }
        LOGGER.info("[{}] ApiKeyPool initialized with {} key(s)", providerId, this.keys.size());
    }

    /**
     * Lấy key tiếp theo theo Round-Robin, bỏ qua key đang trong cooldown.
     *
     * @return API key sẵn sàng dùng
     * @throws AllKeysExhaustedException nếu tất cả key đều đang trong cooldown
     */
    public String acquireKey() {
        int size = keys.size();
        long now = System.currentTimeMillis();

        // Thử tối đa size lần để tìm key không bị cooldown
        for (int attempt = 0; attempt < size; attempt++) {
            int idx = roundRobinIndex.getAndUpdate(i -> (i + 1) % size);
            String key = keys.get(idx);

            Long blockedUntil = cooldownUntil.get(key);
            if (blockedUntil == null || now >= blockedUntil) {
                // Key sẵn sàng
                cooldownUntil.remove(key); // dọn entry cũ nếu đã hết hạn
                LOGGER.debug("[{}] Acquired key index={} (masked: {}...)",
                    providerId, idx, maskKey(key));
                return key;
            }

            long remaining = (blockedUntil - now) / 1000;
            LOGGER.debug("[{}] Key index={} in cooldown ({}s remaining), skipping",
                providerId, idx, remaining);
        }

        throw new AllKeysExhaustedException(
            "[" + providerId + "] All " + size + " key(s) are in cooldown. Retry later.");
    }

    /**
     * Đưa key vào cooldown sau khi nhận lỗi từ API.
     *
     * @param key        key bị lỗi
     * @param statusCode HTTP status code (429, 500, v.v.)
     */
    public void reportFailure(String key, int statusCode) {
        long cooldownMs = (statusCode == 429) ? DEFAULT_COOLDOWN_MS : SERVER_ERROR_COOLDOWN_MS;
        long until = System.currentTimeMillis() + cooldownMs;
        cooldownUntil.put(key, until);
        LOGGER.warn("[{}] Key {} penalized for {}s (HTTP {})",
            providerId, maskKey(key), cooldownMs / 1000, statusCode);
    }

    /**
     * Đánh dấu key hoạt động tốt (xóa cooldown nếu có).
     * Gọi sau khi nhận HTTP 200 thành công.
     */
    public void reportSuccess(String key) {
        cooldownUntil.remove(key);
    }

    /** Số key đang sẵn sàng (không trong cooldown) */
    public int availableCount() {
        long now = System.currentTimeMillis();
        return (int) keys.stream()
            .filter(k -> {
                Long until = cooldownUntil.get(k);
                return until == null || now >= until;
            })
            .count();
    }

    public int totalCount() {
        return keys.size();
    }

    /** Che giấu key khi log để tránh lộ secret */
    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    public static class AllKeysExhaustedException extends RuntimeException {
        public AllKeysExhaustedException(String message) {
            super(message);
        }
    }
}
