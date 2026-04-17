package com.steve.ai.llm;

import com.steve.ai.action.Task;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for TaskPlanner validation logic.
 *
 * Dùng TaskPlanner.validateTaskStatic() — không cần Forge config,
 * tránh IllegalStateException "Cannot get config value before config is loaded".
 */
public class TaskPlannerTest {

    @Test
    void testValidateTask_Success() {
        Task pathfindTask = new Task("pathfind", Map.of("x", 10, "y", 64, "z", 20));
        assertTrue(TaskPlanner.validateTaskStatic(pathfindTask), "Pathfind with x,y,z should be valid");

        Task mineTask = new Task("mine", Map.of("block", "diamond_ore", "quantity", 3));
        assertTrue(TaskPlanner.validateTaskStatic(mineTask), "Mine with block and quantity should be valid");
    }

    @Test
    void testValidateTask_Failure_MissingParams() {
        Task invalidTask = new Task("pathfind", Map.of("x", 10)); // Missing y, z
        assertFalse(TaskPlanner.validateTaskStatic(invalidTask), "Pathfind without y and z should be invalid");
    }

    @Test
    void testValidateTask_UnknownAction() {
        Task unknownTask = new Task("dance", Map.of());
        assertFalse(TaskPlanner.validateTaskStatic(unknownTask), "Unknown actions should be rejected");
    }

    @Test
    void testValidateTask_NullTask() {
        assertFalse(TaskPlanner.validateTaskStatic(null), "Null task should be rejected");
    }

    @Test
    void testValidateTask_NullAction() {
        Task nullAction = new Task(null, Map.of());
        assertFalse(TaskPlanner.validateTaskStatic(nullAction), "Null action should be rejected");
    }
}
