package com.steve.ai.personality;

public class SeriousPersonality implements PersonalityProfile {

    private final String name;

    public SeriousPersonality(String name) {
        this.name = name;
    }

    @Override
    public PersonalityType getType() {
        return PersonalityType.SERIOUS;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public float getJokeChance() {
        return 0.05f;
    }

    @Override
    public float getProactiveChance() {
        return 0.4f;
    }

    @Override
    public String formatMessage(String rawMessage, RelationshipLevel level) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new IllegalArgumentException("rawMessage must not be null or blank");
        }
        String trimmed = rawMessage.trim();
        if (level == RelationshipLevel.STRANGER) {
            return "Báo cáo: " + trimmed;
        }
        return trimmed;
    }

    @Override
    public String getGreeting(RelationshipLevel level) {
        return switch (level) {
            case STRANGER -> "Xin chào. Tôi là " + name + ".";
            case ACQUAINTANCE -> "Chào. " + name + " đây.";
            case FRIEND -> "Chào, " + name + ". Có việc gì không?";
            case BEST_FRIEND -> "Chào. Sẵn sàng hỗ trợ.";
        };
    }

    @Override
    public String getLevelUpMessage(RelationshipLevel newLevel) {
        return switch (newLevel) {
            case ACQUAINTANCE -> "Ghi nhận. Chúng ta đã quen nhau.";
            case FRIEND -> "Xác nhận: mức độ tin tưởng đã tăng.";
            case BEST_FRIEND -> "Báo cáo: quan hệ đạt mức tối đa.";
            default -> "Cập nhật quan hệ hoàn tất.";
        };
    }

    @Override
    public String formatEmotionalMessage(String raw, RelationshipLevel level, EmotionalContext ctx) {
        String base = (raw == null || raw.isBlank()) ? "..." : formatMessage(raw, level);
        
        if (base.endsWith(" ạ") || base.endsWith(".") || base.endsWith("!") || base.endsWith("?")) {
            return base;
        }

        if (level == RelationshipLevel.STRANGER || level == RelationshipLevel.ACQUAINTANCE) {
            java.util.Random rnd = new java.util.Random();
            if (rnd.nextFloat() < 0.3f) {
                return base + " ạ";
            }
        }
        return base;
    }

    @Override
    public String getAddressForm(RelationshipLevel level) {
        return "bạn";
    }
}
