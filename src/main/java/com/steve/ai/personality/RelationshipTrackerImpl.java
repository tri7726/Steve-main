package com.steve.ai.personality;

import net.minecraft.nbt.CompoundTag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

@SuppressWarnings("null")
public class RelationshipTrackerImpl implements RelationshipTracker {

    private static final Logger LOGGER = LogManager.getLogger(RelationshipTrackerImpl.class);

    private final Map<String, Integer> xpMap = new HashMap<>();
    private final Map<String, RelationshipLevel> levelMap = new HashMap<>();
    private final PersonalityProfile personality;
    private final Consumer<String> chatSender;

    public RelationshipTrackerImpl(PersonalityProfile personality, Consumer<String> chatSender) {
        this.personality = personality;
        this.chatSender = chatSender;
    }

    @Override
    public void addExperience(String playerName, ExperienceSource source) {
        int currentXp = xpMap.getOrDefault(playerName, 0);
        int newXp = currentXp + source.xpAmount;
        xpMap.put(playerName, newXp);

        RelationshipLevel oldLevel = levelMap.getOrDefault(playerName, RelationshipLevel.STRANGER);
        RelationshipLevel newLevel = calculateLevel(newXp);

        if (newLevel != oldLevel) {
            levelMap.put(playerName, newLevel);
            chatSender.accept(personality.getLevelUpMessage(newLevel));
        }
    }

    @Override
    public RelationshipLevel getLevel(String playerName) {
        return levelMap.getOrDefault(playerName, RelationshipLevel.STRANGER);
    }

    @Override
    public int getExperiencePoints(String playerName) {
        return xpMap.getOrDefault(playerName, 0);
    }

    @Override
    public boolean canUnlockNextLevel(String playerName) {
        RelationshipLevel current = getLevel(playerName);
        int xp = getExperiencePoints(playerName);
        return switch (current) {
            case STRANGER -> xp < RelationshipLevel.ACQUAINTANCE.minXp;
            case ACQUAINTANCE -> xp < RelationshipLevel.FRIEND.minXp;
            case FRIEND -> xp < RelationshipLevel.BEST_FRIEND.minXp;
            case BEST_FRIEND -> false;
        };
    }

    @Override
    public String getRelationshipSummary(String playerName) {
        RelationshipLevel level = getLevel(playerName);
        int xp = getExperiencePoints(playerName);
        return "Relationship with " + playerName + ": " + level.name() + " (" + xp + " XP)";
    }

    @Override
    public void saveToNBT(CompoundTag tag) {
        CompoundTag xpTag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : xpMap.entrySet()) {
            xpTag.putInt(entry.getKey(), entry.getValue());
        }
        tag.put("relationshipXp", xpTag);

        CompoundTag levelTag = new CompoundTag();
        for (Map.Entry<String, RelationshipLevel> entry : levelMap.entrySet()) {
            levelTag.putString(entry.getKey(), entry.getValue().name());
        }
        tag.put("relationshipLevels", levelTag);
    }

    @Override
    public void loadFromNBT(CompoundTag tag) {
        try {
            xpMap.clear();
            levelMap.clear();

            CompoundTag xpTag = tag.getCompound("relationshipXp");
            for (String key : xpTag.getAllKeys()) {
                xpMap.put(key, xpTag.getInt(key));
            }

            CompoundTag levelTag = tag.getCompound("relationshipLevels");
            for (String key : levelTag.getAllKeys()) {
                levelMap.put(key, RelationshipLevel.valueOf(levelTag.getString(key)));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load relationship data from NBT, resetting to STRANGER: {}", e.getMessage());
            xpMap.clear();
            levelMap.clear();
        }
    }

    private RelationshipLevel calculateLevel(int totalXp) {
        if (totalXp >= 700) return RelationshipLevel.BEST_FRIEND;
        if (totalXp >= 300) return RelationshipLevel.FRIEND;
        if (totalXp >= 100) return RelationshipLevel.ACQUAINTANCE;
        return RelationshipLevel.STRANGER;
    }
}
