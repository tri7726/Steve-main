package com.steve.ai.personality;

public class BravePersonality implements PersonalityProfile {

    private final String name;

    public BravePersonality(String name) {
        this.name = name;
    }

    @Override
    public PersonalityType getType() {
        return PersonalityType.BRAVE;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public float getJokeChance() {
        return 0.3f;
    }

    @Override
    public float getProactiveChance() {
        return 0.55f;
    }

    @Override
    public String formatMessage(String rawMessage, RelationshipLevel level) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new IllegalArgumentException("rawMessage must not be null or blank");
        }
        return rawMessage + "!";
    }

    @Override
    public String getGreeting(RelationshipLevel level) {
        return switch (level) {
            case STRANGER -> "Này! Tao là " + name + "! Sẵn sàng chiến chưa?";
            case ACQUAINTANCE -> "Yo! " + name + " đây! Hôm nay làm gì thú vị không?";
            case FRIEND -> "Bạn ơi! Tao đang chờ mày! Cùng phiêu lưu thôi!";
            case BEST_FRIEND -> "Bestie! Tao biết mày sẽ đến! Cùng chinh phục thế giới nào!";
        };
    }

    @Override
    public String getLevelUpMessage(RelationshipLevel newLevel) {
        return switch (newLevel) {
            case ACQUAINTANCE -> "Tuyệt! Chúng ta đã quen nhau rồi! Cùng phiêu lưu thôi!";
            case FRIEND -> "Woah! Mày là bạn tao rồi! Không gì có thể ngăn chúng ta!";
            case BEST_FRIEND -> "YEAH! Mày là người bạn tốt nhất của tao! Cùng nhau vô địch!";
            default -> "Lên level rồi! Tiến lên!";
        };
    }

    @Override
    public String formatEmotionalMessage(String raw, RelationshipLevel level, EmotionalContext ctx) {
        String base = (raw == null || raw.isBlank()) ? "..." : raw.trim();
        
        // Don't append if message already ends with punctuation or a similar particle
        if (base.endsWith("!") || base.endsWith("?") || base.endsWith(".") 
                || base.toLowerCase().endsWith(" đi") || base.toLowerCase().endsWith(" thôi")) {
            return base;
        }

        // 30% chance to append for more variety (less robotic)
        java.util.Random rnd = new java.util.Random();
        if (rnd.nextFloat() > 0.35f) return base;

        String particle = (ctx == EmotionalContext.TIRED) ? " thôi" : " đi";
        return base + particle;
    }

    @Override
    public String getAddressForm(RelationshipLevel level) {
        return (level == RelationshipLevel.FRIEND || level == RelationshipLevel.BEST_FRIEND) ? "bạn" : "bạn";
    }
}
