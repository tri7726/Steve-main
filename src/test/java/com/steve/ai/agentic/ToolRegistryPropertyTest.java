package com.steve.ai.agentic;

import net.jqwik.api.*;
import net.jqwik.api.constraints.UniqueElements;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for ToolRegistry.
 *
 * Validates: Requirements 4.1, 4.2, 4.3 (Registration, Retrieval, Ordering, Description)
 */
public class ToolRegistryPropertyTest {

    // -------------------------------------------------------------------------
    // Property — ToolRegistry registration and consistency
    // -------------------------------------------------------------------------

    @Property
    @Label("Property 8a — getAllTools() returns exactly the registered set")
    void getAllToolsReturnsExactlyRegisteredSet(
            @ForAll("toolDefinitionLists") List<ToolDefinition> tools) {

        Assume.that(!tools.isEmpty());

        ToolRegistry registry = new ToolRegistryImpl();
        for (ToolDefinition tool : tools) {
            registry.register(tool);
        }

        List<ToolDefinition> allTools = registry.getAllTools();

        Set<String> registeredNames = tools.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());

        Set<String> returnedNames = allTools.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());

        assertEquals(registeredNames, returnedNames);
    }

    @Property
    @Label("Property 8b — hasTool() returns true for every registered tool name")
    void hasToolReturnsTrueForAllRegisteredNames(
            @ForAll("toolDefinitionLists") List<ToolDefinition> tools) {

        Assume.that(!tools.isEmpty());

        ToolRegistry registry = new ToolRegistryImpl();
        for (ToolDefinition tool : tools) {
            registry.register(tool);
        }

        for (ToolDefinition tool : tools) {
            assertTrue(registry.hasTool(tool.name()));
        }
    }

    @Property
    @Label("Property 8c — hasTool() returns false for unregistered names")
    void hasToolReturnsFalseForUnregisteredNames(
            @ForAll("uniqueToolNameLists") @UniqueElements List<String> registeredNames,
            @ForAll("unregisteredName") String unregisteredName) {

        Assume.that(!registeredNames.isEmpty());
        Assume.that(!registeredNames.contains(unregisteredName));

        ToolRegistry registry = new ToolRegistryImpl();
        for (String name : registeredNames) {
            registry.register(new ToolDefinition(name, "desc", Map.of(), List.of()));
        }

        assertFalse(registry.hasTool(unregisteredName));
    }

    @Property
    @Label("Property 9 — Description block contains all registered tool names")
    void descriptionBlockContainsAllToolNames(
            @ForAll("toolDefinitionLists") @UniqueElements List<ToolDefinition> tools) {

        ToolRegistry registry = new ToolRegistryImpl();
        for (ToolDefinition tool : tools) {
            registry.register(tool);
        }

        String block = registry.buildToolDescriptionBlock();
        for (ToolDefinition tool : tools) {
            assertTrue(block.contains(tool.name()), "Description block should contain tool name: " + tool.name());
        }
    }

    // -------------------------------------------------------------------------
    // Unit tests — edge cases & defaults
    // -------------------------------------------------------------------------

    @Test
    void createDefault_loadsMultipleTools() {
        ToolRegistry registry = ToolRegistryImpl.createDefault();
        List<ToolDefinition> tools = registry.getAllTools();
        
        // Hiện tại có 18 tools (mine, smelt, craft, build, attack, follow, pathfind, farm, chest, trade, place, sleep, fish, brew, waypoint, gather, give, feed)
        assertTrue(tools.size() >= 16, "Default registry should contain all standard actions");
        assertTrue(registry.hasTool("mine"));
        assertTrue(registry.hasTool("feed"));
    }

    @Test
    void emptyRegistry_getAllToolsReturnsEmptyList() {
        ToolRegistry registry = new ToolRegistryImpl();
        assertTrue(registry.getAllTools().isEmpty());
    }

    @Test
    void registerDuplicateName_overwritesPrevious() {
        ToolRegistry registry = new ToolRegistryImpl();
        registry.register(new ToolDefinition("mine", "desc1", Map.of(), List.of()));
        registry.register(new ToolDefinition("mine", "desc2", Map.of(), List.of()));

        assertEquals(1, registry.getAllTools().size());
        assertEquals("desc2", registry.getAllTools().get(0).description());
    }

    @Test
    void buildToolDescriptionBlock_isCached() {
        ToolRegistry registry = new ToolRegistryImpl();
        registry.register(new ToolDefinition("mine", "desc", Map.of(), List.of()));
        
        String block1 = registry.buildToolDescriptionBlock();
        String block2 = registry.buildToolDescriptionBlock();
        
        assertSame(block1, block2, "Subsequent calls should return the same cached string instance");
        
        registry.register(new ToolDefinition("craft", "desc", Map.of(), List.of()));
        String block3 = registry.buildToolDescriptionBlock();
        assertNotSame(block1, block3, "Registering a new tool must invalidate the cache");
    }

    @Test
    void getAllToolsIsImmutable() {
        ToolRegistry registry = new ToolRegistryImpl();
        registry.register(new ToolDefinition("mine", "desc", Map.of(), List.of()));

        List<ToolDefinition> list = registry.getAllTools();
        assertThrows(UnsupportedOperationException.class, () -> list.add(
                new ToolDefinition("craft", "desc", Map.of(), List.of())));
    }

    // -------------------------------------------------------------------------
    // Providers
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<List<ToolDefinition>> toolDefinitionLists() {
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15);
        Arbitrary<String> descriptions = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(40);
        
        Arbitrary<Map<String, String>> params = Arbitraries.maps(
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(5),
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10)
        ).ofMaxSize(3);

        Arbitrary<List<String>> examples = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(20).list().ofMaxSize(2);

        return Combinators.combine(names, descriptions, params, examples)
            .as(ToolDefinition::new)
            .list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<List<String>> uniqueToolNameLists() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .list().ofMinSize(1).ofMaxSize(8)
                .uniqueElements();
    }

    @Provide
    Arbitrary<String> unregisteredName() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .map(s -> "UNREGISTERED_" + s);
    }
}
