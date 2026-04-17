package com.steve.ai.action;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * CraftingPrerequisiteChecker: kiểm tra Steve có đủ công cụ để thực hiện
 * một task khai thác (mine) không, và trả về danh sách task cần làm trước.
 *
 * <p><b>Tier tool Minecraft:</b>
 * <pre>
 * wooden_pickaxe  → stone, coal
 * stone_pickaxe   → iron, copper
 * iron_pickaxe    → gold, diamond, redstone, lapis
 * diamond_pickaxe → netherite, ancient_debris, emerald
 * </pre>
 *
 * <p>Nếu không có đủ pickaxe tier → trả về task craft pickaxe cần thiết.
 */
public class CraftingPrerequisiteChecker {

    /** Không đủ tiên quyết: trả về thông báo lý do */
    public record PrerequisiteResult(boolean satisfied, String reason, List<Task> requiredTasks) {
        public static PrerequisiteResult ok() {
            return new PrerequisiteResult(true, "OK", Collections.emptyList());
        }
        public static PrerequisiteResult missing(String reason, List<Task> tasks) {
            return new PrerequisiteResult(false, reason, tasks);
        }
    }

    /**
     * Kiểm tra xem Steve có thể đào block loại blockType không,
     * dựa vào inventory hiện tại.
     *
     * @param steve     Entity cần kiểm tra
     * @param blockType Tên block sẽ đào (ví dụ: "diamond", "iron", "stone")
     * @return PrerequisiteResult — satisfied=true nếu đủ điều kiện
     */
    public static PrerequisiteResult check(SteveEntity steve, String blockType) {
        if (blockType == null) return PrerequisiteResult.ok();
        String b = blockType.toLowerCase().trim();
        // Alias
        b = switch (b) {
            case "sat", "iron"     -> "iron";
            case "kim_cuong", "diamond" -> "diamond";
            case "than", "coal"    -> "coal";
            case "vang", "gold"    -> "gold";
            case "da", "stone"     -> "stone";
            case "dong", "copper"  -> "copper";
            case "than_do", "redstone" -> "redstone";
            case "lapis"           -> "lapis";
            case "ngoc_luc_bao", "emerald" -> "emerald";
            default -> b;
        };

        int requiredTier = getRequiredPickaxeTier(b);
        if (requiredTier == 0) return PrerequisiteResult.ok(); // Không cần pickaxe

        SteveInventory inv = steve.getMemory().getInventory();
        int bestTier = getBestPickaxeTier(inv);

        if (bestTier >= requiredTier) {
            return PrerequisiteResult.ok();
        }

        // Thiếu pickaxe đủ tier → tạo task craft
        String neededPickaxe = getPickaxeNameForTier(requiredTier);
        String reason = "Cần " + neededPickaxe + " để đào " + b
            + " (đang có tier " + bestTier + ", cần tier " + requiredTier + ")";

        // Task craft pickaxe tier cần thiết
        Task craftTask = new Task("craft", Map.of("item", neededPickaxe, "quantity", 1));
        return PrerequisiteResult.missing(reason, List.of(craftTask));
    }

    /**
     * Tier pickaxe cần thiết cho từng loại block (1=gỗ, 2=đá, 3=sắt, 4=kim cương).
     * 0 = không cần pickaxe (đất, cỏ, gỗ, v.v.)
     */
    private static int getRequiredPickaxeTier(String blockType) {
        return switch (blockType) {
            case "stone", "cobblestone", "granite", "diorite", "andesite" -> 1;
            case "coal", "coal_ore"   -> 1;
            case "iron", "iron_ore", "copper", "copper_ore"                -> 2;
            case "gold", "gold_ore", "lapis", "lapis_ore",
                 "redstone", "redstone_ore"                                -> 3;
            case "diamond", "diamond_ore", "emerald", "emerald_ore"       -> 3;
            case "netherite", "ancient_debris"                             -> 4;
            default -> 0; // Gỗ, đất, cỏ, v.v. — không cần pickaxe
        };
    }

    /**
     * Tier cao nhất của pickaxe đang có trong inventory.
     * 0 = không có pickaxe nào.
     */
    private static int getBestPickaxeTier(SteveInventory inv) {
        if (inv.hasItem(Items.NETHERITE_PICKAXE, 1)) return 5;
        if (inv.hasItem(Items.DIAMOND_PICKAXE, 1))   return 4;
        if (inv.hasItem(Items.IRON_PICKAXE, 1))      return 3;
        if (inv.hasItem(Items.STONE_PICKAXE, 1))     return 2;
        if (inv.hasItem(Items.WOODEN_PICKAXE, 1))    return 1;
        return 0;
    }

    /** Tên pickaxe cho tier (dùng để tạo craft task) */
    private static String getPickaxeNameForTier(int tier) {
        return switch (tier) {
            case 1 -> "wooden_pickaxe";
            case 2 -> "stone_pickaxe";
            case 3 -> "iron_pickaxe";
            case 4 -> "diamond_pickaxe";
            default -> "iron_pickaxe";
        };
    }
}
