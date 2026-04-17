package com.steve.ai.agentic;

import java.util.List;

/**
 * Stores intermediate state for a multi-step agent task.
 * Lives for the duration of one agent loop, discarded when the loop ends.
 * Thread-safe: accessed from both game thread and async LLM thread.
 */
public interface WorkingMemory {

    // --- Sub-goal tracking ---

    void setSubGoals(List<SubGoal> subGoals);

    List<SubGoal> getSubGoals();

    /**
     * Returns the first SubGoal with status PENDING or IN_PROGRESS, or null if none.
     */
    SubGoal getCurrentSubGoal();

    void markSubGoalDone(String subGoalId);

    void markSubGoalFailed(String subGoalId, String reason);

    /**
     * Returns true only if the list is non-empty AND all sub-goals have status DONE.
     */
    boolean allSubGoalsDone();

    // --- Scratchpad (free-form notes for LLM) ---

    void appendNote(String note);

    List<String> getNotes();

    /**
     * Returns the last N notes joined by "\n" (up to 5; fewer if not enough notes).
     */
    String getScratchpadSummary();

    // --- Attempt tracking (to detect infinite loops) ---

    int getAttemptCount(String actionType);

    void incrementAttempt(String actionType);

    /**
     * Returns true if any actionType has attemptCount >= STUCK_THRESHOLD.
     */
    boolean isStuck();

    /**
     * Returns true if the specific actionType is stuck (>= threshold).
     * Allows finer-grained stuck detection per action type.
     */
    boolean isActionStuck(String actionType);

    /**
     * Resets the attempt counter for a specific actionType.
     * Call this after a successful action to unblock future retries.
     */
    void resetAttempt(String actionType);

    /**
     * Returns the next sub-goal to execute, respecting priority and dependencies.
     * Higher priority (lower number) sub-goals are preferred.
     * A sub-goal is only eligible if its dependsOn sub-goal is DONE (or null).
     */
    SubGoal getNextEligibleSubGoal();

    /**
     * Returns the computed step budget for this loop based on sub-goal count.
     * Adaptive: more sub-goals → more steps allowed.
     */
    int getStepBudget();

    /**
     * Sets the step budget explicitly (e.g. after goal decomposition).
     */
    void setStepBudget(int budget);

    // --- LLM prompt snapshot ---

    /**
     * Formats working memory state as a section to inject into a ReAct prompt.
     */
    String toPromptSection();
}
