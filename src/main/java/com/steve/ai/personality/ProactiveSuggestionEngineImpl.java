package com.steve.ai.personality;

import com.steve.ai.action.Task;
import com.steve.ai.llm.AgentObservation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ProactiveSuggestionEngineImpl implements ProactiveSuggestionEngine {

    private final Map<SuggestionType, Long> cooldownMap = new HashMap<>();
    private long currentTick = 0L;

    public ProactiveSuggestionEngineImpl() {}

    public void setCurrentTick(long tick) {
        this.currentTick = tick;
    }

    @Override
    public Optional<Suggestion> checkTriggers(AgentObservation obs, RelationshipLevel level) {
        if (level == RelationshipLevel.STRANGER) {
            return Optional.empty();
        }

        SuggestionType[] triggers = {
            SuggestionType.LOW_TORCH,
            SuggestionType.NO_WEAPON,
            SuggestionType.LOW_FOOD,
            SuggestionType.NIGHT_APPROACHING,
            SuggestionType.NEARBY_RESOURCE
        };

        for (SuggestionType triggerType : triggers) {
            if (!isCooldownExpired(triggerType)) {
                continue;
            }
            Optional<Suggestion> suggestion = evaluateTrigger(triggerType, obs);
            if (suggestion.isPresent()) {
                recordSuggestion(triggerType);
                return suggestion;
            }
        }

        return Optional.empty();
    }

    private Optional<Suggestion> evaluateTrigger(SuggestionType type, AgentObservation obs) {
        switch (type) {
            case LOW_TORCH: {
                int torchCount = countItem(obs.inventorySummary, "torch");
                if (torchCount < 8) {
                    return Optional.of(new Suggestion(
                        SuggestionType.LOW_TORCH,
                        "Này, anh gần hết torch rồi, để tao craft thêm nhé?",
                        List.of(new Task("craft", Map.of("item", "torch", "quantity", "16"))),
                        60
                    ));
                }
                break;
            }
            case NO_WEAPON: {
                boolean hasWeapon = obs.inventorySummary != null &&
                    (obs.inventorySummary.contains("sword") || obs.inventorySummary.contains("axe"));
                if (!hasWeapon && !obs.isDaytime) {
                    return Optional.of(new Suggestion(
                        SuggestionType.NO_WEAPON,
                        "Tao thấy mình chưa có vũ khí, để tao craft cái kiếm nhé?",
                        List.of(new Task("craft", Map.of("item", "iron_sword", "quantity", "1"))),
                        80
                    ));
                }
                break;
            }
            case LOW_FOOD: {
                if (obs.getPlayerFoodLevel() < 5) {
                    return Optional.of(new Suggestion(
                        SuggestionType.LOW_FOOD,
                        "Mày đói rồi kìa, để tao kiếm đồ ăn nhé?",
                        List.of(new Task("gather", Map.of("item", "food"))),
                        60
                    ));
                }
                break;
            }
            case NIGHT_APPROACHING: {
                // Nếu đang ban ngày và lightLevel < 8 → sắp tối
                if (obs.isDaytime && obs.lightLevel < 8) {
                    return Optional.of(new Suggestion(
                        SuggestionType.NIGHT_APPROACHING,
                        "Sắp tối rồi, tao chuẩn bị đuốc trước nhé!",
                        List.of(new Task("craft", Map.of("item", "torch", "quantity", "8"))),
                        40
                    ));
                }
                break;
            }
            case NEARBY_RESOURCE: {
                // Placeholder — chưa implement
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isCooldownExpired(SuggestionType type) {
        long lastTick = cooldownMap.getOrDefault(type, 0L);
        return (currentTick - lastTick) >= type.cooldownTicks;
    }

    @Override
    public void recordSuggestion(SuggestionType type) {
        cooldownMap.put(type, currentTick);
    }

    /**
     * Đếm số lần xuất hiện của item trong inventorySummary.
     * inventorySummary có dạng "torch x3, wood x10, ..."
     */
    private int countItem(String inventorySummary, String itemName) {
        if (inventorySummary == null || inventorySummary.isEmpty()) {
            return 0;
        }
        String lower = inventorySummary.toLowerCase();
        String target = itemName.toLowerCase();

        int count = 0;
        int idx = 0;
        while ((idx = lower.indexOf(target, idx)) != -1) {
            // Tìm số lượng sau tên item, ví dụ "torch x3" hoặc "torch: 3"
            int afterItem = idx + target.length();
            // Bỏ qua khoảng trắng
            while (afterItem < lower.length() && lower.charAt(afterItem) == ' ') {
                afterItem++;
            }
            if (afterItem < lower.length() && (lower.charAt(afterItem) == 'x' || lower.charAt(afterItem) == ':')) {
                afterItem++; // bỏ qua 'x' hoặc ':'
                while (afterItem < lower.length() && lower.charAt(afterItem) == ' ') {
                    afterItem++;
                }
                StringBuilder numStr = new StringBuilder();
                while (afterItem < lower.length() && Character.isDigit(lower.charAt(afterItem))) {
                    numStr.append(lower.charAt(afterItem));
                    afterItem++;
                }
                if (numStr.length() > 0) {
                    count += Integer.parseInt(numStr.toString());
                } else {
                    count += 1;
                }
            } else {
                count += 1;
            }
            idx += target.length();
        }
        return count;
    }
}
