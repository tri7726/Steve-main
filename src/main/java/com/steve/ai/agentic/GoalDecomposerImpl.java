package com.steve.ai.agentic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.llm.async.AsyncLLMClient;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * LLM-backed implementation of {@link GoalDecomposer}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Heuristic {@link #needsDecomposition(String)} — no LLM, synchronous</li>
 *   <li>Async {@link #decompose(String)} — calls LLM, parses JSON, validates deps, caches</li>
 * </ul>
 *
 * <p>No Minecraft / SteveEntity imports — pure Java so unit tests can run without a game instance.
 */
public class GoalDecomposerImpl implements GoalDecomposer {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final List<String> COMPLEX_KEYWORDS = List.of(
            "xây", "chuẩn bị", "setup", "full", "hoàn chỉnh", "base", "căn cứ"
    );

    private static final int WORD_COUNT_THRESHOLD = 8;

    private static final String SYSTEM_PROMPT =
            "You are a Minecraft AI assistant. Break down the goal into ordered sub-steps.\n" +
            "Return ONLY a JSON array like: " +
            "[{\"description\":\"...\",\"actionHint\":\"mine\",\"priority\":1,\"dependsOn\":null}, ...]";

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final AsyncLLMClient llmClient;
    private final Gson gson = new Gson();

    /** Cache: goal string → decomposed sub-goals (LLM called at most once per goal). */
    private final Map<String, List<SubGoal>> cache = new HashMap<>();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public GoalDecomposerImpl(AsyncLLMClient llmClient) {
        this.llmClient = llmClient;
    }

    // -----------------------------------------------------------------------
    // GoalDecomposer — needsDecomposition
    // -----------------------------------------------------------------------

    @Override
    public boolean needsDecomposition(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }

        String lower = goal.toLowerCase();

        // Keyword check (case-insensitive)
        for (String keyword : COMPLEX_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }

        // Word-count check
        String[] words = goal.trim().split("\\s+");
        return words.length > WORD_COUNT_THRESHOLD;
    }

    // -----------------------------------------------------------------------
    // GoalDecomposer — decompose
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<List<SubGoal>> decompose(String goal) {
        // Null / blank → immediate fallback
        if (goal == null || goal.isBlank()) {
            return CompletableFuture.completedFuture(fallback(goal));
        }

        // Cache hit
        if (cache.containsKey(goal)) {
            return CompletableFuture.completedFuture(cache.get(goal));
        }

        // Build prompt
        String prompt = "System: " + SYSTEM_PROMPT + "\nUser: Goal: " + goal;

        return llmClient.sendAsync(prompt, Map.of())
                .thenApply(response -> {
                    if (response == null || response.getContent() == null || response.getContent().isBlank()) {
                        return fallback(goal);
                    }

                    List<SubGoal> parsed = parseSubGoals(response.getContent(), goal);
                    List<SubGoal> validated = validateDependencies(parsed);

                    cache.put(goal, validated);
                    return validated;
                })
                .exceptionally(ex -> {
                    List<SubGoal> fb = fallback(goal);
                    cache.put(goal, fb);
                    return fb;
                });
    }

    // -----------------------------------------------------------------------
    // JSON parsing
    // -----------------------------------------------------------------------

    /**
     * Extracts the first JSON array from {@code content} and maps it to SubGoals.
     * Returns a fallback list on any parse error.
     */
    private List<SubGoal> parseSubGoals(String content, String originalGoal) {
        try {
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start == -1 || end == -1 || end <= start) {
                return fallback(originalGoal);
            }

            String json = content.substring(start, end + 1);
            Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> raw = gson.fromJson(json, listType);

            if (raw == null || raw.isEmpty()) {
                return fallback(originalGoal);
            }

            List<SubGoal> result = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                String description = getString(entry, "description", "");
                String actionHint  = getString(entry, "actionHint", "unknown");
                int    priority    = getInt(entry, "priority", 1);
                String dependsOn   = getString(entry, "dependsOn", null);

                result.add(new SubGoal(description, actionHint, priority, dependsOn));
            }

            return result.isEmpty() ? fallback(originalGoal) : result;

        } catch (Exception e) {
            return fallback(originalGoal);
        }
    }

    // -----------------------------------------------------------------------
    // Dependency validation (Task 5.4)
    // -----------------------------------------------------------------------

    /**
     * Validates and sanitises the dependency graph:
     * <ol>
     *   <li>Any {@code dependsOn} that references a non-existent ID is set to {@code null}.</li>
     *   <li>If a cycle is detected via DFS, ALL {@code dependsOn} fields are cleared and the
     *       list is sorted by priority ascending.</li>
     * </ol>
     */
    private List<SubGoal> validateDependencies(List<SubGoal> subGoals) {
        if (subGoals == null || subGoals.isEmpty()) {
            return subGoals;
        }

        // Collect all IDs
        Set<String> ids = new HashSet<>();
        for (SubGoal sg : subGoals) {
            ids.add(sg.id());
        }

        // Step 1: Remove dangling dependsOn references
        List<SubGoal> cleaned = new ArrayList<>();
        for (SubGoal sg : subGoals) {
            if (sg.dependsOn() != null && !ids.contains(sg.dependsOn())) {
                cleaned.add(new SubGoal(sg.id(), sg.description(), sg.actionHint(),
                        sg.priority(), null, sg.status()));
            } else {
                cleaned.add(sg);
            }
        }

        // Step 2: Detect cycles via DFS
        if (hasCycle(cleaned)) {
            // Remove all dependencies and sort by priority
            List<SubGoal> noDeps = new ArrayList<>();
            for (SubGoal sg : cleaned) {
                noDeps.add(new SubGoal(sg.id(), sg.description(), sg.actionHint(),
                        sg.priority(), null, sg.status()));
            }
            noDeps.sort(Comparator.comparingInt(SubGoal::priority));
            return noDeps;
        }

        return cleaned;
    }

    /**
     * DFS-based cycle detection on the dependency graph.
     *
     * @return {@code true} if at least one cycle exists
     */
    private boolean hasCycle(List<SubGoal> subGoals) {
        // Build adjacency map: id → dependsOn (i.e., directed edge: sg → its dependency)
        Map<String, String> depMap = new HashMap<>();
        for (SubGoal sg : subGoals) {
            if (sg.dependsOn() != null) {
                depMap.put(sg.id(), sg.dependsOn());
            }
        }

        Set<String> visited    = new HashSet<>();
        Set<String> inStack    = new HashSet<>();

        for (SubGoal sg : subGoals) {
            if (dfsCycleCheck(sg.id(), depMap, visited, inStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycleCheck(String node,
                                   Map<String, String> depMap,
                                   Set<String> visited,
                                   Set<String> inStack) {
        if (inStack.contains(node)) {
            return true;  // back-edge → cycle
        }
        if (visited.contains(node)) {
            return false; // already fully explored, no cycle from here
        }

        visited.add(node);
        inStack.add(node);

        String next = depMap.get(node);
        if (next != null && dfsCycleCheck(next, depMap, visited, inStack)) {
            return true;
        }

        inStack.remove(node);
        return false;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<SubGoal> fallback(String goal) {
        String desc = (goal == null || goal.isBlank()) ? "complete goal" : goal;
        return List.of(new SubGoal(desc, "unknown", 1));
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        String s = val.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        try {
            // Gson deserialises numbers as Double by default
            return (int) Math.round(Double.parseDouble(val.toString()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
