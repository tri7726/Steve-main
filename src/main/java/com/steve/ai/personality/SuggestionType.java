package com.steve.ai.personality;

public enum SuggestionType {
    LOW_TORCH(8, 200),
    LOW_FOOD(5, 300),
    NO_WEAPON(0, 400),
    NIGHT_APPROACHING(0, 600),
    NEARBY_RESOURCE(0, 150);

    final int threshold;
    final int cooldownTicks;

    SuggestionType(int threshold, int cooldownTicks) {
        this.threshold = threshold;
        this.cooldownTicks = cooldownTicks;
    }
}
