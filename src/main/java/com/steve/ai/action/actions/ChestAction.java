package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChestAction: store items into a nearby chest or retrieve items from it.
 * mode "store"    → takes tracked items from SteveInventory → puts into chest.
 * mode "retrieve" → takes item from chest → gives to nearest player.
 * mode "list"     → reports chest contents to GUI.
 *
 * Lock mechanism: dùng ConcurrentHashMap để tránh nhiều Steve truy cập cùng 1 rương.
 */
@SuppressWarnings("null")
public class ChestAction extends BaseAction {
    private String mode;
    private String itemName;
    private int quantity;

    /** BlockPos → tên Steve đang giữ lock. Static để share giữa tất cả instances. */
    private static final ConcurrentHashMap<Long, String> CHEST_LOCKS = new ConcurrentHashMap<>();

    private BlockPos lockedChestPos = null;

    public ChestAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        mode     = task.getStringParameter("mode");      // "store", "retrieve", "list"
        itemName = task.getStringParameter("item");
        quantity = task.getIntParameter("quantity", 1);

        if (mode == null) mode = "list";

        BlockPos chestPos = findNearestChest();
        if (chestPos == null) {
            // Dùng BlockPlacementHelper để tìm vị trí hợp lệ, không đặt cạnh rương khác
            chestPos = com.steve.ai.action.BlockPlacementHelper.findChestPlacementPos(steve, 4);
            if (chestPos == null) {
                result = ActionResult.failure("Khong tim duoc vi tri dat ruong hop le!");
                return;
            }
            steve.level().setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            SteveMod.LOGGER.info("Steve '{}' placed a chest at {}", steve.getSteveName(), chestPos);
        }

        // ── Acquire lock — từ chối nếu Steve khác đang dùng rương này ────────
        long posKey = chestPos.asLong();
        String existingOwner = CHEST_LOCKS.putIfAbsent(posKey, steve.getSteveName());
        if (existingOwner != null && !existingOwner.equals(steve.getSteveName())) {
            result = ActionResult.failure("Ruong dang duoc " + existingOwner + " su dung, thu lai sau!");
            steve.sendChatMessage("Ruong bi " + existingOwner + " chiem roi, doi xiu!");
            return;
        }
        lockedChestPos = chestPos;

        // Walk to chest
        steve.getNavigation().moveTo(chestPos.getX(), chestPos.getY(), chestPos.getZ(), 1.0);
        steve.swing(InteractionHand.OFF_HAND, true);

        BlockEntity be = steve.level().getBlockEntity(chestPos);
        if (!(be instanceof ChestBlockEntity chest)) {
            result = ActionResult.failure("Could not access chest at " + chestPos);
            return;
        }

        switch (mode) {
            case "store"    -> storeItems(chest);
            case "retrieve" -> retrieveItems(chest);
            case "loot"     -> lootItems(chest);
            default         -> listContents(chest, chestPos);
        }

        // Release lock sau khi xong
        if (lockedChestPos != null) {
            CHEST_LOCKS.remove(lockedChestPos.asLong(), steve.getSteveName());
        }
    }

    @Override protected void onTick() { }
    @Override protected void onCancel() {
        steve.getNavigation().stop();
        // Release lock khi bị cancel
        if (lockedChestPos != null) {
            CHEST_LOCKS.remove(lockedChestPos.asLong(), steve.getSteveName());
        }
    }

    @Override
    public String getDescription() { return "Chest " + mode + (itemName != null ? " " + itemName : ""); }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    // ── Implementation ─────────────────────────────────────────────────────────

    private void storeItems(ChestBlockEntity chest) {
        // Use SteveInventory if it exists, otherwise log a note
        com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
        if (inv.isEmpty()) {
            result = ActionResult.success("Nothing in Steve's inventory to store");
            return;
        }

        int stored = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).isEmpty()) {
                ItemStack nextItem = inv.takeFirst();
                if (nextItem == null) break;
                chest.setItem(slot, nextItem);
                stored++;
            }
        }

        result = ActionResult.success("Stored " + stored + " item stacks into chest");
        SteveMod.LOGGER.info("Steve '{}' stored {} stacks", steve.getSteveName(), stored);
    }

    private void retrieveItems(ChestBlockEntity chest) {
        if (itemName == null) {
            result = ActionResult.failure("No item name specified for retrieve");
            return;
        }

        String target = itemName.toLowerCase().replace(" ", "_");
        int retrieved = 0;
        net.minecraft.world.entity.player.Player player = steve.level().getNearestPlayer(steve, 10.0);

        for (int slot = 0; slot < chest.getContainerSize() && retrieved < quantity; slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty() && stack.getItem().getDescriptionId().toLowerCase().contains(target)) {
                int take = Math.min(stack.getCount(), quantity - retrieved);
                ItemStack toGive = stack.copy();
                toGive.setCount(take);
                stack.shrink(take);
                chest.setItem(slot, stack);

                if (player != null && !player.getInventory().add(toGive)) {
                    steve.spawnAtLocation(toGive);
                } else if (player == null) {
                    steve.spawnAtLocation(toGive);
                }
                retrieved += take;
            }
        }

        result = ActionResult.success("Retrieved " + retrieved + "x " + itemName + " from chest");
    }

    private void lootItems(ChestBlockEntity chest) {
        com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
        int lootedCount = 0;

        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;

            if (isValuable(stack)) {
                ItemStack toLoot = stack.copy();
                inv.addItem(toLoot);
                stack.setCount(0);
                chest.setItem(slot, stack);
                lootedCount++;
            }
        }

        if (lootedCount > 0) {
            steve.sendChatMessage("Đã quét sạch rương! Vét được " + lootedCount + " món đồ ngon.");
            result = ActionResult.success("Looted " + lootedCount + " valuable stacks");
        } else {
            result = ActionResult.success("No valuable items found to loot");
        }
    }

    private boolean isValuable(ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        return item == net.minecraft.world.item.Items.DIAMOND || 
               item == net.minecraft.world.item.Items.GOLD_INGOT ||
               item == net.minecraft.world.item.Items.IRON_INGOT ||
               item == net.minecraft.world.item.Items.EMERALD ||
               item == net.minecraft.world.item.Items.ENCHANTED_BOOK ||
               item instanceof net.minecraft.world.item.ArmorItem ||
               item instanceof net.minecraft.world.item.TieredItem ||
               stack.isEdible() ||
               item == net.minecraft.world.item.Items.GOLDEN_APPLE ||
               item == net.minecraft.world.item.Items.ENDER_PEARL;
    }

    private void listContents(ChestBlockEntity chest, BlockPos pos) {
        List<String> contents = new ArrayList<>();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty()) {
                contents.add(stack.getCount() + "x " + stack.getItem().getDescription().getString());
            }
        }

        String summary = contents.isEmpty() ? "empty"
                : String.join(", ", contents);

        // Memory: save chest location + contents
        steve.getMemory().addAction("Chest at " + pos + " contains: " + summary);

        if (steve.level().isClientSide) {
            com.steve.ai.client.SteveGUI.addSteveMessage(steve.getSteveName(),
                    "Rương tại " + pos + ": " + summary);
        }

        result = ActionResult.success("Chest contains: " + summary);
    }

    private BlockPos findNearestChest() {
        BlockPos center = steve.blockPosition();
        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        if (steve.level().getBlockState(p).getBlock() == Blocks.CHEST) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }
}
