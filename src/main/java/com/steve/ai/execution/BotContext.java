package com.steve.ai.execution;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Snapshot trạng thái bot tại thời điểm skill được thực thi.
 * Immutable — tạo một lần, truyền vào skill.
 */
@SuppressWarnings("null")
public class BotContext {

    public final String steveName;
    public final double x, y, z;
    public final float health;
    public final int hunger;
    public final String biome;
    public final String dimension;
    public final boolean isDay;
    public final boolean isRaining;
    public final SteveInventory inventory;
    public final List<String> nearbyEntityTypes; // toShortString() của mỗi entity
    public final long worldTick;

    private BotContext(Builder b) {
        this.steveName        = b.steveName;
        this.x                = b.x;
        this.y                = b.y;
        this.z                = b.z;
        this.health           = b.health;
        this.hunger           = b.hunger;
        this.biome            = b.biome;
        this.dimension        = b.dimension;
        this.isDay            = b.isDay;
        this.isRaining        = b.isRaining;
        this.inventory        = b.inventory;
        this.nearbyEntityTypes = b.nearbyEntityTypes;
        this.worldTick        = b.worldTick;
    }

    /**
     * Tạo BotContext từ SteveEntity hiện tại.
     */
    public static BotContext capture(SteveEntity steve) {
        BlockPos pos = steve.blockPosition();
        long time    = steve.level().getDayTime() % 24000;

        AABB box = steve.getBoundingBox().inflate(16);
        List<String> nearby = steve.level().getEntities(steve, box).stream()
            .filter(e -> e instanceof net.minecraft.world.entity.LivingEntity)
            .map(e -> e.getType().toShortString())
            .toList();

        return new Builder()
            .steveName(steve.getSteveName())
            .x(pos.getX()).y(pos.getY()).z(pos.getZ())
            .health(steve.getHealth())
            .hunger(steve.getSteveHunger())
            .biome(new com.steve.ai.memory.WorldKnowledge(steve).getBiomeName())
            .dimension(steve.level().dimension().location().getPath())
            .isDay(time < 13000)
            .isRaining(steve.level().isRaining())
            .inventory(steve.getMemory().getInventory())
            .nearbyEntityTypes(nearby)
            .worldTick(steve.level().getGameTime())
            .build();
    }

    /** Format ngắn gọn để inject vào LLM prompt */
    public String toPromptString() {
        return String.format(
            "Pos:[%.0f,%.0f,%.0f] HP:%.0f/20 Hunger:%d/20 Biome:%s Dim:%s Time:%s Rain:%s Nearby:%s",
            x, y, z, health, hunger, biome, dimension,
            isDay ? "Day" : "Night", isRaining ? "Yes" : "No",
            nearbyEntityTypes.isEmpty() ? "none" : String.join(",", nearbyEntityTypes.subList(0, Math.min(5, nearbyEntityTypes.size())))
        );
    }

    public static class Builder {
        private String steveName = "Steve";
        private double x, y, z;
        private float health = 20f;
        private int hunger = 20;
        private String biome = "unknown";
        private String dimension = "overworld";
        private boolean isDay = true;
        private boolean isRaining = false;
        private SteveInventory inventory;
        private List<String> nearbyEntityTypes = List.of();
        private long worldTick = 0;

        public Builder steveName(String v)              { this.steveName = v; return this; }
        public Builder x(double v)                      { this.x = v; return this; }
        public Builder y(double v)                      { this.y = v; return this; }
        public Builder z(double v)                      { this.z = v; return this; }
        public Builder health(float v)                  { this.health = v; return this; }
        public Builder hunger(int v)                    { this.hunger = v; return this; }
        public Builder biome(String v)                  { this.biome = v; return this; }
        public Builder dimension(String v)              { this.dimension = v; return this; }
        public Builder isDay(boolean v)                 { this.isDay = v; return this; }
        public Builder isRaining(boolean v)             { this.isRaining = v; return this; }
        public Builder inventory(SteveInventory v)      { this.inventory = v; return this; }
        public Builder nearbyEntityTypes(List<String> v){ this.nearbyEntityTypes = v; return this; }
        public Builder worldTick(long v)                { this.worldTick = v; return this; }
        public BotContext build()                       { return new BotContext(this); }
    }
}
