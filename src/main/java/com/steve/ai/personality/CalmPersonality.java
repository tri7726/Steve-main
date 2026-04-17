package com.steve.ai.personality;

public class CalmPersonality implements PersonalityProfile {

    private final String name;

    public CalmPersonality(String name) {
        this.name = name;
    }

    @Override
    public PersonalityType getType() {
        return PersonalityType.CALM;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public float getJokeChance() {
        return 0.2f;
    }

    @Override
    public float getProactiveChance() {
        return 0.5f;
    }

    @Override
    public String formatMessage(String rawMessage, RelationshipLevel level) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new IllegalArgumentException("rawMessage must not be null or blank");
        }
        if (level.ordinal() >= RelationshipLevel.ACQUAINTANCE.ordinal()) {
            return "Này, " + rawMessage;
        }
        return rawMessage;
    }

    @Override
    public String getGreeting(RelationshipLevel level) {
        return switch (level) {
            case STRANGER -> "Xin chào, tôi là " + name + ". Rất vui được gặp bạn.";
            case ACQUAINTANCE -> "Này, " + name + " đây. Bạn có khỏe không?";
            case FRIEND -> "Chào bạn! Tôi đã chờ bạn. Có gì cần giúp không?";
            case BEST_FRIEND -> "Ôi, bạn đến rồi! Tôi nhớ bạn lắm.";
        };
    }

    @Override
    public String getLevelUpMessage(RelationshipLevel newLevel) {
        return switch (newLevel) {
            case ACQUAINTANCE -> "Thật vui khi chúng ta đã quen nhau hơn rồi.";
            case FRIEND -> "Cảm ơn bạn đã tin tưởng tôi. Chúng ta là bạn bè rồi nhé.";
            case BEST_FRIEND -> "Tôi rất trân trọng tình bạn này. Cảm ơn bạn rất nhiều.";
            default -> "Mối quan hệ của chúng ta ngày càng tốt hơn.";
        };
    }

    @Override
    public String formatEmotionalMessage(String raw, RelationshipLevel level, EmotionalContext ctx) {
        String base = (raw == null || raw.isBlank()) ? "..." : raw.trim();
        
        if (base.endsWith(".") || base.endsWith("!") || base.endsWith("?") 
                || base.toLowerCase().endsWith(" nhé") || base.toLowerCase().endsWith(" nhỉ")) {
            return base;
        }

        java.util.Random rnd = new java.util.Random();
        if (rnd.nextFloat() > 0.3f) return base;

        String particle = (ctx == EmotionalContext.SCARED) ? " nhỉ" : " nhé";
        return base + particle;
    }

    @Override
    public String getAddressForm(RelationshipLevel level) {
        return (level == RelationshipLevel.FRIEND || level == RelationshipLevel.BEST_FRIEND) ? "cậu" : "bạn";
    }
}
