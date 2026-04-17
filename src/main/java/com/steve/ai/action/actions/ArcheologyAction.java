package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * ArcheologyAction: Scans for suspicious sand/gravel and brushes them.
 * 
 * <p>Replaces the incorrect reuse of FarmingAction for archeology tasks.</p>
 */
@SuppressWarnings("null") // Minecraft API (getBlockState, blockPosition, level) guaranteed non-null at runtime
public class ArcheologyAction extends BaseAction {
    private int radius;
    private List<BlockPos> targets;
    private int targetIndex;
    private int brushTicks;
    private static final int MAX_TICKS = 12000;
    private int totalTicks;

    public ArcheologyAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        radius = task.getIntParameter("radius", 16);
        targets = findSuspiciousBlocks();
        targetIndex = 0;
        brushTicks = 0;
        totalTicks = 0;

        if (targets.isEmpty()) {
            result = ActionResult.success("No suspicious blocks found nearby");
            return;
        }

        steve.sendChatMessage("Tìm thấy " + targets.size() + " khối khả nghi, để tao đi khai quật xem!");
        equipBrush();
    }

    @Override
    protected void onTick() {
        totalTicks++;
        if (totalTicks > MAX_TICKS) {
            result = ActionResult.failure("Archeology timeout");
            return;
        }

        if (targetIndex >= targets.size()) {
            result = ActionResult.success("Khai quật xong " + targetIndex + " khối!");
            return;
        }

        BlockPos pos = targets.get(targetIndex);
        double dist = steve.blockPosition().distSqr(pos);

        // Within 3 blocks for interaction
        if (dist > 9) {
            if (totalTicks % 20 == 0) {
                steve.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.2);
            }
            brushTicks = 0;
        } else {
            // Reached target, start or continue brushing
            steve.getNavigation().stop();
            brushTicks++;
            
            // Look at block
            steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

            // Simulate brushing animation
            if (brushTicks % 10 == 0) {
                steve.swing(InteractionHand.MAIN_HAND, true);
            }

            if (brushTicks >= 100) { // Brush for 5 seconds
                brushBlock(pos);
                targetIndex++;
                brushTicks = 0;
                
                if (targetIndex < targets.size()) {
                    steve.sendChatMessage("Được rồi, khối tiếp theo...");
                }
            }
        }
    }

    private void brushBlock(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);
        Block block = state.getBlock();
        
        // Archeology brushing: replace block and drop reward
        if (block == Blocks.SUSPICIOUS_SAND || block == Blocks.SUSPICIOUS_GRAVEL) {
            Block regular = (block == Blocks.SUSPICIOUS_SAND) ? Blocks.SAND : Blocks.GRAVEL;
            steve.level().setBlock(pos, regular.defaultBlockState(), 3);
            
            // Simulated treasure drop
            dropTreasure(pos);
            steve.pickupNearbyItems(3.0);
            
            SteveMod.LOGGER.info("Steve '{}' excavated suspicious block at {}", steve.getSteveName(), pos);
        }
    }

    private void dropTreasure(BlockPos pos) {
        // Random loot from archeology pool
        net.minecraft.world.item.Item[] loot = {
            Items.EMERALD, Items.DIAMOND, Items.GOLD_NUGGET, Items.IRON_NUGGET, Items.COAL,
            Items.RAW_GOLD, Items.RAW_IRON, Items.STICK
        };
        net.minecraft.world.item.Item item = loot[steve.getRandom().nextInt(loot.length)];
        steve.spawnAtLocation(new net.minecraft.world.item.ItemStack(item));
    }

    private List<BlockPos> findSuspiciousBlocks() {
        List<BlockPos> result = new ArrayList<>();
        BlockPos center = steve.blockPosition();
        
        // Horizontal scan
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    
                    // Vertical scan
                    for (int dy = -4; dy <= 4; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        Block b = steve.level().getBlockState(p).getBlock();
                        if (b == Blocks.SUSPICIOUS_SAND || b == Blocks.SUSPICIOUS_GRAVEL) {
                            result.add(p);
                        }
                    }
                }
            }
        }
        return result;
    }

    private void equipBrush() {
        com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
        for (net.minecraft.world.item.ItemStack stack : inv.getItems()) {
            if (stack.is(Items.BRUSH)) {
                steve.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
                return;
            }
        }
        // If no brush, warn the user
        steve.sendChatMessage("Tao cần một cái bàn chải (Brush) để bắt đầu khai quật!");
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        return "Archeology mining (" + targetIndex + "/" + targets.size() + ")";
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }
}
