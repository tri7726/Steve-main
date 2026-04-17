package com.steve.ai.action;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ResourceDependencyResolver: giải quyết chuỗi nguyên liệu đệ quy.
 *
 * Nâng cấp: graph-based recursive resolution thay vì hardcoded if-else.
 * Tự động resolve toàn bộ chuỗi phụ thuộc đến tận gốc (raw materials).
 * Ví dụ: iron_pickaxe → iron_ingot → raw_iron → mine iron_ore
 *                      → stick → oak_planks → oak_log
 */
public class ResourceDependencyResolver {

    /**
     * Resolve đệ quy toàn bộ chuỗi phụ thuộc cho item mục tiêu.
     * Trả về danh sách Task theo thứ tự thực hiện (từ gốc đến ngọn).
     */
    public static List<Task> resolve(SteveEntity steve, Item target, int quantity) {
        List<Task> buildOrder = new ArrayList<>();
        Set<String> visited = new HashSet<>(); // Tránh vòng lặp vô hạn
        resolveRecursive(steve, target, quantity, buildOrder, visited, 0);
        return buildOrder;
    }

    private static void resolveRecursive(SteveEntity steve, Item target, int quantity,
                                          List<Task> order, Set<String> visited, int depth) {
        if (depth > 8) return; // Max depth guard
        String key = target.toString() + ":" + quantity;
        if (visited.contains(key)) return;
        visited.add(key);

        var inv = steve.getMemory().getInventory();

        // ── Raw materials (không cần craft) ──────────────────────────────────
        if (isRawMaterial(target)) {
            if (!inv.hasItem(target, quantity)) {
                order.add(new Task("mine", Map.of("block", getBlockForItem(target), "quantity", quantity)));
            }
            return;
        }

        // ── Smelted items ─────────────────────────────────────────────────────
        if (isSmeltedItem(target)) {
            if (!inv.hasItem(target, quantity)) {
                Item rawItem = getRawItemFor(target);
                if (rawItem != null) {
                    resolveRecursive(steve, rawItem, quantity, order, visited, depth + 1);
                    order.add(new Task("smelt", Map.of("item", rawItem.toString().replace("minecraft:", ""), "quantity", quantity)));
                }
            }
            return;
        }

        // ── Crafted items: resolve ingredients đệ quy ────────────────────────
        Map<Item, Integer> ingredients = getCraftingIngredients(target, quantity);
        if (ingredients.isEmpty()) return;

        for (Map.Entry<Item, Integer> entry : ingredients.entrySet()) {
            Item ingredient = entry.getKey();
            int needed = entry.getValue();
            int have = inv.countItem(ingredient);
            if (have < needed) {
                resolveRecursive(steve, ingredient, needed - have, order, visited, depth + 1);
            }
        }

        // Cần crafting table cho recipe 3x3?
        if (needsCraftingTable(target) && !inv.hasItem(Items.CRAFTING_TABLE, 1)) {
            resolveRecursive(steve, Items.CRAFTING_TABLE, 1, order, visited, depth + 1);
        }

        order.add(new Task("craft", Map.of("item", getItemName(target), "quantity", quantity)));
    }

    // ── Item classification ───────────────────────────────────────────────────

    private static boolean isRawMaterial(Item item) {
        return item == Items.OAK_LOG || item == Items.BIRCH_LOG || item == Items.SPRUCE_LOG
            || item == Items.COAL || item == Items.RAW_IRON || item == Items.RAW_GOLD
            || item == Items.RAW_COPPER || item == Items.DIAMOND || item == Items.EMERALD
            || item == Items.LAPIS_LAZULI || item == Items.REDSTONE;
    }

    private static boolean isSmeltedItem(Item item) {
        return item == Items.IRON_INGOT || item == Items.GOLD_INGOT || item == Items.COPPER_INGOT
            || item == Items.COOKED_BEEF || item == Items.COOKED_CHICKEN || item == Items.COOKED_PORKCHOP;
    }

    private static boolean needsCraftingTable(Item item) {
        return item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE
            || item == Items.IRON_PICKAXE || item == Items.DIAMOND_PICKAXE
            || item == Items.WOODEN_SWORD || item == Items.IRON_SWORD
            || item == Items.FURNACE || item == Items.CHEST;
    }

    // ── Ingredient maps ───────────────────────────────────────────────────────

    private static Map<Item, Integer> getCraftingIngredients(Item item, int qty) {
        if (item == Items.OAK_PLANKS)       return Map.of(Items.OAK_LOG, Math.max(1, qty / 4));
        if (item == Items.STICK)            return Map.of(Items.OAK_PLANKS, Math.max(2, qty / 2));
        if (item == Items.CRAFTING_TABLE)   return Map.of(Items.OAK_PLANKS, 4);
        if (item == Items.FURNACE)          return Map.of(Items.COBBLESTONE, 8);
        if (item == Items.TORCH)            return Map.of(Items.STICK, qty, Items.COAL, qty);
        if (item == Items.WOODEN_PICKAXE)   return Map.of(Items.OAK_PLANKS, 3, Items.STICK, 2);
        if (item == Items.STONE_PICKAXE)    return Map.of(Items.COBBLESTONE, 3, Items.STICK, 2);
        if (item == Items.IRON_PICKAXE)     return Map.of(Items.IRON_INGOT, 3, Items.STICK, 2);
        if (item == Items.DIAMOND_PICKAXE)  return Map.of(Items.DIAMOND, 3, Items.STICK, 2);
        if (item == Items.WOODEN_SWORD)     return Map.of(Items.OAK_PLANKS, 2, Items.STICK, 1);
        if (item == Items.IRON_SWORD)       return Map.of(Items.IRON_INGOT, 2, Items.STICK, 1);
        if (item == Items.COBBLESTONE)      return Map.of(); // mined, not crafted
        return Map.of();
    }

    private static String getBlockForItem(Item item) {
        if (item == Items.OAK_LOG || item == Items.BIRCH_LOG || item == Items.SPRUCE_LOG) return "oak_log";
        if (item == Items.COAL)         return "coal_ore";
        if (item == Items.RAW_IRON)     return "iron_ore";
        if (item == Items.RAW_GOLD)     return "gold_ore";
        if (item == Items.RAW_COPPER)   return "copper_ore";
        if (item == Items.DIAMOND)      return "diamond_ore";
        if (item == Items.EMERALD)      return "emerald_ore";
        if (item == Items.LAPIS_LAZULI) return "lapis_ore";
        if (item == Items.REDSTONE)     return "redstone_ore";
        if (item == Items.COBBLESTONE)  return "stone";
        return "stone";
    }

    private static Item getRawItemFor(Item smelted) {
        if (smelted == Items.IRON_INGOT)    return Items.RAW_IRON;
        if (smelted == Items.GOLD_INGOT)    return Items.RAW_GOLD;
        if (smelted == Items.COPPER_INGOT)  return Items.RAW_COPPER;
        if (smelted == Items.COOKED_BEEF)   return Items.BEEF;
        if (smelted == Items.COOKED_CHICKEN) return Items.CHICKEN;
        return null;
    }

    private static String getItemName(Item item) {
        return item.toString().replace("minecraft:", "").replace("item.", "");
    }
}
