package com.steve.ai.behavior;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Social/Multiplayer features.
 * - Co-op building: chia phase cho nhiều Steve
 * - Team roles: TANK/DPS/SUPPORT/BUILDER
 * - PvP: Steve vs Steve
 * - Auto-trading với villager
 *
 * Instance per-world (không dùng static mutable state để tránh leak giữa các world).
 * Truy cập qua SteveMod.getMultiAgentCoordinator().
 */
@SuppressWarnings("null")
public class MultiAgentCoordinator {

    public enum TeamRole { TANK, DPS, SUPPORT, BUILDER }

    // Instance fields thay vì static — an toàn khi có nhiều world
    private final Map<String, TeamRole> steveRoles    = new ConcurrentHashMap<>();
    private final Map<String, String>   pvpChallenges = new ConcurrentHashMap<>();
    private final Set<String>           tradingSteves = ConcurrentHashMap.newKeySet();

    // ── Co-op Building ────────────────────────────────────────────────────────

    public void assignCoBuildTasks(List<SteveEntity> steves, String structure) {
        if (steves.isEmpty()) return;

        if (steves.size() == 1) {
            SteveEntity solo = steves.get(0);
            solo.sendChatMessage("Xây " + structure + " một mình thôi!");
            solo.getActionExecutor().enqueue(new Task("build", Map.of("structure", structure)));
            return;
        }

        String[] phases = {"foundation", "walls", "roof", "interior"};
        for (int i = 0; i < steves.size(); i++) {
            SteveEntity steve = steves.get(i);
            String phase = phases[Math.min(i, phases.length - 1)];
            steve.sendChatMessage("Co-op build: mình phụ trách " + phase + " của " + structure + "!");
            steve.getActionExecutor().enqueue(new Task("build", Map.of(
                "structure",     structure,
                "phase",         phase,
                "worker_index",  i,
                "total_workers", steves.size()
            )));
        }
        // Thông báo tổng quan
        steves.get(0).sendChatMessage("📋 Co-op: " + steves.size() + " Steve xây " + structure
                + " (" + String.join(", ", java.util.Arrays.copyOf(phases, Math.min(steves.size(), phases.length))) + ")");
    }

    // ── Team Roles ────────────────────────────────────────────────────────────

    public void assignRole(SteveEntity steve, TeamRole role) {
        steveRoles.put(steve.getSteveName(), role);
        String msg = switch (role) {
            case TANK    -> "Minh la TANK! Se dung truoc chiu don!";
            case DPS     -> "Minh la DPS! Se tan cong manh nhat!";
            case SUPPORT -> "Minh la SUPPORT! Se ho tro dong doi!";
            case BUILDER -> "Minh la BUILDER! Se xay cong trinh!";
        };
        steve.sendChatMessage(msg);
    }

    public void executeRoleBehavior(SteveEntity steve, ActionExecutor executor) {
        TeamRole role = steveRoles.getOrDefault(steve.getSteveName(), TeamRole.DPS);
        switch (role) {
            case TANK    -> executor.enqueue(new Task("attack", Map.of("target", "hostile")));
            case DPS     -> executor.enqueue(new Task("attack", Map.of("target", "hostile", "priority", "weakest")));
            case SUPPORT -> findWoundedAlly(steve).ifPresent(ally -> {
                var inv = steve.getMemory().getInventory();
                // Ưu tiên potion hồi máu, fallback về đồ ăn
                if (inv.hasItem(net.minecraft.world.item.Items.POTION, 1)) {
                    executor.enqueue(new Task("give", Map.of(
                        "player", ally.getSteveName(), "item", "potion", "quantity", 1
                    )));
                    steve.sendChatMessage("💊 Ném potion cho " + ally.getSteveName() + "!");
                } else {
                    executor.enqueue(new Task("give", Map.of(
                        "player", ally.getSteveName(), "item", "cooked_beef", "quantity", 2
                    )));
                    steve.sendChatMessage("🍖 Đưa đồ ăn cho " + ally.getSteveName() + "!");
                }
            });
            case BUILDER -> {} // Builder không tham chiến
        }
    }

    public TeamRole getRole(String steveName) {
        return steveRoles.getOrDefault(steveName, TeamRole.DPS);
    }

    // ── PvP (với timeout) ─────────────────────────────────────────────────────

    private static final int PVP_TIMEOUT_TICKS = 20 * 60 * 5; // 5 phút
    private final Map<String, Integer> pvpStartTicks = new ConcurrentHashMap<>();

    public void startPvP(SteveEntity challenger, SteveEntity target) {
        pvpChallenges.put(challenger.getSteveName(), target.getSteveName());
        pvpStartTicks.put(challenger.getSteveName(), 0);
        challenger.sendChatMessage("⚔ " + challenger.getSteveName() + " thách đấu " + target.getSteveName() + "!");
        target.sendChatMessage("⚔ " + target.getSteveName() + " chấp nhận thách đấu!");

        challenger.getActionExecutor().enqueue(new Task("attack", Map.of("target", target.getSteveName(), "mode", "pvp")));
        target.getActionExecutor().enqueue(new Task("attack", Map.of("target", challenger.getSteveName(), "mode", "pvp")));
    }

    public void endPvP(SteveEntity winner, SteveEntity loser) {
        pvpChallenges.remove(winner.getSteveName());
        pvpChallenges.remove(loser.getSteveName());
        pvpStartTicks.remove(winner.getSteveName());
        winner.sendChatMessage("🏆 " + winner.getSteveName() + " thắng! " + loser.getSteveName() + " thua rồi!");
    }

    /** Gọi mỗi 20 ticks để check timeout PvP. */
    public void tickPvP(java.util.Collection<SteveEntity> allSteves) {
        if (pvpChallenges.isEmpty()) return;
        pvpStartTicks.replaceAll((k, v) -> v + 20);

        pvpStartTicks.entrySet().removeIf(entry -> {
            if (entry.getValue() >= PVP_TIMEOUT_TICKS) {
                String challengerName = entry.getKey();
                pvpChallenges.remove(challengerName);
                // Tìm Steve để thông báo
                allSteves.stream()
                    .filter(s -> s.getSteveName().equals(challengerName))
                    .findFirst()
                    .ifPresent(s -> s.sendChatMessage("⏰ PvP timeout! Hòa nhau rồi."));
                return true;
            }
            return false;
        });
    }

    public boolean isInPvP(String steveName) {
        return pvpChallenges.containsKey(steveName) || pvpChallenges.containsValue(steveName);
    }

    // ── Trading ───────────────────────────────────────────────────────────────

    public void startAutoTrading(SteveEntity steve, ActionExecutor executor) {
        tradingSteves.add(steve.getSteveName());
        steve.sendChatMessage("Bat dau giao dich voi villager de kiem emerald!");
        executor.enqueue(new Task("pathfind", Map.of("destination", "village")));
        executor.enqueue(new Task("trade",    Map.of("item", "emerald", "mode", "buy")));
    }

    public void stopAutoTrading(SteveEntity steve) {
        tradingSteves.remove(steve.getSteveName());
        steve.sendChatMessage("Dung giao dich.");
    }

    public boolean isTrading(String steveName) {
        return tradingSteves.contains(steveName);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Optional<SteveEntity> findWoundedAlly(SteveEntity steve) {
        AABB box = steve.getBoundingBox().inflate(30);
        return steve.level().getEntities(steve, box).stream()
                .filter(e -> e instanceof SteveEntity)
                .map(e -> (SteveEntity) e)
                .filter(s -> s.getHealth() < s.getMaxHealth() * 0.5f)
                .min(Comparator.comparingDouble(SteveEntity::getHealth));
    }

    public List<SteveEntity> getAllSteves() {
        return new ArrayList<>(com.steve.ai.SteveMod.getSteveManager().getAllSteves());
    }

    /** Xoá toàn bộ state khi world unload. */
    public void clear() {
        steveRoles.clear();
        pvpChallenges.clear();
        pvpStartTicks.clear();
        tradingSteves.clear();
    }
}
