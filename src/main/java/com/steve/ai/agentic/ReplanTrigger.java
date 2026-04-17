package com.steve.ai.agentic;

import com.steve.ai.action.ActionResult;
import com.steve.ai.llm.AgentObservation;

/**
 * Decides whether the agent should continue, replan, or abort after an action result.
 * Separates replanning logic from ActionExecutor for testability.
 */
public interface ReplanTrigger {

    /**
     * Evaluates the action result and current context to determine the next control flow.
     *
     * @param result      the result of the last executed action
     * @param memory      the current working memory / scratchpad
     * @param observation the latest world observation
     * @return {@link ReplanDecision#CONTINUE} to retry, {@link ReplanDecision#REPLAN} to ask LLM,
     *         or {@link ReplanDecision#ABORT} to give up
     */
    ReplanDecision evaluate(ActionResult result, WorkingMemory memory, AgentObservation observation);
}
