package com.steve.ai.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * WaypointMemory: lưu vị trí quan trọng (nhà, mỏ, làng, v.v.)
 * Persist qua NBT, tối đa 20 waypoint.
 */
@SuppressWarnings("null")
public class WaypointMemory {
    private final LinkedHashMap<String, WaypointMetadata> waypoints = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, WaypointMetadata> eldest) {
            return size() > 20;
        }
    };

    /** Lưu waypoint với đầy đủ metadata */
    public void save(String label, BlockPos pos, String dimension, String biome) {
        String key = label.toLowerCase();
        String description = "[WAYPOINT] " + key + " at [" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "] in " + dimension;
        WaypointMetadata metadata = new WaypointMetadata(key, pos, dimension, biome, description, System.currentTimeMillis());
        waypoints.put(key, metadata);
    }

    /** Lưu waypoint với tên label (tự động lowercase) — backward compat */
    public void save(String label, BlockPos pos) {
        save(label, pos, "unknown", "unknown");
    }

    /** Lấy BlockPos theo tên (backward compat) */
    public Optional<BlockPos> get(String label) {
        WaypointMetadata meta = waypoints.get(label.toLowerCase());
        return meta != null ? Optional.of(meta.pos()) : Optional.empty();
    }

    /** Lấy WaypointMetadata theo tên */
    public Optional<WaypointMetadata> getMetadata(String label) {
        return Optional.ofNullable(waypoints.get(label.toLowerCase()));
    }

    /** Xóa waypoint */
    public void remove(String label) {
        waypoints.remove(label.toLowerCase());
    }

    public boolean has(String label) {
        return waypoints.containsKey(label.toLowerCase());
    }

    /** Tóm tắt tất cả waypoint để inject vào prompt */
    public String getSummary() {
        if (waypoints.isEmpty()) return "[none]";
        StringBuilder sb = new StringBuilder();
        waypoints.forEach((label, meta) -> {
            BlockPos pos = meta.pos();
            sb.append(label).append("=[")
              .append(pos.getX()).append(",")
              .append(pos.getY()).append(",")
              .append(pos.getZ()).append("]");
            if (!"unknown".equals(meta.dimension())) {
                sb.append("(").append(meta.dimension()).append(")");
            }
            sb.append(" ");
        });
        return sb.toString().trim();
    }

    public void saveToNBT(CompoundTag tag) {
        ListTag list = new ListTag();
        waypoints.forEach((label, meta) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("label", meta.label());
            entry.putInt("x", meta.pos().getX());
            entry.putInt("y", meta.pos().getY());
            entry.putInt("z", meta.pos().getZ());
            entry.putString("dimension", meta.dimension());
            entry.putString("biome", meta.biome());
            entry.putString("description", meta.description());
            entry.putLong("savedAt", meta.savedAt());
            list.add(entry);
        });
        tag.put("Waypoints", list);
    }

    public void loadFromNBT(CompoundTag tag) {
        waypoints.clear();
        if (tag.contains("Waypoints")) {
            ListTag list = tag.getList("Waypoints", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                String label = entry.getString("label");
                BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
                // Legacy entries may only have x/y/z — default the rest
                String dimension = entry.contains("dimension") ? entry.getString("dimension") : "unknown";
                String biome = entry.contains("biome") ? entry.getString("biome") : "unknown";
                String description = entry.contains("description") ? entry.getString("description") : "";
                long savedAt = entry.contains("savedAt") ? entry.getLong("savedAt") : 0L;
                waypoints.put(label, new WaypointMetadata(label, pos, dimension, biome, description, savedAt));
            }
        }
    }
}
