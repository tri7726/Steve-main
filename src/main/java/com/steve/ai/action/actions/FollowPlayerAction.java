package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

@SuppressWarnings("null") // Minecraft API (getBoundingBox, getEntitiesOfClass, distanceTo) guaranteed non-null at runtime
public class FollowPlayerAction extends BaseAction {
    private String playerName;
    private Player targetPlayer;
    private String mode; // "normal", "bodyguard"
    private int ticksRunning;
    private static final int MAX_TICKS = 12000; // 10 minutes
    private static final double FOLLOW_DIST = 4.0;  // start following at this distance
    private static final double STOP_DIST   = 2.5;  // stop when this close

    public FollowPlayerAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        playerName = task.getStringParameter("player");
        mode = task.getStringParameter("mode", "bodyguard");
        ticksRunning = 0;
        steve.setFlying(false);
        findPlayer();

        if (targetPlayer == null) {
            // Try nearest player as fallback
            targetPlayer = findNearest();
        }

        if (targetPlayer == null) {
            result = ActionResult.failure("Player not found: " + playerName);
        } else {
            playerName = targetPlayer.getName().getString();
            steve.sendChatMessage("Đang theo " + playerName + "!");
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.success("Stopped following");
            return;
        }

        // Re-find if lost
        if (targetPlayer == null || !targetPlayer.isAlive() || targetPlayer.isRemoved()) {
            targetPlayer = findNearest();
            if (targetPlayer == null) {
                result = ActionResult.failure("Lost player");
                return;
            }
            playerName = targetPlayer.getName().getString();
        }

        // ── Bodyguard Logic: Protect player from nearby hostiles ─────────────
        if ("bodyguard".equals(mode) && ticksRunning % 20 == 0) {
            checkAndProtectPlayer();
        }

        double dist = steve.distanceTo(targetPlayer);
        if (dist > FOLLOW_DIST) {
            // Speed up if far away
            double speed = dist > 16 ? 1.8 : 1.2;
            steve.getNavigation().moveTo(targetPlayer, speed);
        } else if (dist < STOP_DIST) {
            steve.getNavigation().stop();
        }
        // Look at player
        steve.getLookControl().setLookAt(targetPlayer, 30f, 30f);
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Follow player " + playerName;
    }

    @Override
    public java.util.EnumSet<com.steve.ai.action.ActionSlot> getRequiredSlots() {
        return java.util.EnumSet.of(com.steve.ai.action.ActionSlot.LOCOMOTION);
    }

    private void findPlayer() {
        if (playerName == null || playerName.isBlank()) return;
        String lower = playerName.toLowerCase();
        // Skip placeholder names from LLM
        if (lower.contains("player") || lower.contains("name") || lower.equals("me")
                || lower.equals("you") || lower.equals("null")) {
            targetPlayer = findNearest();
            return;
        }
        for (Player p : steve.level().players()) {
            if (p.getName().getString().equalsIgnoreCase(playerName)) {
                targetPlayer = p;
                return;
            }
        }
        // Partial match
        for (Player p : steve.level().players()) {
            if (p.getName().getString().toLowerCase().contains(lower)) {
                targetPlayer = p;
                return;
            }
        }
    }

    private Player findNearest() {
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player p : steve.level().players()) {
            if (!p.isAlive() || p.isRemoved() || p.isSpectator()) continue;
            double d = steve.distanceTo(p);
            if (d < nearestDist) { nearestDist = d; nearest = p; }
        }
        return nearest;
    }

    private void checkAndProtectPlayer() {
        if (targetPlayer == null) return;
        
        // Find entities targeting the player or close enough to be a threat
        net.minecraft.world.phys.AABB box = targetPlayer.getBoundingBox().inflate(6.0);
        List<net.minecraft.world.entity.monster.Monster> threats = 
            steve.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, box);
        
        for (net.minecraft.world.entity.monster.Monster m : threats) {
            if (m.getTarget() == targetPlayer || m.distanceTo(targetPlayer) < 3.0) {
                steve.sendChatMessage("Tránh xa " + playerName + " ra!");
                // Hand off to CombatAction for a moment
                com.steve.ai.action.Task combatTask = new com.steve.ai.action.Task("attack", 
                    java.util.Map.of("target", "specific", "entityId", m.getId()));
                steve.getActionExecutor().enqueue(combatTask);
                // The executor will handle re-enqueuing this FollowPlayerAction if it was interrupted
                return; 
            }
        }
    }
}

