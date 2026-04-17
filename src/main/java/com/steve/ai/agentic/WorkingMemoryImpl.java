package com.steve.ai.agentic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, thread-safe implementation of WorkingMemory.
 * No disk persistence — scratchpad is ephemeral for the duration of one agent loop.
 *
 * Upgrades:
 * - Priority-based sub-goal scheduling (getNextEligibleSubGoal)
 * - Dependency-aware: sub-goal only eligible when dependsOn is DONE
 * - Per-action stuck detection (isActionStuck)
 * - Adaptive step budget (getStepBudget / setStepBudget)
 * - resetAttempt to unblock after success
 */
@SuppressWarnings("null") // CopyOnWriteArrayList subList and BiFunction params guaranteed non-null
public class WorkingMemoryImpl implements WorkingMemory {

    private static final int STUCK_THRESHOLD = 3;
    private static final int SCRATCHPAD_WINDOW = 5;
    /** Base steps per sub-goal for adaptive budget calculation */
    private static final int STEPS_PER_SUBGOAL = 4;
    private static final int MIN_BUDGET = 8;
    private static final int MAX_BUDGET = 40;

    private volatile int stepBudget = MIN_BUDGET;

    private final CopyOnWriteArrayList<SubGoal> subGoals = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> notes = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Integer> attemptCounts = new ConcurrentHashMap<>();

    // --- Sub-goal tracking ---

    @Override
    public void setSubGoals(List<SubGoal> subGoals) {
        this.subGoals.clear();
        this.subGoals.addAll(subGoals);
        // Adaptive step budget: base + (steps per sub-goal * count), clamped
        int computed = MIN_BUDGET + subGoals.size() * STEPS_PER_SUBGOAL;
        this.stepBudget = Math.min(computed, MAX_BUDGET);
    }

    @Override
    public List<SubGoal> getSubGoals() {
        return new ArrayList<>(subGoals);
    }

    @Override
    public SubGoal getCurrentSubGoal() {
        return getNextEligibleSubGoal();
    }

    @Override
    public void markSubGoalDone(String subGoalId) {
        for (int i = 0; i < subGoals.size(); i++) {
            SubGoal sg = subGoals.get(i);
            if (sg.id().equals(subGoalId)) {
                subGoals.set(i, sg.withStatus(SubGoalStatus.DONE));
                return;
            }
        }
    }

    @Override
    public void markSubGoalFailed(String subGoalId, String reason) {
        for (int i = 0; i < subGoals.size(); i++) {
            SubGoal sg = subGoals.get(i);
            if (sg.id().equals(subGoalId)) {
                subGoals.set(i, sg.withStatus(SubGoalStatus.FAILED));
                appendNote("SubGoal failed: " + reason);
                return;
            }
        }
    }

    @Override
    public boolean allSubGoalsDone() {
        if (subGoals.isEmpty()) return false;
        for (SubGoal sg : subGoals) {
            if (sg.status() != SubGoalStatus.DONE) return false;
        }
        return true;
    }

    // --- Scratchpad ---

    @Override
    public void appendNote(String note) {
        notes.add(note);
    }

    @Override
    public List<String> getNotes() {
        return new ArrayList<>(notes);
    }

    @Override
    public String getScratchpadSummary() {
        int size = notes.size();
        int from = Math.max(0, size - SCRATCHPAD_WINDOW);
        List<String> recent = notes.subList(from, size);
        return String.join("\n", recent);
    }

    // --- Attempt tracking ---

    @Override
    public int getAttemptCount(String actionType) {
        return attemptCounts.getOrDefault(actionType, 0);
    }

    @Override
    public void incrementAttempt(String actionType) {
        attemptCounts.merge(actionType, 1, Integer::sum);
    }

    @Override
    public boolean isStuck() {
        for (int count : attemptCounts.values()) {
            if (count >= STUCK_THRESHOLD) return true;
        }
        return false;
    }

    @Override
    public boolean isActionStuck(String actionType) {
        return attemptCounts.getOrDefault(actionType, 0) >= STUCK_THRESHOLD;
    }

    @Override
    public void resetAttempt(String actionType) {
        attemptCounts.remove(actionType);
    }

    @Override
    public SubGoal getNextEligibleSubGoal() {
        // Build a set of DONE sub-goal IDs for dependency checking
        java.util.Set<String> doneIds = new java.util.HashSet<>();
        for (SubGoal sg : subGoals) {
            if (sg.status() == SubGoalStatus.DONE) {
                doneIds.add(sg.id());
            }
        }

        // Collect eligible sub-goals: PENDING or IN_PROGRESS, dependency satisfied
        List<SubGoal> eligible = new java.util.ArrayList<>();
        for (SubGoal sg : subGoals) {
            if (sg.status() != SubGoalStatus.PENDING && sg.status() != SubGoalStatus.IN_PROGRESS) {
                continue;
            }
            // Dependency check: dependsOn must be null or already DONE
            if (sg.dependsOn() != null && !doneIds.contains(sg.dependsOn())) {
                continue;
            }
            eligible.add(sg);
        }

        if (eligible.isEmpty()) return null;

        // Sort by priority ascending (lower number = higher priority)
        eligible.sort(java.util.Comparator.comparingInt(SubGoal::priority));
        return eligible.get(0);
    }

    @Override
    public int getStepBudget() {
        return stepBudget;
    }

    @Override
    public void setStepBudget(int budget) {
        this.stepBudget = Math.max(MIN_BUDGET, Math.min(MAX_BUDGET, budget));
    }

    // --- LLM prompt snapshot ---

    @Override
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("[WORKING MEMORY]\n");
        sb.append("Sub-goals:\n");
        for (SubGoal sg : subGoals) {
            String tag = statusTag(sg.status());
            sb.append("- [").append(tag).append("] ").append(sg.description()).append("\n");
        }
        sb.append("Recent notes:\n");
        String summary = getScratchpadSummary();
        if (!summary.isEmpty()) {
            sb.append(summary).append("\n");
        }
        return sb.toString();
    }

    private String statusTag(SubGoalStatus status) {
        return switch (status) {
            case DONE -> "DONE";
            case FAILED -> "FAILED";
            case IN_PROGRESS -> "IN_PROGRESS";
            case PENDING -> "PENDING";
        };
    }
}
