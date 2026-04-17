package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;
import java.util.Comparator;

/**
 * HuntingAction: Scans for nearby animals, kills them, and collects drops.
 * Used for autonomous food gathering.
 */
@SuppressWarnings("null")
public class HuntingAction extends BaseAction {
    private int targetQuantity;
    private int collectedCount;
    private Animal targetEntity;
    private int ticksRunning;
    private int ticksSinceLastKill = 0;
    private static final int MAX_TICKS = 1200; // 1 minute timeout for hunting
    private static final int SEARCH_RADIUS = 48;

    public HuntingAction(SteveEntity steve, Task task) {
        super(steve, task);
        this.targetQuantity = task.getIntParameter("quantity", 5);
    }

    @Override
    protected void onStart() {
        collectedCount = 0;
        ticksRunning = 0;
        ticksSinceLastKill = 0;
        steve.sendChatMessage("Bắt đầu đi săn tìm thức ăn!");
        findNextTarget();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastKill++;

        if (ticksRunning > MAX_TICKS) {
            finish(false, "Săn bắn quá lâu mà không xong.");
            return;
        }

        if (targetEntity == null || !targetEntity.isAlive()) {
            if (targetEntity != null && !targetEntity.isAlive()) {
                collectedCount++;
                steve.pickupNearbyItems(4.0);
            }
            if (collectedCount >= targetQuantity) {
                finish(true, "Đã săn được " + collectedCount + " mục tiêu.");
                return;
            }
            findNextTarget();
            if (targetEntity == null) {
                // If still null, maybe wander or finish if enough
                if (collectedCount > 0) {
                    finish(true, "Chỉ tìm thấy " + collectedCount + " động vật.");
                } else {
                    finish(false, "Không tìm thấy con vật nào quanh đây.");
                }
                return;
            }
        }

        double dist = steve.distanceToSqr(targetEntity);
        if (dist < 4.0) { // Reachable distance
            if (ticksRunning % 10 == 0) {
                steve.swing(InteractionHand.MAIN_HAND, true);
                steve.doHurtTarget(targetEntity);
                ticksSinceLastKill = 0;
            }
        } else {
            // Logic: Nếu truy đuổi quá lâu mà không tiếp cận được, bỏ mục tiêu
            if (ticksSinceLastKill > 400) {
                SteveMod.LOGGER.info("Steve '{}' gave up on fast target {} after {} ticks", 
                    steve.getSteveName(), targetEntity.getType().getDescriptionId(), ticksSinceLastKill);
                targetEntity = null;
                ticksSinceLastKill = 0;
                return;
            }

            // Navigate to animal
            if (ticksRunning % 20 == 0) {
                steve.getNavigation().moveTo(targetEntity, 1.3);
            }
            // If animal is stuck or too far, maybe skip?
            if (dist > 10000) { // Too far (>100 blocks)
                targetEntity = null;
            }
        }
    }

    private void findNextTarget() {
        AABB area = steve.getBoundingBox().inflate(SEARCH_RADIUS);
        List<Animal> animals = steve.level().getEntitiesOfClass(Animal.class, area, 
                e -> (e instanceof Cow || e instanceof Pig || e instanceof Sheep || e instanceof Chicken) && e.isAlive());
        
        targetEntity = animals.stream()
                .min(Comparator.comparingDouble(steve::distanceToSqr))
                .orElse(null);
        
        if (targetEntity != null) {
            SteveMod.LOGGER.info("Steve '{}' targeting {} for hunting", steve.getSteveName(), targetEntity.getType().getDescriptionId());
        }
    }

    private void finish(boolean success, String msg) {
        steve.getNavigation().stop();
        if (success) {
            result = ActionResult.success(msg);
            steve.sendChatMessage("Đi săn xong! " + msg);
        } else {
            result = ActionResult.failure(msg);
            steve.sendChatMessage("Săn thất bại: " + msg);
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Săn bắn " + (targetEntity != null ? targetEntity.getType().getDescriptionId() : "động vật") + " (" + collectedCount + "/" + targetQuantity + ")";
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }
}
