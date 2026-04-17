package com.steve.ai.personality;

import com.steve.ai.action.Task;
import com.steve.ai.llm.AgentObservation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SurvivalMonitorImpl implements SurvivalMonitor {

    // Cooldown để tránh spam — mỗi state chỉ trigger 1 lần mỗi 600 ticks (30 giây)
    private final Map<SurvivalState, Long> lastTriggered = new java.util.HashMap<>();
    private long currentTick = 0;
    private static final long COOLDOWN_TICKS = 600;

    public void setCurrentTick(long tick) {
        this.currentTick = tick;
    }

    @Override
    public SurvivalState evaluate(AgentObservation obs) {
        // Thresholds based on personality
        int fleeHp = 10;
        int hungerThreshold = 6;
        int lowHpThreshold = 8;

        if ("BRAVE".equals(obs.personalityType)) {
            fleeHp = 6;
            lowHpThreshold = 4;
            hungerThreshold = 4;
        } else if ("SERIOUS".equals(obs.personalityType) || "CALM".equals(obs.personalityType)) {
            fleeHp = 12;
            lowHpThreshold = 10;
        }

        if (obs.steveHealth < fleeHp && obs.countHostileEntities() > 0) {
            return SurvivalState.UNDER_ATTACK;
        }
        if (!obs.isDaytime && obs.countHostileEntities() > 0) {
            return SurvivalState.NIGHT_DANGER;
        }
        if (obs.steveHunger < hungerThreshold) {
            return SurvivalState.HUNGRY;
        }
        if (obs.steveHealth < lowHpThreshold) {
            return SurvivalState.LOW_HEALTH;
        }
        return SurvivalState.SAFE;
    }

    @Override
    public boolean shouldInterruptCurrentTask(SurvivalState state) {
        if (state == SurvivalState.SAFE) return false;
        // Kiểm tra cooldown — không interrupt liên tục
        long last = lastTriggered.getOrDefault(state, 0L);
        if (currentTick - last < COOLDOWN_TICKS) return false;
        // Chỉ interrupt khi thực sự nguy hiểm
        if (state != SurvivalState.UNDER_ATTACK && state != SurvivalState.NIGHT_DANGER) return false;
        lastTriggered.put(state, currentTick);
        return true;
    }

    @Override
    public Optional<SurvivalAction> getRecommendedAction(SurvivalState state) {
        if (state == SurvivalState.UNDER_ATTACK) {
            return Optional.of(new SurvivalAction(
                "guard_player", 10,
                List.of(new Task("attack", Map.of("target", "hostile"))),
                "Cẩn thận! Tao bảo vệ mày!"
            ));
        } else if (state == SurvivalState.NIGHT_DANGER) {
            // Tự động: mine wood → craft planks → craft sticks → craft sword
            return Optional.of(new SurvivalAction(
                "arm_self", 8,
                List.of(
                    new Task("mine", Map.of("block", "oak_log", "quantity", "3")),
                    new Task("craft", Map.of("item", "oak_planks", "quantity", "12")),
                    new Task("craft", Map.of("item", "stick", "quantity", "8")),
                    new Task("craft", Map.of("item", "wooden_sword", "quantity", "1")),
                    new Task("attack", Map.of("target", "hostile"))
                ),
                "Đêm rồi! Tao đi kiếm gỗ làm vũ khí!"
            ));
        } else if (state == SurvivalState.HUNGRY) {
            return Optional.of(new SurvivalAction(
                "find_food", 6,
                List.of(new Task("gather", Map.of("resource", "wheat", "quantity", "5"))),
                "Mày đói rồi, để tao kiếm đồ ăn!"
            ));
        } else if (state == SurvivalState.LOW_HEALTH) {
            return Optional.of(new SurvivalAction(
                "flee", 7,
                List.of(),
                "Máu tao thấp, cần nghỉ ngơi!"
            ));
        }
        return Optional.empty();
    }
}
