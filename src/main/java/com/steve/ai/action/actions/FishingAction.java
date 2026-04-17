package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.Random;

/**
 * FishingAction: Steve tìm mặt nước, đứng cạnh và câu cá.
 * Cần fishing rod trong SteveInventory.
 * Kết quả (cá, rác, treasure) được thêm vào SteveInventory.
 */
@SuppressWarnings("null") // Minecraft API (getBlockState, blockPosition, level) guaranteed non-null at runtime
public class FishingAction extends BaseAction {
    private int quantity;
    private int caughtCount;
    private int ticksRunning;
    private int waitTicks;       // Ticks chờ cá cắn câu
    private BlockPos waterPos;
    private boolean isCasting;
    private static final int MAX_TICKS = 6000;  // 5 phút
    private static final int MIN_WAIT = 100;    // 5 giây min
    private static final int MAX_WAIT = 300;    // 15 giây max
    private final Random random = new Random();

    // Loot table đơn giản (xác suất)
    private static final Object[][] FISH_LOOT = {
        {Items.COD,              50},
        {Items.SALMON,           25},
        {Items.TROPICAL_FISH,    10},
        {Items.PUFFERFISH,        5},
        {Items.ENCHANTED_BOOK,    2},
        {Items.BOW,               2},
        {Items.FISHING_ROD,       2},
        {Items.SADDLE,            1},
        {Items.LILY_PAD,          3},
    };

    public FishingAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        quantity    = task.getIntParameter("quantity", 5);
        caughtCount = 0;
        ticksRunning = 0;
        isCasting   = false;

        // Kiểm tra có fishing rod không
        SteveInventory inv = steve.getMemory().getInventory();
        if (!inv.hasItem(Items.FISHING_ROD, 1)) {
            result = ActionResult.failure("Khong co cau ca trong tui!");
            steve.sendChatMessage("Tao can cau ca de cau ca!");
            return;
        }

        // Tìm mặt nước gần nhất
        waterPos = findWater();
        if (waterPos == null) {
            result = ActionResult.failure("Khong tim thay nuoc de cau ca!");
            steve.sendChatMessage("Khong co nuoc gan day de cau ca!");
            return;
        }

        // Đi đến cạnh mặt nước
        steve.getNavigation().moveTo(waterPos.getX() + 0.5, waterPos.getY(), waterPos.getZ() + 0.5, 1.0);
        steve.sendChatMessage("Di cau ca! Can " + quantity + " con.");
        SteveMod.LOGGER.info("Steve '{}' going fishing at {}", steve.getSteveName(), waterPos);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.success("Cau duoc " + caughtCount + "/" + quantity + " con ca.");
            return;
        }

        if (caughtCount >= quantity) {
            steve.sendChatMessage("Cau du " + quantity + " con roi! Xong!");
            result = ActionResult.success("Caught " + caughtCount + " fish");
            return;
        }

        // Chờ đến gần nước
        if (steve.getNavigation().isInProgress()) return;

        if (!isCasting) {
            // Bắt đầu cast
            steve.swing(InteractionHand.MAIN_HAND, true);
            waitTicks = MIN_WAIT + random.nextInt(MAX_WAIT - MIN_WAIT);
            isCasting = true;
            SteveMod.LOGGER.debug("Steve '{}' cast fishing rod, waiting {}t", steve.getSteveName(), waitTicks);
            return;
        }

        // Đang chờ cá cắn
        waitTicks--;
        if (waitTicks > 0) return;

        // Cá cắn! Reel in
        steve.swing(InteractionHand.MAIN_HAND, true);
        ItemStack caught = rollFishLoot();
        steve.getMemory().getInventory().addItem(caught);
        caughtCount++;

        steve.sendChatMessage("Cau duoc " + caught.getItem().getDescription().getString()
            + "! (" + caughtCount + "/" + quantity + ")");
        SteveMod.LOGGER.info("Steve '{}' caught {}", steve.getSteveName(),
            caught.getItem().getDescription().getString());

        isCasting = false; // Cast lại lần tiếp theo
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Fishing (" + caughtCount + "/" + quantity + ")";
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    private BlockPos findWater() {
        BlockPos center = steve.blockPosition();
        var level = steve.level();
        for (int r = 1; r <= 32; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        if (level.getBlockState(p).getBlock() != Blocks.WATER) continue;
                        BlockPos standPos = p.above();
                        var standState  = level.getBlockState(standPos);
                        var belowState  = level.getBlockState(standPos.below());
                        if (standState.isAir() && belowState.isSolidRender(level, standPos.below())) {
                            return standPos;
                        }
                    }
                }
            }
        }
        return null;
    }

    private ItemStack rollFishLoot() {
        int total = 0;
        for (Object[] entry : FISH_LOOT) total += (int) entry[1];
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (Object[] entry : FISH_LOOT) {
            cumulative += (int) entry[1];
            if (roll < cumulative) {
                return new ItemStack((net.minecraft.world.item.Item) entry[0], 1);
            }
        }
        return new ItemStack(Items.COD, 1);
    }
}
