package com.steve.ai.llm;

import com.steve.ai.agentic.ToolDefinition;
import com.steve.ai.agentic.ToolRegistry;
import com.steve.ai.agentic.ToolRegistryImpl;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for ReActEngine validation logic.
 *
 * NOTE: ReActEngine requires SteveEntity (Minecraft class) and cannot be
 * instantiated directly in unit tests. These tests verify the ToolRegistry
 * validation gate that ReActEngine delegates to — the same logic that guards
 * Task creation in parseReActResponseWithRegistry().
 */
public class ReActEnginePropertyTest {

    // -------------------------------------------------------------------------
    // Property 10 — ReActEngine only creates Tasks from valid tools
    // Validates: Requirements 4.7, 4.8
    // -------------------------------------------------------------------------

    /**
     * **Validates: Requirements 4.7, 4.8**
     *
     * For any action type string that is NOT registered in the ToolRegistry,
     * hasTool() must return false — meaning ReActEngine would reject the task.
     * This directly tests the validation gate used in parseReActResponseWithRegistry().
     */
    @Property
    @Label("Property 10 — ToolRegistry rejects unknown action types (ReActEngine validation gate)")
    void unknownActionTypeIsRejectedByRegistry(
            @ForAll("registeredTools") String registeredTool,
            @ForAll("unregisteredTools") String unregisteredTool) {

        ToolRegistry registry = new ToolRegistryImpl();
        registry.register(new ToolDefinition(
                registeredTool,
                "Test tool: " + registeredTool,
                Map.of("param", "value"),
                List.of(registeredTool + " example")
        ));

        // Registered tool must be accepted
        assertTrue(registry.hasTool(registeredTool),
                "hasTool() must return true for registered tool: " + registeredTool);

        // Unregistered tool must be rejected (ReActEngine sets finished=true and returns null)
        assertFalse(registry.hasTool(unregisteredTool),
                "hasTool() must return false for unregistered tool: " + unregisteredTool
                        + " (ReActEngine would reject this action type)");
    }

    /**
     * **Validates: Requirements 4.7, 4.8**
     *
     * For any set of registered tools, hasTool() must return true for every
     * registered name and false for any name not in the registry.
     * This is the exact check ReActEngine performs before creating a Task.
     */
    @Property
    @Label("Property 10b — ToolRegistry hasTool is consistent with registered set")
    void toolRegistryHasToolConsistentWithRegisteredSet(
            @ForAll("toolNameList") @net.jqwik.api.constraints.UniqueElements List<String> toolNames) {

        Assume.that(!toolNames.isEmpty());

        ToolRegistry registry = new ToolRegistryImpl();
        for (String name : toolNames) {
            registry.register(new ToolDefinition(name, "desc", Map.of(), List.of()));
        }

        // Every registered name must be found
        for (String name : toolNames) {
            assertTrue(registry.hasTool(name),
                    "hasTool() must return true for registered tool: " + name);
        }

        // A clearly unregistered name must not be found
        String notRegistered = "___NOT_REGISTERED___";
        assertFalse(registry.hasTool(notRegistered),
                "hasTool() must return false for unregistered tool");
    }

    // -------------------------------------------------------------------------
    // Unit tests — validation logic for null/invalid action types
    // -------------------------------------------------------------------------

    @Test
    void toolRegistry_emptyRegistry_rejectsAnyTool() {
        ToolRegistry registry = new ToolRegistryImpl();
        assertFalse(registry.hasTool("mine"));
        assertFalse(registry.hasTool("craft"));
        assertFalse(registry.hasTool(""));
    }

    @Test
    void toolRegistry_defaultRegistry_acceptsAllKnownActions() {
        ToolRegistry registry = ToolRegistryImpl.createDefault();
        // All 16 default action types must be accepted
        for (String action : List.of("mine", "smelt", "craft", "build", "attack",
                "follow", "pathfind", "farm", "chest", "trade",
                "place", "sleep", "fish", "brew", "waypoint", "gather")) {
            assertTrue(registry.hasTool(action),
                    "Default registry must contain action: " + action);
        }
    }

    @Test
    void toolRegistry_defaultRegistry_rejectsUnknownAction() {
        ToolRegistry registry = ToolRegistryImpl.createDefault();
        assertFalse(registry.hasTool("fly"),
                "Default registry must not contain unknown action 'fly'");
        assertFalse(registry.hasTool("teleport"),
                "Default registry must not contain unknown action 'teleport'");
        assertFalse(registry.hasTool(""),
                "Default registry must not contain empty action type");
    }

    // -------------------------------------------------------------------------
    // Providers
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<String> registeredTools() {
        return Arbitraries.of("mine", "craft", "build", "attack", "follow",
                "pathfind", "farm", "chest", "trade", "place");
    }

    @Provide
    Arbitrary<String> unregisteredTools() {
        // Strings that are clearly not in the registered set above
        return Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(20)
                .filter(s -> !List.of("mine", "craft", "build", "attack", "follow",
                        "pathfind", "farm", "chest", "trade", "place").contains(s));
    }

    @Provide
    Arbitrary<List<String>> toolNameList() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .list().ofMinSize(1).ofMaxSize(8);
    }
}
