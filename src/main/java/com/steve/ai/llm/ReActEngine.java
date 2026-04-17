package com.steve.ai.llm;

import com.steve.ai.action.Task;
import com.steve.ai.agentic.ToolRegistry;
import com.steve.ai.agentic.WorkingMemory;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.async.AsyncLLMClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * ReActEngine: the core AI Agent loop.
 *
 * Each iteration:
 *   1. OBSERVE — capture AgentObservation (world snapshot)
 *   2. REASON  — ask LLM "given the goal + observation + history, what next?"
 *   3. ACT     — return the chosen next Task to ActionExecutor
 *   4. Repeat until LLM says "done" or max steps reached.
 *
 * The LLM response follows the ReAct JSON schema:
 * {
 *   "done": false,
 *   "reasoning": "I still need 8 more iron...",
 *   "next_action": { "action": "mine", "parameters": { "block": "iron", "quantity": 8 } }
 * }
 * OR
 * {
 *   "done": true,
 *   "summary": "Mined 16 iron and handed it to the player."
 * }
 */
public class ReActEngine {

    private static final Logger LOGGER = Logger.getLogger(ReActEngine.class.getName());

    private static final int DEFAULT_MAX_STEPS = 12;   // Fallback when no WorkingMemory
    private static final int ABS_MAX_STEPS = 40;        // Hard ceiling regardless of budget
    private static final String REACT_SYSTEM_PROMPT = """
            You are an autonomous Minecraft AI agent using the ReAct (Reason + Act) pattern.
            You have a GOAL and receive real-time observations after each action.
            
            RESPOND ONLY with valid JSON matching ONE of these two schemas:
            
            If more actions are needed:
            {"done": false, "reasoning": "short explanation", "next_action": {"action": "TYPE", "parameters": {...}}}
            
            If the goal is fully accomplished:
            {"done": true, "summary": "what was accomplished"}
            
            AVAILABLE ACTIONS:
            - mine:     {"block": "iron", "quantity": 8}
            - smelt:    {"item": "raw_iron", "quantity": 8}
            - craft:    {"item": "iron_sword", "quantity": 1}
            - build:    {"structure": "house", "blocks": ["oak_planks"], "dimensions": [9,6,9]}
            - attack:   {"target": "hostile"}
            - follow:   {"player": "NAME"}
            - pathfind: {"x": 0, "y": 64, "z": 0}
            - farm:     {"mode": "harvest", "crop": "wheat", "radius": 16}
            - chest:    {"mode": "store"}
            - trade:    {"mode": "buy", "item": "emerald", "quantity": 1}
            - place:    {"block": "torch", "x": 0, "y": 64, "z": 0}
            - give:     {"player": "NAME", "item": "diamond", "quantity": 5}
            - feed:     {"animal": "cow", "item": "wheat"}
            
            SMART CHAINING RULES:
            - If goal requires iron INGOTS: first mine iron_ore, THEN smelt raw_iron
            - If goal requires a crafted item: check if smelted/raw materials needed first
            - If night & hostile mobs around: prioritize attack or move to safety
            - "done": true ONLY when goal is fully completed, not just one step done
            
            CRITICAL: Output ONLY valid JSON. No markdown, no text outside JSON.
            """;

    private final SteveEntity steve;
    private final AsyncLLMClient asyncClient;
    private final String originalGoal;
    private final ToolRegistry toolRegistry;
    private final List<String> actionHistory = new ArrayList<>();

    private int stepCount = 0;
    private boolean finished = false;
    /** Effective step limit — updated from WorkingMemory.getStepBudget() when available */
    private int effectiveMaxSteps = DEFAULT_MAX_STEPS;

    /** Backward-compatible constructor — uses default ToolRegistry. */
    public ReActEngine(SteveEntity steve, AsyncLLMClient asyncClient, String originalGoal) {
        this(steve, asyncClient, originalGoal, com.steve.ai.agentic.ToolRegistryImpl.createDefault());
    }

    /** Full constructor with ToolRegistry for dynamic tool descriptions. */
    public ReActEngine(SteveEntity steve, AsyncLLMClient asyncClient, String originalGoal, ToolRegistry toolRegistry) {
        this.steve = steve;
        this.asyncClient = asyncClient;
        this.originalGoal = originalGoal;
        this.toolRegistry = toolRegistry;
    }

    /** True when the agent has declared success or hit the step limit */
    public boolean isFinished() { return finished; }
    public int getStepCount()   { return stepCount; }
    public int getEffectiveMaxSteps() { return effectiveMaxSteps; }

    /**
     * Called after each action completes (original overload — backward compatible).
     * Returns a future that resolves to:
     * - A Task if more work needed
     * - null if goal is done
     */
    public CompletableFuture<Task> evaluateNextStep(AgentObservation observation) {
        stepCount++;

        if (stepCount > effectiveMaxSteps) {
            LOGGER.warning("[ReAct] '" + steve.getSteveName() + "' hit max steps (" + effectiveMaxSteps + ") for goal: " + originalGoal);
            finished = true;
            return CompletableFuture.completedFuture(null);
        }

        // Build the user prompt with full context
        String userPrompt = buildReActPrompt(observation);

        String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
        Map<String, Object> params = Map.of(
                "systemPrompt", REACT_SYSTEM_PROMPT,
                "model", getModelForProvider(provider),
                "maxTokens", 300,
                "temperature", 0.3
        );

        LOGGER.info("[ReAct] '" + steve.getSteveName() + "' step " + stepCount + "/" + effectiveMaxSteps + " evaluating next step for goal: " + originalGoal);

        return asyncClient.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    if (response == null || response.getContent() == null) {
                        LOGGER.severe("[ReAct] null response from LLM");
                        finished = true;
                        return null;
                    }
                    return parseReActResponse(response.getContent());
                })
                .exceptionally(ex -> {
                    LOGGER.severe("[ReAct] error evaluating step: " + ex.getMessage());
                    finished = true;
                    return null;
                });
    }

    /**
     * Enhanced overload — includes WorkingMemory context in the prompt.
     * Validates action type against ToolRegistry when available.
     *
     * @param observation   must not be null
     * @param workingMemory if not null, must contain at least one SubGoal
     */
    public CompletableFuture<Task> evaluateNextStep(AgentObservation observation, WorkingMemory workingMemory) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }
        if (workingMemory != null && workingMemory.getSubGoals().isEmpty()) {
            throw new IllegalStateException("workingMemory has no SubGoals");
        }

        stepCount++;

        // Sync adaptive step budget from WorkingMemory
        if (workingMemory != null) {
            int budget = workingMemory.getStepBudget();
            effectiveMaxSteps = Math.min(budget, ABS_MAX_STEPS);
        }

        if (stepCount > effectiveMaxSteps) {
            LOGGER.warning("[ReAct] '" + steve.getSteveName() + "' hit max steps (" + effectiveMaxSteps + ") for goal: " + originalGoal);
            finished = true;
            return CompletableFuture.completedFuture(null);
        }

        String userPrompt = buildReActPromptWithContext(observation, workingMemory);
        String systemPrompt = buildSystemPrompt();

        String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
        Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", getModelForProvider(provider),
                "maxTokens", 300,
                "temperature", 0.3
        );

        LOGGER.info("[ReAct] '" + steve.getSteveName() + "' step " + stepCount + "/" + effectiveMaxSteps + " evaluating next step (with context) for goal: " + originalGoal);

        return asyncClient.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    if (response == null || response.getContent() == null) {
                        LOGGER.severe("[ReAct] null response from LLM");
                        finished = true;
                        return null;
                    }
                    return parseReActResponseWithRegistry(response.getContent());
                })
                .exceptionally(ex -> {
                    LOGGER.severe("[ReAct] error evaluating step: " + ex.getMessage());
                    finished = true;
                    return null;
                });
    }

    /**
     * Forces a replan by injecting the replan reason into the prompt.
     * Resets finished=false so replanning can proceed even if previously finished.
     *
     * @param observation   must not be null
     * @param workingMemory if not null, must contain at least one SubGoal
     * @param replanReason  reason for replanning (e.g. "diamond not found, try cave")
     */
    public CompletableFuture<Task> forceReplan(AgentObservation observation, WorkingMemory workingMemory, String replanReason) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }
        if (workingMemory != null && workingMemory.getSubGoals().isEmpty()) {
            throw new IllegalStateException("workingMemory has no SubGoals");
        }

        // Allow replanning even if previously finished
        finished = false;
        stepCount++;

        // Sync adaptive step budget from WorkingMemory
        if (workingMemory != null) {
            int budget = workingMemory.getStepBudget();
            effectiveMaxSteps = Math.min(budget, ABS_MAX_STEPS);
        }

        if (stepCount > effectiveMaxSteps) {
            LOGGER.warning("[ReAct] '" + steve.getSteveName() + "' hit max steps (" + effectiveMaxSteps + ") during replan for goal: " + originalGoal);
            finished = true;
            return CompletableFuture.completedFuture(null);
        }

        String userPrompt = buildReActPromptWithContext(observation, workingMemory)
                + "\n=== REPLAN REASON ===\n" + replanReason + "\n"
                + "\n=== DECIDE: What is the next action to achieve the goal? ===";

        // Remove the trailing DECIDE section that buildReActPromptWithContext already appended
        // (we replace it with the replan version above)
        // Actually we need to build without the trailing DECIDE, then add replan + DECIDE
        userPrompt = buildReActPromptWithContextNoDecide(observation, workingMemory)
                + "\n=== REPLAN REASON ===\n" + replanReason + "\n"
                + "\n=== DECIDE: What is the next action to achieve the goal? ===";

        String systemPrompt = buildSystemPrompt();

        String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
        Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", getModelForProvider(provider),
                "maxTokens", 300,
                "temperature", 0.3
        );

        LOGGER.info("[ReAct] '" + steve.getSteveName() + "' forcing replan for goal: " + originalGoal + " | reason: " + replanReason);

        return asyncClient.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    if (response == null || response.getContent() == null) {
                        LOGGER.severe("[ReAct] null response from LLM during replan");
                        finished = true;
                        return null;
                    }
                    return parseReActResponseWithRegistry(response.getContent());
                })
                .exceptionally(ex -> {
                    LOGGER.severe("[ReAct] error during replan: " + ex.getMessage());
                    finished = true;
                    return null;
                });
    }

    // ── Prompt Building ───────────────────────────────────────────────────────

    private String buildReActPrompt(AgentObservation obs) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== ORIGINAL GOAL ===\n");
        sb.append(originalGoal).append("\n\n");

        sb.append("=== STEP ").append(stepCount).append("/").append(effectiveMaxSteps).append(" ===\n\n");

        sb.append(obs.toPromptSection()).append("\n");

        if (!actionHistory.isEmpty()) {
            sb.append("=== COMPLETED STEPS SO FAR ===\n");
            for (int i = 0; i < actionHistory.size(); i++) {
                sb.append((i + 1)).append(". ").append(actionHistory.get(i)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("=== DECIDE: What is the next action to achieve the goal? ===");
        return sb.toString();
    }

    /**
     * Builds a prompt that includes WorkingMemory context.
     * Includes the DECIDE section at the end.
     */
    private String buildReActPromptWithContext(AgentObservation obs, WorkingMemory workingMemory) {
        return buildReActPromptWithContextNoDecide(obs, workingMemory)
                + "\n=== DECIDE: What is the next action to achieve the goal? ===";
    }

    /**
     * Builds a prompt that includes WorkingMemory context, WITHOUT the trailing DECIDE section.
     * Used by forceReplan to insert the replan reason before DECIDE.
     */
    private String buildReActPromptWithContextNoDecide(AgentObservation obs, WorkingMemory workingMemory) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== ORIGINAL GOAL ===\n");
        sb.append(originalGoal).append("\n\n");

        sb.append("=== STEP ").append(stepCount).append("/").append(effectiveMaxSteps).append(" ===\n\n");

        sb.append(obs.toPromptSection()).append("\n");

        if (workingMemory != null) {
            sb.append(workingMemory.toPromptSection()).append("\n");
        }

        if (!actionHistory.isEmpty()) {
            sb.append("=== COMPLETED STEPS SO FAR ===\n");
            for (int i = 0; i < actionHistory.size(); i++) {
                sb.append((i + 1)).append(". ").append(actionHistory.get(i)).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Builds the system prompt — uses ToolRegistry if available, otherwise falls back to hardcoded.
     */
    private String buildSystemPrompt() {
        if (toolRegistry == null) {
            return REACT_SYSTEM_PROMPT;
        }

        // Replace the AVAILABLE ACTIONS section with dynamic tool descriptions
        String toolBlock = toolRegistry.buildToolDescriptionBlock();
        return """
                You are an autonomous Minecraft AI agent using the ReAct (Reason + Act) pattern.
                You have a GOAL and receive real-time observations after each action.
                
                RESPOND ONLY with valid JSON matching ONE of these two schemas:
                
                If more actions are needed:
                {"done": false, "reasoning": "short explanation", "next_action": {"action": "TYPE", "parameters": {...}}}
                
                If the goal is fully accomplished:
                {"done": true, "summary": "what was accomplished"}
                
                AVAILABLE ACTIONS:
                """ + toolBlock + """
                
                SMART CHAINING RULES:
                - If goal requires iron INGOTS: first mine iron_ore, THEN smelt raw_iron
                - If goal requires a crafted item: check if smelted/raw materials needed first
                - If night & hostile mobs around: prioritize attack or move to safety
                - "done": true ONLY when goal is fully completed, not just one step done
                
                CRITICAL: Output ONLY valid JSON. No markdown, no text outside JSON.
                """;
    }

    // ── Response Parsing ──────────────────────────────────────────────────────

    private Task parseReActResponse(String json) {
        try {
            json = json.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\n?", "").replaceAll("```", "").trim();
            }

            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            boolean done = root.has("done") && root.get("done").getAsBoolean();
            if (done) {
                String summary = root.has("summary") ? root.get("summary").getAsString() : "Goal complete";
                LOGGER.info("[ReAct] '" + steve.getSteveName() + "' declared DONE: " + summary);
                actionHistory.add("✓ DONE: " + summary);

                if (steve.level().isClientSide) {
                    com.steve.ai.client.SteveGUI.addSteveMessage(steve.getSteveName(), "Done! " + summary);
                }
                finished = true;
                return null;
            }

            String reasoning = root.has("reasoning") ? root.get("reasoning").getAsString() : "";
            LOGGER.info("[ReAct] Reasoning: " + reasoning);

            com.google.gson.JsonObject nextAction = root.getAsJsonObject("next_action");
            if (nextAction == null) {
                LOGGER.warning("[ReAct] No next_action in response");
                finished = true;
                return null;
            }

            String actionType = nextAction.get("action").getAsString();
            com.google.gson.JsonObject paramsJson = nextAction.has("parameters")
                    ? nextAction.getAsJsonObject("parameters")
                    : new com.google.gson.JsonObject();

            java.util.Map<String, Object> params = new java.util.HashMap<>();
            for (var entry : paramsJson.entrySet()) {
                var val = entry.getValue();
                if (val.isJsonPrimitive()) {
                    var prim = val.getAsJsonPrimitive();
                    if (prim.isNumber()) {
                        params.put(entry.getKey(), prim.getAsNumber());
                    } else {
                        params.put(entry.getKey(), prim.getAsString());
                    }
                } else {
                    params.put(entry.getKey(), val.toString());
                }
            }

            String histEntry = "Step " + stepCount + ": " + actionType + " → " + reasoning;
            actionHistory.add(histEntry);

            LOGGER.info("[ReAct] '" + steve.getSteveName() + "' next action: " + actionType + " with params " + params);

            return new Task(actionType, params);

        } catch (Exception e) {
            LOGGER.severe("[ReAct] Failed to parse response: " + e.getMessage() + " | Raw: " + json);
            finished = true;
            return null;
        }
    }

    /**
     * Parses the LLM response and validates the action type against ToolRegistry when available.
     * If the action type is not in the registry, logs a warning, sets finished=true, returns null.
     */
    private Task parseReActResponseWithRegistry(String json) {
        try {
            json = json.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\n?", "").replaceAll("```", "").trim();
            }

            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            boolean done = root.has("done") && root.get("done").getAsBoolean();
            if (done) {
                String summary = root.has("summary") ? root.get("summary").getAsString() : "Goal complete";
                LOGGER.info("[ReAct] '" + steve.getSteveName() + "' declared DONE: " + summary);
                actionHistory.add("✓ DONE: " + summary);

                if (steve.level().isClientSide) {
                    com.steve.ai.client.SteveGUI.addSteveMessage(steve.getSteveName(), "Done! " + summary);
                }
                finished = true;
                return null;
            }

            String reasoning = root.has("reasoning") ? root.get("reasoning").getAsString() : "";
            LOGGER.info("[ReAct] Reasoning: " + reasoning);

            com.google.gson.JsonObject nextAction = root.getAsJsonObject("next_action");
            if (nextAction == null) {
                LOGGER.warning("[ReAct] No next_action in response");
                finished = true;
                return null;
            }

            String actionType = nextAction.get("action").getAsString();

            // Validate action type against ToolRegistry
            if (toolRegistry != null && !toolRegistry.hasTool(actionType)) {
                LOGGER.warning("[ReAct] Unknown action type from LLM: '" + actionType + "' — not in ToolRegistry, rejecting task");
                finished = true;
                return null;
            }

            com.google.gson.JsonObject paramsJson = nextAction.has("parameters")
                    ? nextAction.getAsJsonObject("parameters")
                    : new com.google.gson.JsonObject();

            java.util.Map<String, Object> params = new java.util.HashMap<>();
            for (var entry : paramsJson.entrySet()) {
                var val = entry.getValue();
                if (val.isJsonPrimitive()) {
                    var prim = val.getAsJsonPrimitive();
                    if (prim.isNumber()) {
                        params.put(entry.getKey(), prim.getAsNumber());
                    } else {
                        params.put(entry.getKey(), prim.getAsString());
                    }
                } else {
                    params.put(entry.getKey(), val.toString());
                }
            }

            String histEntry = "Step " + stepCount + ": " + actionType + " → " + reasoning;
            actionHistory.add(histEntry);

            LOGGER.info("[ReAct] '" + steve.getSteveName() + "' next action: " + actionType + " with params " + params);

            return new Task(actionType, params);

        } catch (Exception e) {
            LOGGER.severe("[ReAct] Failed to parse response: " + e.getMessage() + " | Raw: " + json);
            finished = true;
            return null;
        }
    }

    private String getModelForProvider(String provider) {
        return switch (provider) {
            case "openai" -> SteveConfig.OPENAI_MODEL.get();
            case "groq"   -> SteveConfig.GROQ_MODEL.get();
            default       -> SteveConfig.GROQ_MODEL.get();
        };
    }
}
