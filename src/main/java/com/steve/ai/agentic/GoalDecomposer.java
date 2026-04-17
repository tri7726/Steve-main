package com.steve.ai.agentic;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Decomposes complex player goals into ordered sub-goals using an LLM.
 *
 * <p>Intentionally avoids any Minecraft/SteveEntity dependency to remain
 * pure-Java and easily testable.</p>
 */
public interface GoalDecomposer {

    /**
     * Asynchronously breaks a goal string into an ordered list of {@link SubGoal}s.
     *
     * <p>If the LLM is unavailable or returns unparseable JSON, the implementation
     * MUST fall back to a single {@code SubGoal} wrapping the original goal so that
     * callers always receive a non-empty list.</p>
     *
     * @param goal the raw goal string from the player (may be null/blank — handled gracefully)
     * @return a future that resolves to a non-empty list of SubGoals
     */
    CompletableFuture<List<SubGoal>> decompose(String goal);

    /**
     * Synchronous heuristic check — no LLM call.
     *
     * @param goal the goal string to evaluate
     * @return {@code true} if the goal is complex enough to warrant decomposition
     */
    boolean needsDecomposition(String goal);
}
