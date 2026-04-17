package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.BlockPlacementHelper;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.Objects;

/**
 * DimensionAction: Steve xây cổng Nether và di chuyển giữa các chiều không gian.
 */
@SuppressWarnings("null") // Minecraft API (getBlockState, setBlock, blockPosition) guaranteed non-null at runtime
public class DimensionAction extends BaseAction {
    private String mode; // "build", "travel"
    private int ticksRunning;
    private static final int MAX_TICKS = 600;

    public DimensionAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        mode = task.getStringParameter("mode", "build");
        ticksRunning = 0;
        SteveInventory inv = steve.getMemory().getInventory();

        if ("build".equals(mode)) {
            // 1. Kiểm tra tài nguyên (10 Obsidian + Flint and Steel)
            if (!inv.hasItem(Items.OBSIDIAN, 10)) {
                result = ActionResult.failure("Thiếu Obsidian để xây cổng!");
                return;
            }
            if (!inv.hasItem(Items.FLINT_AND_STEEL, 1)) {
                result = ActionResult.failure("Thiếu Bật lửa để kích hoạt cổng!");
                return;
            }

            // 2. Tìm vị trí phẳng
            BlockPos base = BlockPlacementHelper.findPlacementPos(steve, 10);
            if (base == null) base = Objects.requireNonNull(steve.blockPosition().north(5));

            // 3. Xây khung 4x5 (tối giản 10 block)
            buildPortalFrame(base);
            inv.removeItem(Items.OBSIDIAN, 10);

            // 4. Kích hoạt
            BlockPos innerPos = base.offset(1, 1, 0);
            steve.level().setBlock(innerPos, Blocks.NETHER_PORTAL.defaultBlockState(), 3);
            steve.swing(InteractionHand.MAIN_HAND, true);
            
            steve.sendChatMessage("Cổng Địa Ngục đã sẵn sàng! Chuẩn bị thám hiểm thôi.");
            SteveMod.LOGGER.info("Steve '{}' built and lit a Nether portal at {}", steve.getSteveName(), base);
            result = ActionResult.success("Built portal at " + base);

        } else if ("travel".equals(mode)) {
            // Tìm cổng gần nhất
            BlockPos portal = findPortalBlock();
            if (portal != null) {
                steve.getNavigation().moveTo(portal.getX(), portal.getY(), portal.getZ(), 1.0);
                steve.sendChatMessage("Đang đi vào cổng để đổi gió tí...");
                // Note: Logic teleport thực sự được xử lý bởi vanilla khi entity ở trong Portal block
                result = ActionResult.success("Traveling to dimension");
            } else {
                result = ActionResult.failure("Không tìm thấy cổng nào gần đây!");
            }
        }
    }

    private void buildPortalFrame(BlockPos base) {
        // Khung 4x5 rỗng 4 góc (10 block obsidian)
        // Dưới (2)
        steve.level().setBlock(base.offset(1, 0, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        steve.level().setBlock(base.offset(2, 0, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        // Trái (3)
        steve.level().setBlock(base.offset(0, 1, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        steve.level().setBlock(base.offset(0, 2, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        steve.level().setBlock(base.offset(0, 3, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        // Phải (3)
        steve.level().setBlock(base.offset(3, 1, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        steve.level().setBlock(base.offset(3, 2, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        steve.level().setBlock(base.offset(3, 3, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        // Trên (2)
        steve.level().setBlock(base.offset(1, 4, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        steve.level().setBlock(base.offset(2, 4, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
    }

    private BlockPos findPortalBlock() {
        BlockPos center = steve.blockPosition();
        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        var state = steve.level().getBlockState(p);
                        if (state != null && state.getBlock() == Blocks.NETHER_PORTAL) return p;
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        // Timeout guard: if travelling to a portal takes too long, abort
        if (ticksRunning >= MAX_TICKS) {
            result = ActionResult.failure("Timed out travelling to portal after " + (MAX_TICKS / 20) + "s.");
            steve.sendChatMessage("Tao không tìm thấy cổng dịch chuyển.");
            steve.getNavigation().stop();
        }
    }
    @Override protected void onCancel() { steve.getNavigation().stop(); }
    @Override public String getDescription() { return "Dimension Action: " + mode; }
    @Override public EnumSet<ActionSlot> getRequiredSlots() { return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION); }
}
