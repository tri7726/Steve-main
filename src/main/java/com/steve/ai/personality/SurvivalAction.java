package com.steve.ai.personality;

import com.steve.ai.action.Task;
import java.util.List;

public record SurvivalAction(
    String actionType,   // "arm_self", "find_food", "guard_player", "flee"
    int priority,        // 1-10, cao hơn = ưu tiên hơn
    List<Task> tasks,
    String chatMessage
) {}
