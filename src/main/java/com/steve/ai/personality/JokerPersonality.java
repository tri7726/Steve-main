package com.steve.ai.personality;

public class JokerPersonality implements PersonalityProfile {

    private final String name;

    public JokerPersonality(String name) {
        this.name = name;
    }

    @Override
    public PersonalityType getType() {
        return PersonalityType.JOKER;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public float getJokeChance() {
        return 0.7f;
    }

    @Override
    public float getProactiveChance() {
        return 0.6f;
    }

    @Override
    public String formatMessage(String rawMessage, RelationshipLevel level) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new IllegalArgumentException("rawMessage must not be null or blank");
        }
        if (level.ordinal() >= RelationshipLevel.FRIEND.ordinal()) {
            return rawMessage + " 😄";
        }
        return rawMessage + " hehe";
    }

    @Override
    public String getGreeting(RelationshipLevel level) {
        return switch (level) {
            case STRANGER -> "Ê, tao là " + name + "!";
            case ACQUAINTANCE -> "Yo " + name + " đây!";
            case FRIEND, BEST_FRIEND -> "Heeey! Tao nhớ mày lắm 😄";
        };
    }

    @Override
    public String getLevelUpMessage(RelationshipLevel newLevel) {
        return switch (newLevel) {
            case ACQUAINTANCE -> "Haha, giờ tao với mày quen rồi đó! 😄";
            case FRIEND -> "Yayyy! Mày là bạn tao rồi nha! 🎉";
            case BEST_FRIEND -> "Woooo! Mày là bestie của tao luôn rồi! 😄🎊";
            default -> "Ê, mình lên level rồi nè! hehe";
        };
    }

    @Override
    public String formatEmotionalMessage(String raw, RelationshipLevel level, EmotionalContext ctx) {
        String base = (raw == null || raw.isBlank()) ? "..." : raw.trim();
        
        if (base.endsWith("!") || base.endsWith("?") || base.endsWith(".") || base.endsWith("😄")) {
            return base;
        }

        java.util.Random rnd = new java.util.Random();
        if (rnd.nextFloat() > 0.4f) return base;

        String particle = switch (ctx) {
            case SCARED -> " ơi trời";
            case TIRED -> " mệt quá";
            case EXCITED -> " yayyy";
            default -> " nha";
        };
        return base + particle;
    }

    @Override
    public String getAddressForm(RelationshipLevel level) {
        return (level == RelationshipLevel.FRIEND || level == RelationshipLevel.BEST_FRIEND) ? "mày" : "bạn";
    }
}
