package com.steve.ai.agentic;

import com.steve.ai.action.ActionResult;
import com.steve.ai.llm.AgentObservation;

/**
 * Default implementation of {@link ReplanTrigger}.
 *
 * Priority order:
 * 1. isStuck() globally          → ABORT  (highest priority)
 * 2. isActionStuck(actionType)   → REPLAN (per-action stuck, not global abort)
 * 3. isSuccess()                 → CONTINUE + resetAttempt
 * 4. Error classification by message content → CONTINUE / REPLAN
 */
public class ReplanTriggerImpl implements ReplanTrigger {

    @Override
    public ReplanDecision evaluate(ActionResult result, WorkingMemory memory, AgentObservation observation) {
        // 1. Global stuck — all attempts exhausted → abort
        if (memory.isStuck()) {
            return ReplanDecision.ABORT;
        }

        // 2. Success → reset attempt counter for this action type, keep going
        if (result.isSuccess()) {
            // Infer action type from observation's last action result context
            // Reset is best-effort; WorkingMemory tracks by actionType string
            SubGoal current = memory.getCurrentSubGoal();
            if (current != null) {
                memory.resetAttempt(current.actionHint());
            }
            return ReplanDecision.CONTINUE;
        }

        // 3. Classify failure by message
        ErrorType errorType = classifyError(result.getMessage());

        switch (errorType) {
            case TRANSIENT:
                // Transient errors: retry, but check if this specific action is stuck
                SubGoal sg = memory.getCurrentSubGoal();
                if (sg != null && memory.isActionStuck(sg.actionHint())) {
                    // This specific action is stuck — replan instead of aborting globally
                    memory.appendNote("Action '" + sg.actionHint() + "' stuck after retries, replanning");
                    return ReplanDecision.REPLAN;
                }
                return ReplanDecision.CONTINUE;

            case RESOURCE_MISSING:
                memory.appendNote("Resource not found: " + result.getMessage());
                return ReplanDecision.REPLAN;

            case PERMANENT: {
                SubGoal current = memory.getCurrentSubGoal();
                if (current != null) {
                    memory.markSubGoalFailed(current.id(), result.getMessage());
                }
                return ReplanDecision.REPLAN;
            }

            case UNKNOWN:
            default:
                return ReplanDecision.REPLAN;
        }
    }

    // ── Error classification ──────────────────────────────────────────────────

    private enum ErrorType {
        TRANSIENT, RESOURCE_MISSING, PERMANENT, UNKNOWN
    }

    private ErrorType classifyError(String message) {
        if (message == null) {
            return ErrorType.UNKNOWN;
        }
        String lower = message.toLowerCase();

        if (lower.contains("blocked") || lower.contains("timeout") || lower.contains("retry")) {
            return ErrorType.TRANSIENT;
        }
        if (lower.contains("not found") || lower.contains("no ") || lower.contains("missing")) {
            return ErrorType.RESOURCE_MISSING;
        }
        if (lower.contains("cannot") || lower.contains("impossible") || lower.contains("failed permanently")) {
            return ErrorType.PERMANENT;
        }
        return ErrorType.UNKNOWN;
    }
}
