package com.steve.ai.memory;

import com.steve.ai.agentic.AgentLoopContext;
import com.steve.ai.agentic.AgentLoopStatus;
import com.steve.ai.agentic.SubGoal;
import com.steve.ai.agentic.SubGoalStatus;
import com.steve.ai.llm.async.AsyncLLMClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * SelfReflectionEngine: học từ lỗi lặp lại.
 * Khi cùng 1 action type thất bại >= threshold lần,
 * tự tổng hợp bài học và lưu vào VectorStore.
 *
 * Nếu có AsyncLLMClient, sẽ gọi LLM để tổng hợp bài học thông minh hơn.
 * Nếu không, dùng rule-based synthesis đơn giản.
 */
@SuppressWarnings("null") // BiFunction<Integer,Integer,Integer> params and Minecraft API guaranteed non-null
public class SelfReflectionEngine {
    private static final Logger LOGGER = Logger.getLogger(SelfReflectionEngine.class.getName());
    private static final int FAILURE_THRESHOLD = 3;

    /** actionType → danh sách lỗi gần đây */
    private final Map<String, List<String>> failureLog = new LinkedHashMap<>();

    private final VectorStore vectorStore;
    private final AsyncLLMClient llmClient;

    public SelfReflectionEngine(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.llmClient = null;
    }

    public SelfReflectionEngine(VectorStore vectorStore, AsyncLLMClient llmClient) {
        this.vectorStore = vectorStore;
        this.llmClient = llmClient;
    }

    /**
     * Ghi nhận lỗi. Nếu đủ threshold → tổng hợp bài học và lưu vào VectorStore.
     *
     * @param actionType loại action (mine, craft, combat, v.v.)
     * @param errorMsg   thông báo lỗi từ ActionResult
     */
    public void recordFailure(String actionType, String errorMsg) {
        if (actionType == null || errorMsg == null) return;

        failureLog.computeIfAbsent(actionType, k -> new ArrayList<>()).add(errorMsg);
        List<String> failures = failureLog.get(actionType);

        if (failures.size() >= FAILURE_THRESHOLD) {
            // Find most common error for prompt/lesson
            Map<String, Integer> freq = new LinkedHashMap<>();
            for (String e : failures) {
                String key = extractKeyPhrase(e);
                freq.merge(key, 1, Integer::sum);
            }
            String mostCommonError = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown error");

            List<String> snapshot = new ArrayList<>(failures);
            failures.clear(); // Reset trước khi gọi async

            if (llmClient != null) {
                String prompt = "Action '" + actionType + "' failed " + FAILURE_THRESHOLD
                    + " times: " + mostCommonError + ". Give 1 specific advice in Vietnamese or English.";
                llmClient.sendAsync(prompt, Collections.emptyMap())
                    .thenAccept(response -> {
                        String lesson = "[LESSON] " + response.getContent();
                        String safe = lesson.length() > 200 ? lesson.substring(0, 200) : lesson;
                        vectorStore.addMemory(safe, MemoryPriority.HIGH);
                        LOGGER.info("[SelfReflection] LLM lesson saved: " + safe);
                    })
                    .exceptionally(ex -> {
                        LOGGER.warning("[SelfReflection] LLM call failed, falling back to rule-based: " + ex.getMessage());
                        storeFallbackLesson(actionType, snapshot);
                        return null;
                    });
            } else {
                storeFallbackLesson(actionType, snapshot);
            }
        }
    }

    private void storeFallbackLesson(String actionType, List<String> errors) {
        String lesson = synthesizeLesson(actionType, errors);
        if (lesson != null) {
            String safe = lesson.length() > 200 ? lesson.substring(0, 200) : lesson;
            vectorStore.addMemory(safe, MemoryPriority.HIGH);
            LOGGER.info("[SelfReflection] Lesson saved: " + safe);
        }
    }

    /**
     * Tổng hợp bài học từ danh sách lỗi theo rule-based.
     */
    private String synthesizeLesson(String actionType, List<String> errors) {
        // Tìm pattern phổ biến nhất trong errors
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String e : errors) {
            String key = extractKeyPhrase(e);
            freq.merge(key, 1, Integer::sum);
        }
        String mostCommon = freq.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("unknown error");

        return "[LESSON] " + actionType + ": failed because '" + mostCommon
            + "' -> avoid this pattern next time";
    }

    private String extractKeyPhrase(String error) {
        // Lấy 50 ký tự đầu làm key phrase
        if (error == null) return "unknown";
        return error.length() > 50 ? error.substring(0, 50) : error;
    }

    /** Xóa toàn bộ failure log (gọi khi bắt đầu session mới) */
    public void reset() {
        failureLog.clear();
    }

    /**
     * Kiểm tra xem action type này có đang trong trạng thái fail nhiều không.
     * Dùng để skip action đã fail liên tục.
     */
    public boolean isRepeatedlyFailing(String actionType) {
        List<String> failures = failureLog.get(actionType);
        return failures != null && failures.size() >= FAILURE_THRESHOLD;
    }

    /** Lấy số lần fail của action type */
    public int getFailureCount(String actionType) {
        List<String> failures = failureLog.get(actionType);
        return failures == null ? 0 : failures.size();
    }

    /**
     * Records the outcome of a completed agent loop session.
     * Logs the result and, for FAILED/ABORTED loops, records failures for each failed SubGoal.
     *
     * @param context the completed AgentLoopContext, or null (no-op)
     */
    public void recordSession(AgentLoopContext context) {
        if (context == null) return;

        if (context.status == AgentLoopStatus.DONE) {
            LOGGER.info("[SelfReflection] Agent loop DONE: loopId=" + context.loopId
                + ", goal=" + context.originalGoal
                + ", steps=" + context.totalSteps);
        } else if (context.status == AgentLoopStatus.FAILED || context.status == AgentLoopStatus.ABORTED) {
            LOGGER.warning("[SelfReflection] Agent loop " + context.status
                + ": loopId=" + context.loopId
                + ", goal=" + context.originalGoal
                + ", steps=" + context.totalSteps);

            for (SubGoal subGoal : context.memory.getSubGoals()) {
                if (subGoal.status() == SubGoalStatus.FAILED) {
                    recordFailure(subGoal.actionHint(), "SubGoal failed: " + subGoal.description());
                }
            }
        }
    }
}
