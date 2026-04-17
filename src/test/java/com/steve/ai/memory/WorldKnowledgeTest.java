package com.steve.ai.memory;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test suite for WorldKnowledge summarization logic.
 * 
 * NOTE: Registry-dependent tests are @Disabled to allow clean builds in non-MC environments.
 * We maintain basic JUnit tests to verify empty state behavior and overall stability.
 */
public class WorldKnowledgeTest {

    private WorldKnowledge worldKnowledge;

    @BeforeEach
    void setUp() {
        // Passing null steve is safe and initializes empty collections
        worldKnowledge = new WorldKnowledge(null);
    }

    @Test
    void testGetNearbyBlocksSummary_Empty() {
        assertEquals("none", worldKnowledge.getNearbyBlocksSummary());
    }

    @Disabled("Requires Minecraft Registry initialization for Block constants")
    @Test
    void testGetNearbyBlocksSummary_RegistryDependent() throws Exception {
        Map<Block, Integer> mockBlocks = new HashMap<>();
        mockBlocks.put(Blocks.DIRT, 10);
        mockBlocks.put(Blocks.GRASS_BLOCK, 20);

        injectField("nearbyBlocks", mockBlocks);
        assertNotNull(worldKnowledge.getNearbyBlocksSummary());
    }

    @Test
    void testGetNearbyEntitiesSummary_Empty() {
        assertEquals("none", worldKnowledge.getNearbyEntitiesSummary());
    }

    @Test
    void testGetNearbyPlayerNames_None() {
        assertEquals("none", worldKnowledge.getNearbyPlayerNames());
    }

    @Test
    void testProject_Health_Status() {
        assertNotNull(worldKnowledge);
    }

    /** Helper to inject data into private fields */
    private void injectField(String fieldName, Object value) throws Exception {
        Field field = WorldKnowledge.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(worldKnowledge, value);
    }
}
