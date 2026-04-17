package com.steve.ai.personality;

public enum ExperienceSource {
    TASK_COMPLETED(10),
    COMPLEX_TASK_COMPLETED(30),
    SURVIVAL_SAVED_PLAYER(50),
    PROACTIVE_HELP_ACCEPTED(15),
    CHAT_INTERACTION(2);

    final int xpAmount;

    ExperienceSource(int xpAmount) {
        this.xpAmount = xpAmount;
    }
}
