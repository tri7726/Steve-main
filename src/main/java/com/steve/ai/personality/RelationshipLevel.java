package com.steve.ai.personality;

public enum RelationshipLevel {
    STRANGER(0, 100),
    ACQUAINTANCE(100, 300),
    FRIEND(300, 700),
    BEST_FRIEND(700, Integer.MAX_VALUE);

    final int minXp;
    final int maxXp;

    RelationshipLevel(int minXp, int maxXp) {
        this.minXp = minXp;
        this.maxXp = maxXp;
    }
}
