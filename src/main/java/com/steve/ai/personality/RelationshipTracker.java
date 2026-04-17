package com.steve.ai.personality;

public interface RelationshipTracker {
    RelationshipLevel getLevel(String playerName);
    int getExperiencePoints(String playerName);
    void addExperience(String playerName, ExperienceSource source);
    boolean canUnlockNextLevel(String playerName);
    String getRelationshipSummary(String playerName);
    void saveToNBT(net.minecraft.nbt.CompoundTag tag);
    void loadFromNBT(net.minecraft.nbt.CompoundTag tag);
}
