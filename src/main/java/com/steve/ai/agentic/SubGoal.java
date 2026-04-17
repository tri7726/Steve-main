package com.steve.ai.agentic;

import java.util.UUID;

/**
 * Represents a single sub-step in a decomposed goal plan.
 */
public record SubGoal(
    String id,
    String description,
    String actionHint,
    int priority,
    String dependsOn,   // nullable — ID of SubGoal that must complete first
    SubGoalStatus status
) {
    /**
     * Convenience constructor: auto-generates UUID for id and sets status=PENDING.
     */
    public SubGoal(String description, String actionHint, int priority, String dependsOn) {
        this(UUID.randomUUID().toString(), description, actionHint, priority, dependsOn, SubGoalStatus.PENDING);
    }

    /**
     * Convenience constructor without dependsOn.
     */
    public SubGoal(String description, String actionHint, int priority) {
        this(description, actionHint, priority, null);
    }

    /**
     * Returns a copy of this SubGoal with the given status.
     */
    public SubGoal withStatus(SubGoalStatus newStatus) {
        return new SubGoal(id, description, actionHint, priority, dependsOn, newStatus);
    }
}
