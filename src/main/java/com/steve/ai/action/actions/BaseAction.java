package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

public abstract class BaseAction {
    protected final SteveEntity steve;
    protected final Task task;
    protected ActionResult result;
    protected boolean started = false;
    protected boolean cancelled = false;

    public BaseAction(SteveEntity steve, Task task) {
        this.steve = steve;
        this.task = task;
    }

    public Task getTask() {
        return task;
    }

    public void start() {
        if (started) return;
        started = true;
        onStart();
    }

    public void tick() {
        if (!started || isComplete()) return;
        onTick();
    }

    public void cancel() {
        cancelled = true;
        result = ActionResult.failure("Action cancelled");
        onCancel();
    }

    public boolean isComplete() {
        return result != null || cancelled;
    }

    public ActionResult getResult() {
        return result;
    }

    protected abstract void onStart();
    protected abstract void onTick();
    protected abstract void onCancel();
    
    public abstract String getDescription();
    
    /**
     * Define which slots this action occupies.
     * By default, it occupies both Locomotion and Interaction for backward compatibility.
     */
    public java.util.EnumSet<com.steve.ai.action.ActionSlot> getRequiredSlots() {
        return java.util.EnumSet.of(com.steve.ai.action.ActionSlot.LOCOMOTION, com.steve.ai.action.ActionSlot.INTERACTION);
    }
}

