package com.steve.ai.action.actions;

import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * GatherResourceAction - delegates to MineBlockAction for ore/resource types.
 * Acts as a semantic alias so LLM can say "gather" and it maps cleanly to Mining.
 */
@SuppressWarnings("null")
public class GatherResourceAction extends BaseAction {
    private String resourceType;
    private int quantity;
    private BaseAction delegated;

    public GatherResourceAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        resourceType = task.getStringParameter("resource");
        quantity = task.getIntParameter("quantity", 8);

        if ("food".equalsIgnoreCase(resourceType) || "thuc_an".equalsIgnoreCase(resourceType) || "meat".equalsIgnoreCase(resourceType)) {
            delegated = new HuntingAction(steve, new Task("hunt", Map.of("quantity", Math.max(2, quantity / 2))));
            delegated.start();
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("block", resourceType);
        params.put("quantity", quantity);
        Task mineTask = new Task("mine", params);

        delegated = new MineBlockAction(steve, mineTask);
        delegated.start();
    }

    @Override
    protected void onTick() {
        if (delegated == null) return;

        if (delegated.isComplete()) {
            result = delegated.getResult();
        } else {
            delegated.tick();
        }
    }

    @Override
    protected void onCancel() {
        if (delegated != null) delegated.cancel();
    }

    @Override
    public String getDescription() {
        return "Gather " + quantity + " " + resourceType;
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }
}


