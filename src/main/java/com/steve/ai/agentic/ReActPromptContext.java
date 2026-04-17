package com.steve.ai.agentic;

/**
 * All information needed to build a ReAct prompt for the LLM.
 * Uses String summaries instead of direct object references to avoid circular dependencies.
 */
public record ReActPromptContext(
    String originalGoal,
    int stepNumber,
    int maxSteps,
    String observationSummary,
    String workingMemorySummary,
    String toolDescriptions,
    boolean isReplan,
    String replanReason   // nullable
) {}
