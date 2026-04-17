package com.steve.ai.personality;

import com.steve.ai.llm.AgentObservation;
import java.util.Optional;

public interface ProactiveSuggestionEngine {
    Optional<Suggestion> checkTriggers(AgentObservation obs, RelationshipLevel level);
    boolean isCooldownExpired(SuggestionType type);
    void recordSuggestion(SuggestionType type);
}
