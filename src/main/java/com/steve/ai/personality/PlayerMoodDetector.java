package com.steve.ai.personality;

public class PlayerMoodDetector {

    public static PlayerMood detect(String command) {
        if (command == null) return PlayerMood.CASUAL;

        long exclamationCount = command.chars().filter(c -> c == '!').count();
        if (exclamationCount >= 2) return PlayerMood.EXCITED;

        if (command.contains("giúp") || command.contains("cứu") || command.contains("nhanh")) {
            return PlayerMood.URGENT;
        }

        if (command.contains("cảm ơn") || command.contains("tốt lắm") || command.contains("giỏi")) {
            return PlayerMood.HAPPY;
        }

        return PlayerMood.CASUAL;
    }
}
