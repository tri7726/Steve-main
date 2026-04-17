package com.steve.ai.agentic;

import java.time.Instant;
import java.util.UUID;

/**
 * Wraps the full state of a running agent loop.
 * NOTE: The 'memory' field (WorkingMemory) will be added in Task 2.
 */
public class AgentLoopContext {
    public final String originalGoal;
    public final String loopId;
    public final Instant startedAt;
    public int totalSteps;
    public AgentLoopStatus status;

    public final WorkingMemory memory;

    /**
     * Creates a new AgentLoopContext for the given goal.
     * Auto-generates a UUID loopId, sets startedAt=now, totalSteps=0, status=RUNNING.
     */
    public AgentLoopContext(String originalGoal) {
        this.originalGoal = originalGoal;
        this.loopId = UUID.randomUUID().toString();
        this.startedAt = Instant.now();
        this.totalSteps = 0;
        this.status = AgentLoopStatus.RUNNING;
        this.memory = new WorkingMemoryImpl();
    }
}
