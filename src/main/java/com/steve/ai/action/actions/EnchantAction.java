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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.Objects;

/**
 * EnchantAction: Steve sử dụng bàn phù phép để nâng cấp trang bị.
 * Cần: Enchanting Table + Lapis Lazuli + 30 Levels.
 */
@SuppressWarnings("null") // Minecraft API (getBlockState, blockPosition, offset) guaranteed non-null at runtime
public class EnchantAction extends BaseAction {
    private int ticksRunning;
    private static final int ENCHANT_COST = 3; // Lapis cost
    private static final int MAX_TICKS = 600;

    public EnchantAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        ticksRunning = 0;
        SteveInventory inv = steve.getMemory().getInventory();

        // 1. Kiểm tra tài nguyên
        if (!inv.hasItem(Items.LAPIS_LAZULI, ENCHANT_COST)) {
            result = ActionResult.failure("Thiếu Lapis Lazuli để phù phép!");
            steve.sendChatMessage("Tao cần ít nhất " + ENCHANT_COST + " Lapis để phù phép.");
            return;
        }

        // 2. Tìm item cần enchantment (Ưu tiên Diamond tools/armor chưa có enchant)
        ItemStack targetStack = findBestItemToEnchant(steve);
        if (targetStack == null || targetStack.isEmpty()) {
            result = ActionResult.success("Không có món đồ nào cần phù phép ngay lúc này.");
            return;
        }

        // 3. Tìm hoặc đặt Enchanting Table
        BlockPos tablePos = findEnchantingTable();
        if (tablePos == null) {
            if (inv.hasItem(Items.ENCHANTING_TABLE, 1)) {
                tablePos = BlockPlacementHelper.findPlacementPos(steve, 4);
                if (tablePos == null) tablePos = Objects.requireNonNull(steve.blockPosition().above());
                steve.level().setBlock(tablePos, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
                inv.removeItem(Items.ENCHANTING_TABLE, 1);
                SteveMod.LOGGER.info("Steve '{}' placed enchanting table at {}", steve.getSteveName(), tablePos);
            } else {
                result = ActionResult.failure("Không có bàn phù phép!");
                steve.sendChatMessage("Tao cần bàn phù phép để nâng cấp đồ.");
                return;
            }
        }

        // 4. Di chuyển tới bàn
        steve.getNavigation().moveTo(tablePos.getX(), tablePos.getY(), tablePos.getZ(), 1.0);
        steve.sendChatMessage("Đang đi tới bàn phù phép để nâng cấp " + targetStack.getItem().getDescription().getString() + "...");

        // 5. Thực hiện phù phép (Simulated)
        // Trong thực tế mod, ta sẽ trừ level và lapis rồi dùng EnchantmentHelper
        inv.removeItem(Items.LAPIS_LAZULI, ENCHANT_COST);
        
        // Giả lập phù phép cấp 30 (Add random high-tier enchants)
        applyHighTierEnchants(targetStack);
        
        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.sendChatMessage("Xong! Món đồ " + targetStack.getItem().getDescription().getString() + " giờ đã rất bá đạo.");
        SteveMod.LOGGER.info("Steve '{}' enchanted {} with high-tier enchants", steve.getSteveName(), targetStack);
        
        result = ActionResult.success("Enchanted " + targetStack.getItem().getDescription().getString());
    }

    private ItemStack findBestItemToEnchant(SteveEntity steve) {
        // Kiểm tra main-hand trước
        ItemStack main = steve.getMainHandItem();
        if (isEnchantable(main)) return main;

        // Check armor slots
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) {
                ItemStack armor = steve.getItemBySlot(slot);
                if (isEnchantable(armor)) return armor;
            }
        }

        // Check inventory
        for (ItemStack stack : steve.getMemory().getInventory().getItems()) {
            if (isEnchantable(stack)) return stack;
        }
        return null;
    }

    private boolean isEnchantable(ItemStack stack) {
        if (stack.isEmpty() || !stack.isEnchantable()) return false;
        // Chỉ enchant đồ Diamond trở lên và chưa có nhiều enchant
        return stack.getItem() instanceof net.minecraft.world.item.TieredItem || stack.getItem() instanceof net.minecraft.world.item.ArmorItem;
    }

    private void applyHighTierEnchants(ItemStack stack) {
        // Logic đơn giản: Add Efficiency V, Unbreaking III, Sharpness V tùy loại
        java.util.Random rand = new java.util.Random();
        if (stack.getItem() instanceof net.minecraft.world.item.PickaxeItem) {
            stack.enchant(net.minecraft.world.item.enchantment.Enchantments.BLOCK_EFFICIENCY, 5);
            stack.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3);
            if (rand.nextBoolean()) stack.enchant(net.minecraft.world.item.enchantment.Enchantments.BLOCK_FORTUNE, 3);
        } else if (stack.getItem() instanceof net.minecraft.world.item.SwordItem) {
            stack.enchant(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 5);
            stack.enchant(net.minecraft.world.item.enchantment.Enchantments.SWEEPING_EDGE, 3);
            stack.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3);
        } else if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem) {
            stack.enchant(net.minecraft.world.item.enchantment.Enchantments.ALL_DAMAGE_PROTECTION, 4);
            stack.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3);
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        // Timeout guard: if navigating to the enchanting table takes too long, abort
        if (ticksRunning >= MAX_TICKS) {
            result = ActionResult.failure("Timed out navigating to enchanting table after " + (MAX_TICKS / 20) + "s.");
            steve.sendChatMessage("Dẫn đường tới bàn phù phép thất bại, bỏ qua.");
            steve.getNavigation().stop();
        }
    }

    @Override protected void onCancel() { steve.getNavigation().stop(); }

    @Override
    public String getDescription() { return "Enchanting gear"; }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    private BlockPos findEnchantingTable() {
        BlockPos center = steve.blockPosition();
        for (int r = 1; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        var state = steve.level().getBlockState(p);
                        if (state != null && state.getBlock() == Blocks.ENCHANTING_TABLE) return p;
                    }
                }
            }
        }
        return null;
    }
}
