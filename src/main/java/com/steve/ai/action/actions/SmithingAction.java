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
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

/**
 * SmithingAction: Steve sử dụng Bàn Rèn để nâng cấp đồ Kim Cương lên Netherite.
 */
@SuppressWarnings("null")
public class SmithingAction extends BaseAction {

    public SmithingAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        SteveInventory inv = steve.getMemory().getInventory();

        // 1. Kiểm tra Netherite Ingot
        if (!inv.hasItem(Items.NETHERITE_INGOT, 1)) {
            result = ActionResult.failure("Thiếu thỏi Netherite để nâng cấp!");
            return;
        }

        // 2. Tìm trang bị Kim Cương cần nâng cấp
        ItemStack diamondItem = findDiamondItem(steve);
        if (diamondItem == null || diamondItem.isEmpty()) {
            result = ActionResult.failure("Không tìm thấy đồ Kim Cương nào để nâng cấp.");
            return;
        }

        // 3. Tìm hoặc đặt Smithing Table
        BlockPos tablePos = findSmithingTable();
        if (tablePos == null) {
            if (inv.hasItem(Items.SMITHING_TABLE, 1)) {
                tablePos = BlockPlacementHelper.findPlacementPos(steve, 4);
                if (tablePos == null) tablePos = steve.blockPosition().above();
                steve.level().setBlock(tablePos, Blocks.SMITHING_TABLE.defaultBlockState(), 3);
                inv.removeItem(Items.SMITHING_TABLE, 1);
                SteveMod.LOGGER.info("Steve '{}' placed smithing table at {}", steve.getSteveName(), tablePos);
            } else {
                result = ActionResult.failure("Cần Bàn Rèn để nâng cấp đồ!");
                return;
            }
        }

        // 4. Di chuyển tới bàn và thực hiện nâng cấp (Giả lập)
        steve.getNavigation().moveTo(tablePos.getX(), tablePos.getY(), tablePos.getZ(), 1.0);
        
        // Trừ nguyên liệu
        inv.removeItem(Items.NETHERITE_INGOT, 1);
        
        // Tạo item Netherite tương ứng
        ItemStack netheriteItem = convertToNetherite(diamondItem);
        
        // Nếu item đang mặc thì mặc luôn, nếu trong inv thì thay thế
        updateEquipment(steve, diamondItem, netheriteItem);
        
        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.sendChatMessage("Trang bị của tao giờ đã là Netherite! Thách cả thế giới luôn.");
        SteveMod.LOGGER.info("Steve '{}' upgraded {} to Netherite", steve.getSteveName(), diamondItem);
        
        result = ActionResult.success("Upgraded item to Netherite");
    }

    private ItemStack findDiamondItem(SteveEntity steve) {
        // Kiểm tra armor slots trước
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            ItemStack stack = steve.getItemBySlot(slot);
            if (isDiamond(stack)) return stack;
        }
        // Kiểm tra inventory
        for (ItemStack stack : steve.getMemory().getInventory().getItems()) {
            if (isDiamond(stack)) return stack;
        }
        return null;
    }

    private boolean isDiamond(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.DIAMOND_SWORD || item == Items.DIAMOND_PICKAXE || 
               item == Items.DIAMOND_AXE || item == Items.DIAMOND_SHOVEL || 
               item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE || 
               item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS;
    }

    private ItemStack convertToNetherite(ItemStack diamond) {
        Item dType = diamond.getItem();
        Item nType;
        if (dType == Items.DIAMOND_SWORD) nType = Items.NETHERITE_SWORD;
        else if (dType == Items.DIAMOND_PICKAXE) nType = Items.NETHERITE_PICKAXE;
        else if (dType == Items.DIAMOND_AXE) nType = Items.NETHERITE_AXE;
        else if (dType == Items.DIAMOND_SHOVEL) nType = Items.NETHERITE_SHOVEL;
        else if (dType == Items.DIAMOND_HELMET) nType = Items.NETHERITE_HELMET;
        else if (dType == Items.DIAMOND_CHESTPLATE) nType = Items.NETHERITE_CHESTPLATE;
        else if (dType == Items.DIAMOND_LEGGINGS) nType = Items.NETHERITE_LEGGINGS;
        else if (dType == Items.DIAMOND_BOOTS) nType = Items.NETHERITE_BOOTS;
        else return diamond;

        ItemStack netherite = new ItemStack(nType);
        // Copy enchantments
        net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(
            net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(diamond), netherite);
        return netherite;
    }

    private void updateEquipment(SteveEntity steve, ItemStack oldItem, ItemStack newItem) {
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (steve.getItemBySlot(slot) == oldItem) {
                steve.setItemSlot(slot, newItem);
                return;
            }
        }
        // Nếu không có trong slot mặc định thì thay thế trong inventory
        steve.getMemory().getInventory().removeItem(oldItem.getItem(), 1);
        steve.getMemory().getInventory().addItem(newItem);
    }

    private BlockPos findSmithingTable() {
        BlockPos center = steve.blockPosition();
        for (int r = 1; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        if (steve.level().getBlockState(p).getBlock() == Blocks.SMITHING_TABLE) return p;
                    }
                }
            }
        }
        return null;
    }

    @Override protected void onTick() { }
    @Override protected void onCancel() { }
    @Override public String getDescription() { return "Smithing Netherite Gear"; }
    @Override public EnumSet<ActionSlot> getRequiredSlots() { return EnumSet.of(ActionSlot.INTERACTION); }
}
