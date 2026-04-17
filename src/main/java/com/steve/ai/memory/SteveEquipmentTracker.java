package com.steve.ai.memory;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * SteveEquipmentTracker: theo dõi độ bền (durability) của tool/weapon
 * đang được trang bị. Giúp Steve biết khi nào cần sửa hoặc thay đồ.
 *
 * <p>Steve là PathfinderMob nên không có inventory slot như Player —
 * class này wrap thông tin trực tiếp từ main-hand và off-hand.</p>
 */
@SuppressWarnings("null")
public class SteveEquipmentTracker {

    /**
     * Tính phần trăm độ bền còn lại của một ItemStack (0.0 = gãy, 1.0 = mới).
     * Trả về 1.0 nếu item không có durability (không bị mòn).
     */
    public static float getDurabilityFraction(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 1.0f;
        if (!stack.isDamageableItem()) return 1.0f;  // Không có durability (đá, quặng, v.v.)
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 0) return 1.0f;
        int currentDamage = stack.getDamageValue();
        return 1.0f - ((float) currentDamage / maxDamage);
    }

    /**
     * Kiểm tra item có bị mòn dưới ngưỡng không.
     * @param stack   ItemStack cần kiểm tra
     * @param threshold  Ngưỡng (0.0 – 1.0). Ví dụ: 0.1 = dưới 10% độ bền
     */
    public static boolean isDamaged(ItemStack stack, float threshold) {
        return getDurabilityFraction(stack) < threshold;
    }

    /**
     * Kiểm tra item cần sửa gấp (dưới 10% độ bền).
     */
    public static boolean needsRepair(ItemStack stack) {
        return isDamaged(stack, 0.10f);
    }

    /**
     * Tạo chuỗi mô tả ngắn cho prompt, ví dụ: "iron_pickaxe(73%)"
     * Trả về "empty" nếu không có item.
     */
    public static String getSlotSummary(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        String name = stack.getItem().getDescription().getString().toLowerCase().replace(" ", "_");
        if (!stack.isDamageableItem()) return name;
        float fraction = getDurabilityFraction(stack);
        int pct = Math.round(fraction * 100);
        String desc = name + "(" + pct + "%)";
        if (fraction < 0.10f) {
            desc += "[CRITICAL]";
        }
        return desc;
    }

    /**
     * Tạo chuỗi mô tả cả 2 tay cho prompt.
     * Ví dụ: "main=iron_sword(88%), off=shield(100%)"
     */
    public static String getFullSummary(ItemStack mainHand, ItemStack offHand) {
        return "main=" + getSlotSummary(mainHand)
             + ", off=" + getSlotSummary(offHand);
    }

    /**
     * Kiểm tra và trang bị khiên từ inventory vào tay phụ nếu chưa có.
     * @return true nếu đã có hoặc vừa trang bị xong
     */
    public static boolean ensureShieldEquipped(com.steve.ai.entity.SteveEntity steve) {
        ItemStack offhand = steve.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        if (offhand.getItem() instanceof net.minecraft.world.item.ShieldItem) return true;

        SteveInventory inv = steve.getMemory().getInventory();
        ItemStack shield = inv.findFirstItem(Items.SHIELD);
        if (!shield.isEmpty()) {
            steve.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, shield.copy());
            inv.removeItem(Items.SHIELD, 1);
            return true;
        }
        return false;
    }
}
