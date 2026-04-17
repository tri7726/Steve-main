package com.steve.ai.memory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;

/**
 * SteveInventory: a virtual inventory for Steve (PathfinderMob).
 * Tracks items Steve has "collected" during tasks so ChestAction can store them.
 * Max 54 stacks (double chest size).
 */
@SuppressWarnings("null")
public class SteveInventory {
    // LinkedList wrapped với synchronized — game thread và async action threads đều truy cập
    private final List<ItemStack> items = java.util.Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_STACKS = 54;

    public synchronized void addItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        // Try to merge with existing stack first
        synchronized (items) {
            for (ItemStack existing : items) {
                if (ItemStack.isSameItemSameTags(existing, stack)
                        && existing.getCount() < existing.getMaxStackSize()) {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    int toAdd = Math.min(space, stack.getCount());
                    existing.grow(toAdd);
                    stack.shrink(toAdd);
                    if (stack.isEmpty()) return;
                }
            }
            // New slot
            if (items.size() < MAX_STACKS) {
                items.add(stack.copy());
            }
        }
    }

    public synchronized ItemStack takeFirst() {
        if (items.isEmpty()) return null;
        // Trả về copy để tránh caller modify stack trong inventory
        ItemStack first = items.remove(0);
        return first.copy();
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized boolean isFull() {
        return items.size() >= MAX_STACKS;
    }

    public synchronized int countItem(net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : items) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    public synchronized List<String> getSummary() {
        List<String> result = new ArrayList<>();
        for (ItemStack stack : items) {
            result.add(stack.getCount() + "x " + stack.getItem().getDescription().getString());
        }
        return result;
    }

    /** Trả về snapshot copy để tránh ConcurrentModificationException khi iterate bên ngoài */
    public synchronized List<ItemStack> getItems() {
        return new ArrayList<>(items);
    }

    public synchronized void clear() {
        items.clear();
    }

    public synchronized boolean hasItem(net.minecraft.world.item.Item item, int count) {
        int total = 0;
        for (ItemStack stack : items) {
            if (stack.is(item)) total += stack.getCount();
            if (total >= count) return true;
        }
        return false;
    }

    /**
     * Trừ item khỏi inventory theo atomic operation.
     * Trả về số lượng thực sự đã trừ được.
     */
    public synchronized int removeItem(net.minecraft.world.item.Item item, int count) {
        int remaining = count;
        Iterator<ItemStack> it = items.iterator();
        while (it.hasNext() && remaining > 0) {
            ItemStack stack = it.next();
            if (stack.is(item)) {
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) it.remove();
            }
        }
        return count - remaining;
    }

    /** Tìm và trả về ItemStack đầu tiên chứa item cụ thể (dùng cho survival MLG/Totem) */
    public synchronized ItemStack findFirstItem(net.minecraft.world.item.Item item) {
        for (ItemStack stack : items) {
            if (stack.is(item)) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** Tìm mảnh giáp tốt nhất cho một slot cụ thể trong inventory */
    public synchronized ItemStack findBestArmorItem(net.minecraft.world.entity.EquipmentSlot slot) {
        ItemStack best = ItemStack.EMPTY;
        int bestDefense = -1;

        for (ItemStack stack : items) {
            if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor) {
                if (armor.getEquipmentSlot() == slot) {
                    int defense = armor.getDefense();
                    if (defense > bestDefense) {
                        best = stack;
                        bestDefense = defense;
                    }
                }
            }
        }
        return best;
    }

    /** Danh sách vật phẩm bị coi là "rác" trong survival */
    private static final Set<Item> TRASH_ITEMS = Set.of(
        Items.ROTTEN_FLESH,
        Items.POISONOUS_POTATO,
        Items.SPIDER_EYE,
        Items.GRAVEL,
        Items.ANDESITE,
        Items.DIORITE,
        Items.GRANITE
    );

    /** Dọn dẹp túi đồ nếu sắp đầy (> 50 slot) */
    public synchronized void cleanTrash(com.steve.ai.entity.SteveEntity steve) {
        if (items.size() < 50) return;

        Map<Item, Integer> countMap = new HashMap<>();
        int removedCount = 0;
        
        synchronized (items) {
            Iterator<ItemStack> it = items.iterator();
            while (it.hasNext()) {
                ItemStack stack = it.next();
                Item item = stack.getItem();
                
                // 1. Sử dụng danh sách rác đã định nghĩa
                if (TRASH_ITEMS.contains(item)) {
                    // Châm chước giữ lại một ít nguyên liệu xây dựng (Dirt/Cobble/Gravel) 
                    // nếu chúng nằm trong danh sách TRASH nhưng có ích
                    if (item == Items.COBBLESTONE || item == Items.DIRT || item == Items.GRAVEL || 
                        item == Items.ANDESITE || item == Items.DIORITE || item == Items.GRANITE) {
                        
                        int currentCount = countMap.getOrDefault(item, 0);
                        if (currentCount >= 2) { // Giữ tối đa 2 stacks mỗi loại
                            it.remove();
                            removedCount++;
                        } else {
                            countMap.put(item, currentCount + 1);
                        }
                    } else {
                        // Rác tuyệt đối (Rotten flesh, etc.)
                        it.remove();
                        removedCount++;
                    }
                }
            }
        }

        if (removedCount > 0) {
            steve.sendChatMessage("Túi đầy quá, để tao lọc bớt rác thải công nghiệp cho sạch chỗ.");
        }
    }

    // ── NBT Serialization ─────────────────────────────────────────────────────

    public synchronized void saveToNBT(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                stack.save(itemTag);
                list.add(itemTag);
            }
        }
        tag.put("Items", list);
    }

    public synchronized void loadFromNBT(CompoundTag tag) {
        items.clear();
        if (tag.contains("Items")) {
            ListTag list = tag.getList("Items", 10);
            for (int i = 0; i < list.size(); i++) {
                ItemStack stack = ItemStack.of(list.getCompound(i));
                if (!stack.isEmpty()) items.add(stack);
            }
        }
    }
}
