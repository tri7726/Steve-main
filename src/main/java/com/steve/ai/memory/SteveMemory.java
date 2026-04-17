package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

@SuppressWarnings("null")
public class SteveMemory {
    private String currentGoal;
    private final Queue<String> taskQueue;
    private final LinkedList<String> recentActions;
    private final VectorStore vectorStore;
    private final SteveInventory inventory;
    private final WaypointMemory waypoints;
    private final ExplorationMemory explorationMemory;
    private final SelfReflectionEngine reflectionEngine;
    private static final int MAX_RECENT_ACTIONS = 20;

    public SteveMemory(SteveEntity steve) {
        this.currentGoal = "";
        this.taskQueue = new LinkedList<>();
        this.recentActions = new LinkedList<>();
        
        File memoryDir = new File(FMLPaths.GAMEDIR.get().toFile(), "steve_memory");
        this.vectorStore = new VectorStore(memoryDir, steve.getSteveName());
        this.inventory = new SteveInventory();
        this.waypoints = new WaypointMemory();
        this.explorationMemory = new ExplorationMemory();
        this.reflectionEngine = new SelfReflectionEngine(this.vectorStore);
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public void setCurrentGoal(String goal) {
        this.currentGoal = goal;
    }

    public void addAction(String action) {
        if (action == null) return;
        // Truncate trước khi lưu vào VectorStore để tránh prompt injection
        // và giữ memory gọn gàng
        String sanitized = action.length() > 200 ? action.substring(0, 200) : action;
        recentActions.addLast(sanitized);
        if (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.removeFirst();
        }
        vectorStore.addMemory(sanitized);
    }
    
    public List<String> searchLongTermMemory(String query) {
        return vectorStore.search(query, 5);
    }

    public SteveInventory getInventory() {
        return inventory;
    }

    public WaypointMemory getWaypoints() {
        return waypoints;
    }

    public ExplorationMemory getExplorationMemory() {
        return explorationMemory;
    }

    public VectorStore getVectorStore() {
        return vectorStore;
    }

    public SelfReflectionEngine getReflectionEngine() {
        return reflectionEngine;
    }

    public List<String> getRecentActions(int count) {
        List<String> result = new ArrayList<>();
        
        int startIndex = Math.max(0, recentActions.size() - count);
        for (int i = startIndex; i < recentActions.size(); i++) {
            result.add(recentActions.get(i));
        }
        
        return result;
    }

    public void clearTaskQueue() {
        taskQueue.clear();
        currentGoal = "";
    }

    public void saveToNBT(CompoundTag tag) {
        tag.putString("CurrentGoal", currentGoal);
        
        ListTag actionsList = new ListTag();
        for (String action : recentActions) {
            actionsList.add(StringTag.valueOf(action));
        }
        tag.put("RecentActions", actionsList);

        // Lưu inventory để không mất đồ khi tắt server
        CompoundTag inventoryTag = new CompoundTag();
        inventory.saveToNBT(inventoryTag);
        tag.put("Inventory", inventoryTag);

        // Lưu waypoints
        CompoundTag waypointTag = new CompoundTag();
        waypoints.saveToNBT(waypointTag);
        tag.put("Waypoints", waypointTag);

        // Lưu exploration memory
        CompoundTag explorationTag = new CompoundTag();
        explorationMemory.saveToNBT(explorationTag);
        tag.put("ExplorationMemory", explorationTag);
    }

    public void loadFromNBT(CompoundTag tag) {
        if (tag.contains("CurrentGoal")) {
            currentGoal = tag.getString("CurrentGoal");
        }
        
        if (tag.contains("RecentActions")) {
            recentActions.clear();
            ListTag actionsList = tag.getList("RecentActions", 8); // 8 = String type
            for (int i = 0; i < actionsList.size(); i++) {
                recentActions.add(actionsList.getString(i));
            }
        }

        if (tag.contains("Inventory")) {
            inventory.loadFromNBT(tag.getCompound("Inventory"));
        }

        if (tag.contains("Waypoints")) {
            waypoints.loadFromNBT(tag.getCompound("Waypoints"));
        }

        if (tag.contains("ExplorationMemory")) {
            explorationMemory.loadFromNBT(tag.getCompound("ExplorationMemory"));
        }
    }
}

