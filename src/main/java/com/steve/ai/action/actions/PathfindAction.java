package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;

@SuppressWarnings("null") // Minecraft API (blockPosition, closerThan) guaranteed non-null at runtime
public class PathfindAction extends BaseAction {
    private BlockPos targetPos;
    private int ticksRunning;
    private static final int MAX_TICKS = 1200; // 60 seconds

    public PathfindAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);

        // If y=0 and x=0 and z=0, it's likely a bad LLM response — fail fast
        if (x == 0 && y == 0 && z == 0) {
            result = ActionResult.failure("Invalid pathfind target (0,0,0)");
            return;
        }

        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;

        steve.setFlying(false);
        steve.getNavigation().moveTo(x, y, z, 1.2);
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (steve.blockPosition().closerThan(targetPos, 3.0)) {
            result = ActionResult.success("Reached " + targetPos);
            return;
        }

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Pathfinding timeout");
            return;
        }

        // Re-issue move if navigation stopped (but not reached)
        if (steve.getNavigation().isDone()) {
            steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.2);
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Pathfind to " + targetPos;
    }

    @Override
    public java.util.EnumSet<com.steve.ai.action.ActionSlot> getRequiredSlots() {
        return java.util.EnumSet.of(com.steve.ai.action.ActionSlot.LOCOMOTION);
    }
}

