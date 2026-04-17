package com.steve.ai.personality;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.llm.ResponseParser;
import com.steve.ai.llm.TaskPlanner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ComplexCommandPlannerImpl implements ComplexCommandPlanner {

    private static final List<String> COMPLEX_KEYWORDS = List.of(
        "căn cứ", "base", "full", "đầy đủ", "hoàn chỉnh",
        "setup", "chuẩn bị", "xây dựng", "build a"
    );

    private static final List<Task> FALLBACK_TASKS = List.of(
        new Task("pathfind", Map.of("x", "0", "y", "64", "z", "0"))
    );

    private final TaskPlanner taskPlanner;

    public ComplexCommandPlannerImpl(TaskPlanner taskPlanner) {
        this.taskPlanner = taskPlanner;
    }

    @Override
    public boolean isComplexCommand(String command) {
        if (command == null) return false;
        String lower = command.toLowerCase();
        for (String keyword : COMPLEX_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<List<Task>> decompose(
            String command,
            PersonalityProfile personality,
            RelationshipLevel relationship) {

        String prompt = buildPrompt(command, personality, relationship);

        String provider = SteveConfig.AI_PROVIDER.get();
        String model = switch (provider.toLowerCase()) {
            case "groq" -> SteveConfig.GROQ_MODEL.get();
            case "openai" -> SteveConfig.OPENAI_MODEL.get();
            default -> SteveConfig.GROQ_MODEL.get();
        };

        Map<String, Object> params = Map.of(
            "model", model,
            "maxTokens", SteveConfig.MAX_TOKENS.get(),
            "temperature", SteveConfig.TEMPERATURE.get()
        );

        return taskPlanner.getAsyncClientForProvider(provider)
            .sendAsync(prompt, params)
            .thenApply(response -> {
                if (response == null) {
                    SteveMod.LOGGER.warn("[ComplexCommandPlanner] Null LLM response, using fallback");
                    return FALLBACK_TASKS;
                }
                String content = response.getContent();
                if (content == null || content.isBlank()) {
                    SteveMod.LOGGER.warn("[ComplexCommandPlanner] Empty LLM content, using fallback");
                    return FALLBACK_TASKS;
                }
                ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(content);
                if (parsed == null) {
                    SteveMod.LOGGER.warn("[ComplexCommandPlanner] Failed to parse LLM response, using fallback");
                    return FALLBACK_TASKS;
                }
                List<Task> tasks = taskPlanner.validateAndFilterTasks(parsed.getTasks());
                if (tasks.isEmpty()) {
                    SteveMod.LOGGER.warn("[ComplexCommandPlanner] No valid tasks after validation, using fallback");
                    return FALLBACK_TASKS;
                }
                return tasks;
            })
            .exceptionally(throwable -> {
                SteveMod.LOGGER.error("[ComplexCommandPlanner] LLM call failed: {}", throwable.getMessage());
                return FALLBACK_TASKS;
            });
    }

    private String buildPrompt(String command, PersonalityProfile personality, RelationshipLevel relationship) {
        return "You are Steve AI. Decompose this complex command into a list of simple tasks.\n"
            + "Personality: " + personality.getType().name() + "\n"
            + "Relationship: " + relationship.name() + "\n"
            + "Command: " + command + "\n"
            + "Respond in the same JSON format as usual task planning.";
    }
}
