package com.steve.ai.personality;

public interface PersonalityProfile {
    PersonalityType getType();
    String getName();
    String formatMessage(String rawMessage, RelationshipLevel level);
    String getGreeting(RelationshipLevel level);
    float getJokeChance();
    float getProactiveChance();
    String getLevelUpMessage(RelationshipLevel newLevel);

    default String formatEmotionalMessage(String raw, RelationshipLevel level, EmotionalContext ctx) {
        return formatMessage(raw, level);
    }

    default String getAddressForm(RelationshipLevel level) {
        return "bạn";
    }
}
