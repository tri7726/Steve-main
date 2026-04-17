package com.steve.ai.action;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ResourceDependencyResolver: Một tiện ích giúp Steve giải quyết chuỗi nguyên liệu.
 * Ví dụ: Để có Đuốc, cần Gậy -> cần Ván gỗ -> cần Gỗ (khai thác).
 */
public class ResourceDependencyResolver {

    /**
     * Trả về danh sách Task cần thực hiện để có được vật phẩm mục tiêu.
     * Thứ tự trong list là thứ tự thực hiện (từ thu thập cơ bản đến craft cuối).
     */
    public static List<Task> resolve(SteveEntity steve, Item target, int quantity) {
        List<Task> buildOrder = new ArrayList<>();
        
        if (target == Items.TORCH) {
            resolveTorch(steve, quantity, buildOrder);
        } else if (target == Items.STICK) {
            resolveStick(steve, quantity, buildOrder);
        } else if (target == Items.OAK_PLANKS) {
            resolvePlanks(steve, quantity, buildOrder);
        } else if (isWoodItem(target)) {
            resolveWoodBase(steve, quantity, buildOrder);
        } else if (isPickaxe(target)) {
            resolvePickaxe(steve, target, buildOrder);
        }
        
        return buildOrder;
    }

    private static void resolveTorch(SteveEntity steve, int quantity, List<Task> order) {
        var inv = steve.getMemory().getInventory();
        
        // Cần Stick + (Coal hoặc Charcoal)
        if (!inv.hasItem(Items.STICK, 1)) {
            resolveStick(steve, 4, order);
        }
        
        if (!inv.hasItem(Items.COAL, 1) && !inv.hasItem(Items.CHARCOAL, 1)) {
            // Priority 1: Tìm Coal ore
            order.add(0, new Task("mine", Map.of("block", "coal_ore", "quantity", 1)));
        }
        
        order.add(new Task("craft", Map.of("item", "torch", "quantity", quantity)));
    }

    private static void resolveStick(SteveEntity steve, int quantity, List<Task> order) {
        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(Items.STICK, quantity)) return;

        if (!inv.hasItem(Items.OAK_PLANKS, 2)) {
            resolvePlanks(steve, 4, order);
        }
        order.add(new Task("craft", Map.of("item", "stick", "quantity", Math.max(4, quantity))));
    }

    private static void resolvePlanks(SteveEntity steve, int quantity, List<Task> order) {
        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(Items.OAK_PLANKS, quantity)) return;

        if (!hasAnyLog(inv)) {
            order.add(new Task("mine", Map.of("block", "oak_log", "quantity", 1)));
        }
        order.add(new Task("craft", Map.of("item", "oak_planks", "quantity", Math.max(4, quantity))));
    }

    private static void resolveWoodBase(SteveEntity steve, int quantity, List<Task> order) {
        var inv = steve.getMemory().getInventory();
        if (hasAnyLog(inv)) return; // Already have logs

        order.add(new Task("mine", Map.of("block", "oak_log", "quantity", (quantity / 4) + 1)));
    }

    private static void resolvePickaxe(SteveEntity steve, Item pickaxe, List<Task> order) {
        var inv = steve.getMemory().getInventory();
        
        // ── Wooden Pickaxe ──────────────────────────────────────────────────
        if (pickaxe == Items.WOODEN_PICKAXE) {
            resolveStick(steve, 4, order);
            resolvePlanks(steve, 4, order);
            order.add(new Task("craft", Map.of("item", "wooden_pickaxe", "quantity", 1)));
        } 
        // ── Stone Pickaxe ───────────────────────────────────────────────────
        else if (pickaxe == Items.STONE_PICKAXE) {
            if (!inv.hasItem(Items.COBBLESTONE, 3)) {
                order.add(0, new Task("mine", Map.of("block", "stone", "quantity", 3)));
            }
            resolveStick(steve, 2, order);
            order.add(new Task("craft", Map.of("item", "stone_pickaxe", "quantity", 1)));
        }
        // ── Iron Pickaxe ────────────────────────────────────────────────────
        else if (pickaxe == Items.IRON_PICKAXE) {
            if (!inv.hasItem(Items.IRON_INGOT, 3)) {
                // If has raw iron, needs smelting
                if (inv.hasItem(Items.RAW_IRON, 3)) {
                    order.add(0, new Task("smelt", Map.of("item", "raw_iron", "quantity", 3)));
                } else {
                    order.add(0, new Task("mine", Map.of("block", "iron_ore", "quantity", 3)));
                }
            }
            resolveStick(steve, 2, order);
            order.add(new Task("craft", Map.of("item", "iron_pickaxe", "quantity", 1)));
        }
        // ── Diamond Pickaxe ─────────────────────────────────────────────────
        else if (pickaxe == Items.DIAMOND_PICKAXE) {
            if (!inv.hasItem(Items.DIAMOND, 3)) {
                order.add(0, new Task("mine", Map.of("block", "diamond_ore", "quantity", 3)));
            }
            resolveStick(steve, 2, order);
            order.add(new Task("craft", Map.of("item", "diamond_pickaxe", "quantity", 1)));
        }
    }

    private static boolean isWoodItem(Item item) {
        return item == Items.OAK_PLANKS || item == Items.STICK || item == Items.CRAFTING_TABLE;
    }

    private static boolean isPickaxe(Item item) {
        return item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE || item == Items.IRON_PICKAXE;
    }

    private static boolean hasAnyLog(com.steve.ai.memory.SteveInventory inv) {
        return inv.hasItem(Items.OAK_LOG, 1) || inv.hasItem(Items.BIRCH_LOG, 1) 
            || inv.hasItem(Items.SPRUCE_LOG, 1) || inv.hasItem(Items.JUNGLE_LOG, 1)
            || inv.hasItem(Items.ACACIA_LOG, 1) || inv.hasItem(Items.DARK_OAK_LOG, 1);
    }
}
