package com.steve.ai.personality;

import com.steve.ai.llm.AgentObservation;
import java.util.Optional;

public interface SurvivalMonitor {
    SurvivalState evaluate(AgentObservation obs);
    Optional<SurvivalAction> getRecommendedAction(SurvivalState state);
    boolean shouldInterruptCurrentTask(SurvivalState state);
    default void setCurrentTick(long tick) {}
}
