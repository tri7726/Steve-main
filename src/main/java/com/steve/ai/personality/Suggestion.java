package com.steve.ai.personality;

import com.steve.ai.action.Task;
import java.util.List;

public record Suggestion(
    SuggestionType type,
    String chatMessage,
    List<Task> autoTasks,          // tasks tự động thực hiện nếu player không từ chối
    int waitTicksForResponse       // số tick chờ player phản hồi trước khi tự làm
) {}
