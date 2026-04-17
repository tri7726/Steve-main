package com.steve.ai.llm;

import com.steve.ai.agentic.AgentLoopContext;
import com.steve.ai.agentic.SubGoal;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentObservation: captures a real-time snapshot of the world state around Steve.
 * Built after every Action completes and fed back to the LLM so it can react.
 */
@SuppressWarnings("null")
public class AgentObservation {

    public final String steveName;
    public final String position;
    public final String lastActionResult;   // What just finished
    public final String nearbyBlocks;
    public final String nearbyEntities;
    public final String nearbyPlayers;
    public final String biome;
    public final String inventorySummary;
    public final boolean isDaytime;
    public final int lightLevel;
    public final float playerHealth;    // 0-20
    public final float playerFood;      // 0-20
    public final float steveHealth;     // 0-20
    public final int   steveHunger;     // 0-20 (simulated Steve hunger)
    public final float mainHandDurability; // 0.0-1.0 (1.0 = new)
    public final int hostileCount;      // số hostile entity gần đó
    public final String personalityType; // NEW: Steve's personality type

    // Agent loop context fields
    public final String currentSubGoal;     // nullable — description of the SubGoal currently being executed
    public final String scratchpadSummary;  // last 3 notes from WorkingMemory (empty string if none)
    public final int totalStepsInLoop;      // total steps executed in current loop (0 if no active loop)

    private AgentObservation(Builder b) {
        this.steveName       = b.steveName;
        this.position        = b.position;
        this.lastActionResult = b.lastActionResult;
        this.nearbyBlocks    = b.nearbyBlocks;
        this.nearbyEntities  = b.nearbyEntities;
        this.nearbyPlayers   = b.nearbyPlayers;
        this.biome           = b.biome;
        this.inventorySummary = b.inventorySummary;
        this.isDaytime       = b.isDaytime;
        this.lightLevel      = b.lightLevel;
        this.playerHealth    = b.playerHealth;
        this.playerFood      = b.playerFood;
        this.steveHealth     = b.steveHealth;
        this.steveHunger     = b.steveHunger;
        this.mainHandDurability = b.mainHandDurability;
        this.hostileCount    = b.hostileCount;
        this.personalityType = b.personalityType;
        this.currentSubGoal  = b.currentSubGoal;
        this.scratchpadSummary = b.scratchpadSummary;
        this.totalStepsInLoop  = b.totalStepsInLoop;
    }

    public float getPlayerHealth()      { return playerHealth; }
    public float getPlayerFoodLevel()   { return playerFood; }
    public float getSteveHealth()       { return steveHealth; }
    public int   getSteveHunger()       { return steveHunger; }
    public float getMainHandDurability() { return mainHandDurability; }
    public int countHostileEntities()   { return hostileCount; }

    /**
     * Build a full observation from the current game state.
     */
    public static AgentObservation capture(SteveEntity steve, String lastActionResult) {
        return captureWithContext(steve, lastActionResult, null);
    }

    /**
     * Build a full observation from the current game state, enriched with AgentLoopContext.
     * When context is not null: populates currentSubGoal, scratchpadSummary, totalStepsInLoop.
     * When context is null: currentSubGoal=null, scratchpadSummary="", totalStepsInLoop=0.
     */
    public static AgentObservation captureWithContext(SteveEntity steve, String lastActionResult, AgentLoopContext context) {
        WorldKnowledge wk = new WorldKnowledge(steve);
        BlockPos pos       = steve.blockPosition();

        List<String> invItems = steve.getMemory().getInventory().getSummary();
        String invSummary = invItems.isEmpty() ? "empty" : String.join(", ", invItems);

        int light = steve.level().getBrightness(
                net.minecraft.world.level.LightLayer.BLOCK, pos);
        long time = steve.level().getDayTime() % 24000;
        boolean day = time < 13000;

        // Nearby hostile/peaceful count
        AABB box = steve.getBoundingBox().inflate(20.0);
        List<Entity> nearby = steve.level().getEntities(steve, box);
        List<String> entityDesc = new ArrayList<>();
        int hostiles = 0;
        for (Entity e : nearby) {
            if (e instanceof LivingEntity le && !(le instanceof SteveEntity)) {
                entityDesc.add(e.getType().toShortString() + "@" + (int)le.distanceTo(steve) + "m");
                if (e instanceof net.minecraft.world.entity.monster.Monster) {
                    hostiles++;
                }
            }
        }

        List<String> playerNames = new ArrayList<>();
        for (Player p : steve.level().players()) {
            playerNames.add(p.getName().getString() + "@" + (int)p.distanceTo(steve) + "m");
        }

        // Nearest player health/food
        float nearestPlayerHealth = 20f;
        float nearestPlayerFood = 20f;
        Player nearestPlayer = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player p : steve.level().players()) {
            double d = p.distanceToSqr(steve);
            if (d < nearestDist) {
                nearestDist = d;
                nearestPlayer = p;
            }
        }
        if (nearestPlayer != null) {
            nearestPlayerHealth = nearestPlayer.getHealth();
            nearestPlayerFood = nearestPlayer.getFoodData().getFoodLevel();
        }

        // Populate agent loop context fields
        String currentSubGoal = null;
        String scratchpadSummary = "";
        int totalStepsInLoop = 0;
        if (context != null) {
            SubGoal sg = context.memory.getCurrentSubGoal();
            currentSubGoal = (sg != null) ? sg.description() : null;
            scratchpadSummary = context.memory.getScratchpadSummary();
            totalStepsInLoop = context.totalSteps;
        }

        return new Builder()
                .steveName(steve.getSteveName())
                .position(String.format("[%d,%d,%d]", pos.getX(), pos.getY(), pos.getZ()))
                .lastActionResult(lastActionResult)
                .nearbyBlocks(wk.getNearbyBlocksSummary())
                .nearbyEntities(entityDesc.isEmpty() ? "none" : String.join(", ", entityDesc))
                .nearbyPlayers(playerNames.isEmpty() ? "none" : String.join(", ", playerNames))
                .biome(wk.getBiomeName())
                .inventorySummary(invSummary)
                .isDaytime(day)
                .lightLevel(light)
                .playerHealth(nearestPlayerHealth)
                .playerFood(nearestPlayerFood)
                .steveHealth(steve.getHealth())
                .steveHunger(steve.getSteveHunger())
                .mainHandDurability(com.steve.ai.memory.SteveEquipmentTracker.getDurabilityFraction(steve.getMainHandItem()))
                .hostileCount(hostiles)
                .personalityType(steve.getPersonality() != null ? steve.getPersonality().getType().name() : "SERIOUS")
                .currentSubGoal(currentSubGoal)
                .scratchpadSummary(scratchpadSummary)
                .totalStepsInLoop(totalStepsInLoop)
                .build();
    }

    /** Format as readable text for the LLM prompt */
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        // Tính % durability cho main hand
        int durPct = Math.round(mainHandDurability * 100);
        sb.append("=== CURRENT OBSERVATION ===\n")
          .append("Position: ").append(position).append("\n")
          .append("Time: ").append(isDaytime ? "Day" : "Night").append(" | Light: ").append(lightLevel).append("\n")
          .append("Biome: ").append(biome).append("\n")
          .append("HP: ").append(String.format("%.0f", steveHealth)).append("/20")
          .append(" | Hunger: ").append(steveHunger).append("/20\n")
          .append("Tool: ").append(durPct).append("% durability\n")
          .append("Nearby Blocks: ").append(nearbyBlocks).append("\n")
          .append("Nearby Entities: ").append(nearbyEntities).append("\n")
          .append("Nearby Players: ").append(nearbyPlayers).append("\n")
          .append("Steve Inventory: ").append(inventorySummary).append("\n")
          .append("Last Action Result: ").append(lastActionResult).append("\n");

        if (currentSubGoal != null) {
            sb.append("Current Sub-Goal: ").append(currentSubGoal).append("\n");
        }
        if (scratchpadSummary != null && !scratchpadSummary.isEmpty()) {
            sb.append("Scratchpad Notes:\n").append(scratchpadSummary).append("\n");
        }
        if (totalStepsInLoop > 0) {
            sb.append("Steps in Loop: ").append(totalStepsInLoop).append("\n");
        }

        return sb.toString();
    }

    // ── Builder ───────────────────────────────────────────────────────────────
    public static class Builder {
        String steveName, position, lastActionResult, nearbyBlocks;
        String nearbyEntities, nearbyPlayers, biome, inventorySummary;
        boolean isDaytime;
        int lightLevel;
        float playerHealth = 20f;
        float playerFood = 20f;
        float steveHealth = 20f;
        int   steveHunger = 20;
        float mainHandDurability = 1.0f;
        int hostileCount = 0;
        String personalityType = "SERIOUS";
        String currentSubGoal = null;
        String scratchpadSummary = "";
        int totalStepsInLoop = 0;

        public Builder steveName(String v)         { steveName = v; return this; }
        public Builder position(String v)           { position = v; return this; }
        public Builder lastActionResult(String v)   { lastActionResult = v; return this; }
        public Builder nearbyBlocks(String v)       { nearbyBlocks = v; return this; }
        public Builder nearbyEntities(String v)     { nearbyEntities = v; return this; }
        public Builder nearbyPlayers(String v)      { nearbyPlayers = v; return this; }
        public Builder biome(String v)              { biome = v; return this; }
        public Builder inventorySummary(String v)   { inventorySummary = v; return this; }
        public Builder isDaytime(boolean v)         { isDaytime = v; return this; }
        public Builder lightLevel(int v)            { lightLevel = v; return this; }
        public Builder playerHealth(float v)        { playerHealth = v; return this; }
        public Builder playerFood(float v)          { playerFood = v; return this; }
        public Builder steveHealth(float v)         { steveHealth = v; return this; }
        public Builder steveHunger(int v)           { steveHunger = v; return this; }
        public Builder mainHandDurability(float v)  { mainHandDurability = v; return this; }
        public Builder hostileCount(int v)          { hostileCount = v; return this; }
        public Builder personalityType(String v)    { personalityType = v; return this; }
        public Builder currentSubGoal(String v)     { currentSubGoal = v; return this; }
        public Builder scratchpadSummary(String v)  { scratchpadSummary = v != null ? v : ""; return this; }
        public Builder totalStepsInLoop(int v)      { totalStepsInLoop = v; return this; }
        public AgentObservation build()             { return new AgentObservation(this); }
    }
}
