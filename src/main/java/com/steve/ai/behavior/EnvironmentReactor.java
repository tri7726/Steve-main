package com.steve.ai.behavior;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * Steve tự động phản ứng với môi trường.
 *
 * Nâng cấp:
 * - ThreatLevel enum: SAFE / LOW / HIGH / CRITICAL — phản ứng khác nhau theo mức độ
 * - Storm/rain detection: cảnh báo sét đánh khi đứng ngoài trời
 * - Flee chỉ trigger khi threat CRITICAL, không flee khi chỉ có 1-2 mob
 * - Tự attack khi threat HIGH thay vì chỉ chạy
 * - Flee cooldown reset nếu đã về đến gần home
 *
 * Gọi tick() mỗi 20 ticks từ SteveEntity.
 */
@SuppressWarnings("null")
public class EnvironmentReactor {

    private enum ThreatLevel { SAFE, LOW, HIGH, CRITICAL }

    private static final int HOSTILE_WARN_RADIUS = 16;
    private static final int FLEE_HOSTILE_COUNT  = 5;   // Nâng lên 5 — flee chỉ khi thực sự nguy hiểm
    private static final int FIGHT_HOSTILE_COUNT = 2;   // Tự fight khi có 2-4 mob
    private static final int WARN_COOLDOWN_TICKS = 200;
    private static final int LAVA_SCAN_RADIUS    = 4;

    private int warnCooldown     = 0;
    private int fleeHomeCooldown = 0;
    private boolean hasWarnedNight = false;
    private boolean hasWarnedStorm = false;

    // Cache scan entity
    private int    cachedHostileCount  = 0;
    private String cachedClosestThreat = null;
    private int    cacheAge            = Integer.MAX_VALUE;

    public void tick(SteveEntity steve, ActionExecutor executor) {
        if (steve.level().isClientSide) return;

        warnCooldown     = Math.max(0, warnCooldown - 20);
        fleeHomeCooldown = Math.max(0, fleeHomeCooldown - 20);
        cacheAge        += 20;

        long time    = steve.level().getDayTime() % 24000;
        boolean isNight = time >= 13000;

        if (cacheAge >= 20) scanNearbyHostiles(steve);

        ThreatLevel threat = evaluateThreat();

        checkAndWarnDanger(steve, isNight, threat);
        checkStormWarning(steve);
        reactToThreat(steve, executor, threat, isNight);

        if (!isNight) { hasWarnedNight = false; }
        if (!steve.level().isRaining()) { hasWarnedStorm = false; }
    }

    // ── Threat evaluation ─────────────────────────────────────────────────────

    private ThreatLevel evaluateThreat() {
        if (cachedHostileCount == 0)                        return ThreatLevel.SAFE;
        if (cachedHostileCount < FIGHT_HOSTILE_COUNT)       return ThreatLevel.LOW;
        if (cachedHostileCount < FLEE_HOSTILE_COUNT)        return ThreatLevel.HIGH;
        return ThreatLevel.CRITICAL;
    }

    // ── Entity scan ───────────────────────────────────────────────────────────

    private void scanNearbyHostiles(SteveEntity steve) {
        AABB box = steve.getBoundingBox().inflate(HOSTILE_WARN_RADIUS);
        List<Entity> nearby = steve.level().getEntities(steve, box);

        int count = 0;
        String closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity e : nearby) {
            if (e instanceof Monster m) {
                count++;
                double d = m.distanceTo(steve);
                if (d < closestDist) { closestDist = d; closest = e.getType().toShortString(); }
            }
        }
        cachedHostileCount  = count;
        cachedClosestThreat = closest;
        cacheAge = 0;
    }

    // ── Warn ──────────────────────────────────────────────────────────────────

    private void checkAndWarnDanger(SteveEntity steve, boolean isNight, ThreatLevel threat) {
        if (warnCooldown > 0) return;

        // Lava scan chỉ khi cooldown hết (tốn CPU)
        if (threat == ThreatLevel.SAFE && isLavaClose(steve)) {
            steve.sendChatMessage("⚠ Cẩn thận! Có lava gần đây!");
            warnCooldown = WARN_COOLDOWN_TICKS;
            return;
        }

        switch (threat) {
            case LOW -> {
                steve.sendChatMessage("👀 Có " + cachedHostileCount + " mob gần đây (" + cachedClosestThreat + "), cẩn thận!");
                warnCooldown = WARN_COOLDOWN_TICKS;
            }
            case HIGH -> {
                steve.sendChatMessage("⚠ " + cachedHostileCount + " mob! Đang chiến đấu...");
                warnCooldown = WARN_COOLDOWN_TICKS;
            }
            case CRITICAL -> {
                steve.sendChatMessage("🚨 QUÁ NHIỀU MOB (" + cachedHostileCount + ")! CHẠY!");
                warnCooldown = WARN_COOLDOWN_TICKS;
            }
            default -> {
                if (isNight && !hasWarnedNight) {
                    hasWarnedNight = true;
                    steve.sendChatMessage("🌙 Trời tối rồi, cẩn thận mob nhé!");
                    warnCooldown = WARN_COOLDOWN_TICKS;
                }
            }
        }
    }

    // ── Storm warning ─────────────────────────────────────────────────────────

    private void checkStormWarning(SteveEntity steve) {
        if (hasWarnedStorm || warnCooldown > 0) return;
        if (!steve.level().isThundering()) return;

        // Chỉ cảnh báo khi đứng ngoài trời (sky light cao)
        int skyLight = steve.level().getBrightness(
            net.minecraft.world.level.LightLayer.SKY, steve.blockPosition());
        if (skyLight >= 10) {
            steve.sendChatMessage("⛈ Có sét! Nên vào trong tránh bị đánh!");
            hasWarnedStorm = true;
            warnCooldown = WARN_COOLDOWN_TICKS * 3; // 30 giây cooldown cho storm
        }
    }

    // ── React to threat ───────────────────────────────────────────────────────

    private void reactToThreat(SteveEntity steve, ActionExecutor executor,
                                ThreatLevel threat, boolean isNight) {
        switch (threat) {
            case LOW -> {
                // LOW: tự trang bị vũ khí nếu chưa có, không attack ngay
                if (steve.getMainHandItem().isEmpty()) {
                    executor.enqueue(new Task("attack", Map.of("target", "hostile")));
                }
            }
            case HIGH -> {
                // HIGH: tự fight
                executor.enqueue(new Task("attack", Map.of("target", "hostile")));
            }
            case CRITICAL -> {
                if (fleeHomeCooldown == 0) checkAndFleeHome(steve, executor);
            }
            default -> {}
        }
    }

    // ── Lava scan ─────────────────────────────────────────────────────────────

    private boolean isLavaClose(SteveEntity steve) {
        BlockPos origin = steve.blockPosition();
        int r = LAVA_SCAN_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    if (steve.level().getBlockState(origin.offset(dx, dy, dz)).getBlock() == Blocks.LAVA)
                        return true;
                }
            }
        }
        return false;
    }

    // ── Flee home ─────────────────────────────────────────────────────────────

    private void checkAndFleeHome(SteveEntity steve, ActionExecutor executor) {
        var waypoints = steve.getMemory().getWaypoints();
        var home = waypoints.get("home").orElseGet(() -> waypoints.get("base").orElse(null));

        if (home != null) {
            // Nếu đã gần home rồi thì không cần flee nữa
            double distToHome = steve.blockPosition().distSqr(home);
            if (distToHome < 100) { // 10 block
                fleeHomeCooldown = 200;
                return;
            }
            steve.sendChatMessage("🚨 Quá nhiều mob! Chạy về nhà!");
            executor.enqueue(new Task("pathfind", Map.of(
                "destination", home.getX() + " " + home.getY() + " " + home.getZ()
            )));
            fleeHomeCooldown = 600;
        } else {
            Player nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Player p : steve.level().players()) {
                double d = p.distanceTo(steve);
                if (d < nearestDist) { nearestDist = d; nearest = p; }
            }
            if (nearest != null && nearestDist > 8) {
                steve.sendChatMessage("🚨 Quá nhiều mob! Chạy lại gần " + nearest.getName().getString() + "!");
                executor.enqueue(new Task("follow", Map.of("player", nearest.getName().getString())));
                fleeHomeCooldown = 400;
            }
        }
    }
}
