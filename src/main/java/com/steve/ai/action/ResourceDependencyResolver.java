package com.steve.ai.action;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ResourceDependencyResolver: giải quyết chuỗi nguyên liệu đệ quy.
 */
public class ResourceDependencyResolver {

    public static List<Task> resolve(SteveEntity steve, Item target, int quantity) {
        List<Task> buildOrder = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        resolveRecursive(steve, target, quantity, buildOrder, visited, 0);
        return buildOrder;
    }

    private static void resolveRecursive(SteveEntity steve, Item target, int quantity,
                                          List<Task> order, Set<String> visited, int depth) {
        if (depth > 8) return;
        String key = target.toString() + ":" + quantity;
        if (visited.contains(key)) return;
        visited.add(key);

        var inv = steve.getMemory().getInventory();

        if (isRawMaterial(target)) {
            if (!inv.hasItem(target, quantity)) {
                order.add(new Task("mine", Map.of("block", getBlockForItem(target), "quantity", quantity)));
            }
            return;
        }

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

        order.add(new Task("craft", Map.of("item", getItemName(target), "quantity", quantity)));
    }

    private static boolean isRawMaterial(Item item) {
        String name = item.toString();
        return name.contains("_log") || name.contains("_stem") || name.contains("_wood")
            || item == Items.COAL || item == Items.RAW_IRON || item == Items.RAW_GOLD
            || item == Items.RAW_COPPER || item == Items.DIAMOND || item == Items.EMERALD
            || item == Items.LAPIS_LAZULI || item == Items.REDSTONE || item == Items.COBBLESTONE
            || item == Items.WHEAT || item == Items.SUGAR_CANE || item == Items.BAMBOO;
    }

    private static boolean isSmeltedItem(Item item) {
        return item == Items.IRON_INGOT || item == Items.GOLD_INGOT || item == Items.COPPER_INGOT
            || item == Items.COOKED_BEEF || item == Items.COOKED_CHICKEN || item == Items.COOKED_PORKCHOP
            || item == Items.GLASS || item == Items.BRICK;
    }

    private static boolean needsCraftingTable(Item item) {
        String name = item.toString();
        // Hầu hết tool và block phức tạp cần bàn craft
        return name.contains("_pickaxe") || name.contains("_sword") || name.contains("_axe")
            || name.contains("_shovel") || name.contains("_hoe") || name.contains("_chestplate")
            || item == Items.FURNACE || item == Items.CHEST || item == Items.CRAFTING_TABLE
            || name.contains("_bed") || name.contains("_boat") || name.contains("_door");
    }

    private static Map<Item, Integer> getCraftingIngredients(Item item, int qty) {
        String name = item.toString();

        if (name.endsWith("_planks")) {
            String logName = name.replace("_planks", "_log");
            Item logItem = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("minecraft", logName));
            if (logItem == null || logItem == Items.AIR) logItem = Items.OAK_LOG;
            return Map.of(logItem, (qty + 3) / 4);
        }

        if (item == Items.STICK)            return Map.of(Items.OAK_PLANKS, (qty + 1) / 2);
        if (item == Items.CRAFTING_TABLE)   return Map.of(Items.OAK_PLANKS, 4);
        if (item == Items.FURNACE)          return Map.of(Items.COBBLESTONE, 8);
        if (item == Items.CHEST)            return Map.of(Items.OAK_PLANKS, 8);
        if (item == Items.TORCH)            return Map.of(Items.STICK, (qty + 3) / 4, Items.COAL, (qty + 3) / 4);
        
        if (item == Items.WOODEN_PICKAXE)   return Map.of(Items.OAK_PLANKS, 3, Items.STICK, 2);
        if (item == Items.STONE_PICKAXE)    return Map.of(Items.COBBLESTONE, 3, Items.STICK, 2);
        if (item == Items.IRON_PICKAXE)     return Map.of(Items.IRON_INGOT, 3, Items.STICK, 2);
        if (item == Items.DIAMOND_PICKAXE)  return Map.of(Items.DIAMOND, 3, Items.STICK, 2);
        
        if (item == Items.WOODEN_SWORD)     return Map.of(Items.OAK_PLANKS, 2, Items.STICK, 1);
        if (item == Items.IRON_SWORD)       return Map.of(Items.IRON_INGOT, 2, Items.STICK, 1);
        
        return Map.of();
    }

    private static String getBlockForItem(Item item) {
        String name = item.toString().replace("minecraft:", "");
        if (name.contains("_log")) return name;
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
        if (smelted == Items.COOKED_PORKCHOP) return Items.PORKCHOP;
        return null;
    }

    private static String getItemName(Item item) {
        return item.toString().replace("minecraft:", "").replace("item.", "");
    }
}
