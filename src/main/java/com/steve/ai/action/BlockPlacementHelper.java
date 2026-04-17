package com.steve.ai.action;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * BlockPlacementHelper — tìm vị trí đặt block hợp lệ cho tất cả action.
 *
 * Tầng 1: Kiểm tra vị trí hợp lệ (air, nền solid, không đè block quan trọng)
 * Tầng 2: Quét spiral từ trong ra ngoài, ưu tiên gần Steve nhất
 * Tầng 3: Logic đặc biệt theo loại block (rương, giường, đuốc)
 */
@SuppressWarnings("null") // Minecraft API (getBlockState, blockPosition, Direction) guaranteed non-null at runtime
public class BlockPlacementHelper {

    /** Block quan trọng không được đặt đè lên */
    private static final java.util.Set<Block> PROTECTED_BLOCKS = java.util.Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL,
        Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
        Blocks.CRAFTING_TABLE, Blocks.ANVIL, Blocks.ENCHANTING_TABLE,
        Blocks.ENDER_CHEST, Blocks.SHULKER_BOX,
        Blocks.WHITE_BED, Blocks.RED_BED, Blocks.BLUE_BED,
        Blocks.GREEN_BED, Blocks.YELLOW_BED, Blocks.ORANGE_BED,
        Blocks.SPAWNER, Blocks.BEDROCK
    );

    /**
     * Tìm vị trí tốt nhất để đặt 1 block thông thường (lò, bàn chế tạo, v.v.)
     * trong bán kính maxRadius block quanh Steve.
     *
     * @return BlockPos hợp lệ, hoặc null nếu không tìm được
     */
    public static BlockPos findPlacementPos(SteveEntity steve, int maxRadius) {
        Level level = steve.level();
        BlockPos center = steve.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();

        // Quét spiral từ trong ra ngoài
        for (int r = 0; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // Chỉ vòng ngoài
                    for (int dy = -1; dy <= 2; dy++) {
                        BlockPos candidate = center.offset(dx, dy, dz);
                        if (isValidPlacement(level, candidate)) {
                            candidates.add(candidate);
                        }
                    }
                }
            }
        }

        // Ưu tiên vị trí gần Steve nhất mà Steve có thể đứng cạnh
        return candidates.stream()
            .filter(pos -> canSteveReach(level, steve, pos))
            .min(Comparator.comparingDouble(pos -> pos.distSqr(center)))
            .orElse(null);
    }

    /**
     * Tìm vị trí đặt rương — không đặt cạnh rương khác (tránh double chest ngoài ý muốn).
     */
    public static BlockPos findChestPlacementPos(SteveEntity steve, int maxRadius) {
        Level level = steve.level();
        BlockPos center = steve.blockPosition();

        for (int r = 0; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    BlockPos candidate = center.offset(dx, 0, dz).above();
                    if (isValidPlacement(level, candidate)
                            && !hasAdjacentChest(level, candidate)
                            && canSteveReach(level, steve, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Tìm vị trí đặt đuốc — ưu tiên tường, fallback về sàn.
     */
    public static BlockPos findTorchPlacementPos(SteveEntity steve, int maxRadius) {
        Level level = steve.level();
        BlockPos center = steve.blockPosition();

        // Ưu tiên 1: đặt trên tường (sáng hơn, không bị giẫm)
        Direction[] horizontals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (int r = 1; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    BlockPos wallCheck = center.offset(dx, 1, dz);
                    for (Direction dir : horizontals) {
                        BlockPos wallPos = wallCheck.relative(dir);
                        if (level.getBlockState(wallPos).isSolidRender(level, wallPos)
                                && level.getBlockState(wallCheck).isAir()) {
                            return wallCheck;
                        }
                    }
                }
            }
        }

        // Fallback: đặt trên sàn
        return findPlacementPos(steve, maxRadius);
    }

    /**
     * Tìm 2 vị trí liên tiếp để đặt giường (cần 2 block thẳng hàng).
     * Trả về [footPos, headPos] hoặc null nếu không tìm được.
     */
    public static BlockPos[] findBedPlacementPos(SteveEntity steve) {
        Level level = steve.level();
        BlockPos center = steve.blockPosition();
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos foot = center.offset(dx, 0, dz).above();
                    for (Direction dir : dirs) {
                        BlockPos head = foot.relative(dir);
                        if (isValidPlacement(level, foot)
                                && isValidPlacement(level, head)
                                && canSteveReach(level, steve, foot)) {
                            return new BlockPos[]{foot, head};
                        }
                    }
                }
            }
        }
        return null;
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    /**
     * Kiểm tra vị trí có thể đặt block không.
     */
    public static boolean isValidPlacement(Level level, BlockPos pos) {
        BlockState atPos = level.getBlockState(pos);
        BlockState below = level.getBlockState(pos.below());

        // Vị trí phải là air hoặc replaceable
        if (!atPos.isAir() && !atPos.canBeReplaced()) return false;

        // Không đặt đè lên block quan trọng
        if (PROTECTED_BLOCKS.contains(atPos.getBlock())) return false;

        // Nền bên dưới phải solid
        if (!below.isSolidRender(level, pos.below())) return false;

        // Không đặt lơ lửng (nền dưới không phải air)
        if (below.isAir()) return false;

        return true;
    }

    /**
     * Kiểm tra Steve có thể đứng cạnh vị trí để tương tác không.
     */
    private static boolean canSteveReach(Level level, SteveEntity steve, BlockPos pos) {
        // Kiểm tra ít nhất 1 trong 4 ô cạnh có thể đứng được
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction dir : dirs) {
            BlockPos standPos = pos.relative(dir);
            BlockState standState = level.getBlockState(standPos);
            BlockState aboveStand = level.getBlockState(standPos.above());
            if (standState.isAir() && aboveStand.isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kiểm tra có rương nào kề cạnh không (tránh tạo double chest ngoài ý muốn).
     */
    private static boolean hasAdjacentChest(Level level, BlockPos pos) {
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction dir : dirs) {
            Block adjacent = level.getBlockState(pos.relative(dir)).getBlock();
            if (adjacent == Blocks.CHEST || adjacent == Blocks.TRAPPED_CHEST) {
                return true;
            }
        }
        return false;
    }
}
