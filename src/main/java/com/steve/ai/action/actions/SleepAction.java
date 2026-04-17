package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.EnumSet;

/**
 * SleepAction: tìm giường gần đó và ngủ để skip đêm.
 * Nếu không có giường, đặt giường từ inventory (nếu có).
 * Chỉ hoạt động ban đêm (dayTime >= 12542).
 */
@SuppressWarnings("null")
public class SleepAction extends BaseAction {
    private int ticksRunning = 0;
    private static final int MAX_TICKS = 200; // 10 giây timeout
    private BlockPos bedPos = null;

    public SleepAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        // Chỉ ngủ ban đêm
        long dayTime = steve.level().getDayTime() % 24000;
        if (dayTime < 12542) {
            result = ActionResult.failure("Ban ngay roi, khong can ngu!");
            return;
        }

        // Nếu có waypoint "home" và đang ở xa → về nhà trước
        com.steve.ai.memory.WaypointMemory waypoints = steve.getMemory().getWaypoints();
        if (waypoints.has("home")) {
            waypoints.get("home").ifPresent(homePos -> {
                double distSq = steve.blockPosition().distSqr(homePos);
                if (distSq > 20 * 20) { // Xa hơn 20 block → pathfind về nhà trước
                    steve.getNavigation().moveTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5, 1.2);
                    steve.sendChatMessage("Về nhà ngủ thôi!");
                    SteveMod.LOGGER.info("Steve '{}' navigating home before sleeping", steve.getSteveName());
                }
            });
        }

        // Tìm giường gần đó
        bedPos = findNearestBed();

        if (bedPos == null) {
            // Thử đặt giường từ inventory
            bedPos = tryPlaceBedFromInventory();
        }

        if (bedPos == null) {
            result = ActionResult.failure("Khong co giuong de ngu!");
            steve.sendChatMessage("Tao khong co giuong, khong ngu duoc!");
            return;
        }

        // Di chuyển đến giường
        steve.getNavigation().moveTo(bedPos.getX() + 0.5, bedPos.getY(), bedPos.getZ() + 0.5, 1.2);
        steve.sendChatMessage("Di ngu thoi, dem roi!");
        SteveMod.LOGGER.info("Steve '{}' going to sleep at {}", steve.getSteveName(), bedPos);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Sleep timeout");
            return;
        }

        if (bedPos == null) return;

        // Kiểm tra đã đến giường chưa
        double dist = steve.distanceToSqr(bedPos.getX() + 0.5, bedPos.getY(), bedPos.getZ() + 0.5);
        if (dist > 4.0) return; // Chưa đến nơi

        // Thử ngủ — trong Minecraft, mob không thể dùng giường trực tiếp
        // Nên dùng cách: nếu tất cả player đang ngủ hoặc không có player, skip đêm
        if (steve.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Kiểm tra xem có thể skip đêm không (tất cả player đang ngủ)
            boolean canSkip = serverLevel.players().stream()
                .allMatch(p -> p.isSleeping() || p.isSpectator());

            if (canSkip || serverLevel.players().isEmpty()) {
                serverLevel.setDayTime(24000); // Skip sang ngày
                steve.sendChatMessage("Ngu ngon! Sang roi!");
                result = ActionResult.success("Slept through the night");
                SteveMod.LOGGER.info("Steve '{}' skipped night", steve.getSteveName());
            } else {
                // Chờ player ngủ
                if (ticksRunning % 40 == 0) {
                    steve.sendChatMessage("Cho moi nguoi ngu da...");
                }
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Sleep (skip night)";
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION);
    }

    private BlockPos findNearestBed() {
        BlockPos center = steve.blockPosition();
        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        BlockState state = steve.level().getBlockState(p);
                        if (state.getBlock() instanceof BedBlock) {
                            // Lấy phần head của giường
                            if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
                                return p;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private BlockPos tryPlaceBedFromInventory() {
        com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();

        net.minecraft.world.item.Item[] beds = {
            net.minecraft.world.item.Items.WHITE_BED,
            net.minecraft.world.item.Items.RED_BED,
            net.minecraft.world.item.Items.BLUE_BED,
            net.minecraft.world.item.Items.GREEN_BED,
            net.minecraft.world.item.Items.YELLOW_BED,
        };

        for (net.minecraft.world.item.Item bed : beds) {
            if (inv.hasItem(bed, 1)) {
                // Dùng BlockPlacementHelper để tìm 2 vị trí liên tiếp hợp lệ
                BlockPos[] bedPositions = com.steve.ai.action.BlockPlacementHelper.findBedPlacementPos(steve);
                if (bedPositions == null) return null;

                BlockPos foot = bedPositions[0];
                BlockPos head = bedPositions[1];
                net.minecraft.core.Direction dir = net.minecraft.core.Direction.getNearest(
                    head.getX() - foot.getX(), 0, head.getZ() - foot.getZ()
                );

                net.minecraft.world.level.block.Block bedBlock = getBedBlock(bed);
                if (bedBlock != null) {
                    steve.level().setBlock(foot, bedBlock.defaultBlockState()
                        .setValue(BedBlock.FACING, dir.getOpposite())
                        .setValue(BedBlock.PART, BedPart.FOOT), 3);
                    steve.level().setBlock(head, bedBlock.defaultBlockState()
                        .setValue(BedBlock.FACING, dir.getOpposite())
                        .setValue(BedBlock.PART, BedPart.HEAD), 3);
                    inv.removeItem(bed, 1);
                    SteveMod.LOGGER.info("Steve '{}' placed bed at {}", steve.getSteveName(), foot);
                    return head;
                }
            }
        }
        return null;
    }

    private net.minecraft.world.level.block.Block getBedBlock(net.minecraft.world.item.Item item) {
        if (item == net.minecraft.world.item.Items.WHITE_BED) return Blocks.WHITE_BED;
        if (item == net.minecraft.world.item.Items.RED_BED) return Blocks.RED_BED;
        if (item == net.minecraft.world.item.Items.BLUE_BED) return Blocks.BLUE_BED;
        if (item == net.minecraft.world.item.Items.GREEN_BED) return Blocks.GREEN_BED;
        if (item == net.minecraft.world.item.Items.YELLOW_BED) return Blocks.YELLOW_BED;
        return null;
    }
}
