package com.steve.ai.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * ExplorationMemory: ghi nhớ các khu vực (chunk-level) mà Steve đã khám phá.
 *
 * <p>Mỗi "chunk grid" được encode dưới dạng cặp (chunkX, chunkZ) ở scale 32x32 block
 * (nhỏ hơn Minecraft chunk 16x16 để độ phân giải tốt hơn khi tìm hướng mới).
 *
 * <p>Tối đa 200 khu vực được lưu (FIFO — drop khu vực cũ nhất khi đầy).
 * Persist qua NBT.</p>
 */
public class ExplorationMemory {

    private static final int GRID_SIZE  = 32;   // Kích thước mỗi ô grid
    private static final int MAX_GRIDS  = 200;  // Tối đa ô nhớ

    /**
     * Dùng LinkedHashMap như một bounded FIFO set.
     * Key = encoded (gridX, gridZ), Value = dummy Boolean.
     * removeEldestEntry loại ô cũ nhất khi vượt MAX_GRIDS.
     */
    private final java.util.LinkedHashMap<Long, Boolean> exploredGrids =
        new java.util.LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                return size() > MAX_GRIDS;
            }
        };

    // Encode cặp (gridX, gridZ) thành 1 long
    private static long encode(int gx, int gz) {
        return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
    }

    private static int decodeX(long key) { return (int)(key >> 32); }
    private static int decodeZ(long key) { return (int)(key & 0xFFFFFFFFL); }

    private int toGridX(int worldX) { return Math.floorDiv(worldX, GRID_SIZE); }
    private int toGridZ(int worldZ) { return Math.floorDiv(worldZ, GRID_SIZE); }

    /**
     * Đánh dấu khu vực quanh pos (trong radius block) là đã khám phá.
     */
    public void markExplored(BlockPos center, int radius) {
        int gridRadius = Math.max(1, radius / GRID_SIZE);
        int cx = toGridX(center.getX());
        int cz = toGridZ(center.getZ());

        for (int dx = -gridRadius; dx <= gridRadius; dx++) {
            for (int dz = -gridRadius; dz <= gridRadius; dz++) {
                long key = encode(cx + dx, cz + dz);
                exploredGrids.put(key, Boolean.TRUE); // LinkedHashMap auto-evicts old entries
            }
        }
    }

    /**
     * Kiểm tra pos có trong khu vực đã khám phá không.
     */
    public boolean hasExplored(BlockPos pos) {
        return exploredGrids.containsKey(encode(toGridX(pos.getX()), toGridZ(pos.getZ())));
    }

    /**
     * Tìm hướng chưa khám phá gần nhất từ vị trí hiện tại.
     * Quét 8 hướng theo NSEW + chéo, chọn hướng đầu tiên không thuộc exploredGrids.
     *
     * @param currentPos  Vị trí hiện tại của Steve
     * @param searchRange Khoảng cách (block) mỗi hướng cần check
     * @return BlockPos của trung tâm ô grid chưa khám, hoặc null nếu tất cả đã khám
     */
    public BlockPos getUnexploredDirection(BlockPos currentPos, int searchRange) {
        int cx = toGridX(currentPos.getX());
        int cz = toGridZ(currentPos.getZ());
        int steps = Math.max(1, searchRange / GRID_SIZE);

        // 8 hướng: N, S, E, W, NE, NW, SE, SW
        int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}, {1,1}, {-1,1}, {1,-1}, {-1,-1}};

        for (int[] dir : dirs) {
            for (int dist = 1; dist <= steps; dist++) {
                int gx = cx + dir[0] * dist;
                int gz = cz + dir[1] * dist;
                if (!exploredGrids.containsKey(encode(gx, gz))) {
                    // Trả về tọa độ thế giới ở giữa ô grid này
                    return new BlockPos(gx * GRID_SIZE + GRID_SIZE / 2,
                                       currentPos.getY(),
                                       gz * GRID_SIZE + GRID_SIZE / 2);
                }
            }
        }
        return null; // Tất cả đã khám phá trong range
    }

    public int exploredCount() {
        return exploredGrids.size();
    }

    // ── NBT Serialization ─────────────────────────────────────────────────────

    public void saveToNBT(CompoundTag tag) {
        ListTag list = new ListTag();
        for (long key : exploredGrids.keySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("gx", decodeX(key));
            entry.putInt("gz", decodeZ(key));
            list.add(entry);
        }
        tag.put("ExploredGrids", list);
    }

    public void loadFromNBT(CompoundTag tag) {
        exploredGrids.clear();
        if (tag.contains("ExploredGrids")) {
            ListTag list = tag.getList("ExploredGrids", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                exploredGrids.put(encode(entry.getInt("gx"), entry.getInt("gz")), Boolean.TRUE);
            }
        }
    }
}
