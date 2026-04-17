package com.steve.ai.personality;

import com.steve.ai.action.Task;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ComplexCommandPlanner {
    boolean isComplexCommand(String command);
    CompletableFuture<List<Task>> decompose(
        String command,
        PersonalityProfile personality,
        RelationshipLevel relationship
    );
}
