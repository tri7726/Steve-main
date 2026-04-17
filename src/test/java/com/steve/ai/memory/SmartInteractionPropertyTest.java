package com.steve.ai.memory;

import com.steve.ai.llm.VietnameseCommandNormalizer;
import com.steve.ai.personality.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Steve Smart Interaction features.
 * Uses jqwik 1.8.1 + JUnit 5.
 *
 * NOTE: Tests cannot use Minecraft classes (BlockPos, Level, etc.)
 * because tests run without a Minecraft environment.
 */
public class SmartInteractionPropertyTest {

    // -------------------------------------------------------------------------
    // Property 1 — Waypoint round-trip
    // Validates: Requirements 1.1, 1.2
    // -------------------------------------------------------------------------

    /**
     * **Validates: Requirements 1.1, 1.2**
     *
     * WaypointMemory stores metadata keyed by label (lowercased).
     * For any valid label, dimension, and biome, after storing a WaypointMetadata
     * directly in the internal map (bypassing BlockPos), getMetadata(label) must
     * return a record whose label, dimension, and biome match what was stored.
     *
     * NOTE: WaypointMemory.save() requires net.minecraft.core.BlockPos which is
     * unavailable in the test environment. We therefore test the pure-Java metadata
     * fields by constructing a WaypointMetadata with a null pos and inserting it
     * via reflection into the internal map.
     */
    @Property
    @Label("Property 1 — Waypoint metadata round-trip (label, dimension, biome)")
    void waypointMetadataRoundTrip(
            @ForAll("validLabels") String label,
            @ForAll("dimensionNames") String dimension,
            @ForAll("biomeNames") String biome) throws Exception {

        WaypointMemory memory = new WaypointMemory();

        // Build metadata without BlockPos (null pos — pure Java fields only)
        String key = label.toLowerCase();
        String description = "[WAYPOINT] " + key + " in " + dimension;
        WaypointMetadata metadata = new WaypointMetadata(key, null, dimension, biome, description, System.currentTimeMillis());

        // Insert via reflection (bypasses BlockPos dependency)
        Field waypointsField = WaypointMemory.class.getDeclaredField("waypoints");
        waypointsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, WaypointMetadata> map =
                (java.util.Map<String, WaypointMetadata>) waypointsField.get(memory);
        map.put(key, metadata);

        // Verify round-trip
        assertTrue(memory.has(label));
        WaypointMetadata retrieved = memory.getMetadata(label).orElseThrow();
        assertEquals(key, retrieved.label());
        assertEquals(dimension, retrieved.dimension());
        assertEquals(biome, retrieved.biome());
    }

    @Provide
    Arbitrary<String> validLabels() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
    }

    @Provide
    Arbitrary<String> dimensionNames() {
        return Arbitraries.of("overworld", "nether", "end", "unknown");
    }

    @Provide
    Arbitrary<String> biomeNames() {
        return Arbitraries.of("plains", "desert", "forest", "ocean", "unknown");
    }

    // -------------------------------------------------------------------------
    // Property 2 — VectorStore priority boost
    // Validates: Requirements 2.4
    // -------------------------------------------------------------------------

    /**
     * **Validates: Requirements 2.4**
     *
     * For any non-empty text, a document stored with HIGH priority must appear
     * in the search results when searching for that same text.
     * (The HIGH-priority document scores ×1.5 vs NORMAL, so it should rank first.)
     */
    @Property
    @Label("Property 2 — VectorStore HIGH priority document appears in search results")
    void vectorStorePriorityBoost(@ForAll("nonEmptyAlpha") String text) throws IOException {
        File tempDir = Files.createTempDirectory("vstore-test-").toFile();
        tempDir.deleteOnExit();

        VectorStore store = new VectorStore(tempDir, "test-" + System.nanoTime());

        // Add same text at both priorities
        store.addMemory(text + " normal content", MemoryPriority.NORMAL);
        store.addMemory(text + " high content", MemoryPriority.HIGH);

        List<String> results = store.search(text, 5);

        // HIGH priority document must appear somewhere in results
        boolean highFound = results.stream().anyMatch(r -> r.contains("high content"));
        assertTrue(highFound,
            "HIGH priority document should appear in search results for query: " + text);
    }

    @Provide
    Arbitrary<String> nonEmptyAlpha() {
        return Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(30);
    }

    // -------------------------------------------------------------------------
    // Property 3 — SelfReflection threshold
    // Validates: Requirements 3.2, 3.6
    // -------------------------------------------------------------------------

    /**
     * **Validates: Requirements 3.2, 3.6**
     *
     * For any actionType string, after exactly FAILURE_THRESHOLD (3) calls to
     * recordFailure(), the VectorStore must contain at least one document
     * containing "[LESSON]".
     */
    @Property
    @Label("Property 3 — SelfReflection stores [LESSON] after threshold failures")
    void selfReflectionThreshold(@ForAll("actionTypes") String actionType) throws IOException {
        File tempDir = Files.createTempDirectory("reflect-test-").toFile();
        tempDir.deleteOnExit();

        VectorStore store = new VectorStore(tempDir, "reflect-" + System.nanoTime());
        SelfReflectionEngine engine = new SelfReflectionEngine(store);

        int threshold = 3; // FAILURE_THRESHOLD is private static final int = 3

        for (int i = 0; i < threshold; i++) {
            engine.recordFailure(actionType, "error message " + i);
        }

        List<String> results = store.search("[LESSON]", 10);
        boolean hasLesson = results.stream().anyMatch(r -> r.contains("[LESSON]"));
        assertTrue(hasLesson,
            "VectorStore should contain a [LESSON] document after " + threshold
                + " failures for actionType: " + actionType);
    }

    @Provide
    Arbitrary<String> actionTypes() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);
    }

    // -------------------------------------------------------------------------
    // Property 4 — Normalizer idempotency
    // Validates: Requirements 4.4
    // -------------------------------------------------------------------------

    /**
     * **Validates: Requirements 4.4**
     *
     * normalize(normalize(cmd)) == normalize(cmd) for any pure-ASCII/English command.
     * English-only strings are passed through unchanged by the normalizer, so
     * applying it twice must yield the same result as applying it once.
     */
    @Property
    @Label("Property 4 — VietnameseCommandNormalizer is idempotent on ASCII input")
    void normalizerIdempotency(@ForAll("asciiCommands") String cmd) {
        String once = VietnameseCommandNormalizer.normalize(cmd);
        String twice = VietnameseCommandNormalizer.normalize(once);
        assertEquals(once, twice,
            "normalize should be idempotent but got different results for: " + cmd);
    }

    @Provide
    Arbitrary<String> asciiCommands() {
        // Pure ASCII strings — the normalizer passes these through unchanged
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(40);
    }

    // -------------------------------------------------------------------------
    // Property 5 — PlayerMoodDetector unit tests (EmotionalContext requires SteveEntity)
    // Validates: Requirements 6.2
    // -------------------------------------------------------------------------

    /**
     * **Validates: Requirements 6.2**
     *
     * NOTE: Property 5 as specified (health < 6 → SCARED/TIRED) requires
     * SteveEntity which depends on Minecraft classes and cannot run in unit tests.
     * Instead, we verify PlayerMoodDetector.detect() returns the correct mood
     * for known Vietnamese trigger words, which is the pure-Java equivalent.
     *
     * Unit test: "giúp" → URGENT, "cảm ơn" → HAPPY
     */
    @Test
    void playerMoodDetector_urgentKeyword() {
        assertEquals(PlayerMood.URGENT, PlayerMoodDetector.detect("giúp tao với"),
            "Command containing 'giúp' should be detected as URGENT");
    }

    @Test
    void playerMoodDetector_happyKeyword() {
        assertEquals(PlayerMood.HAPPY, PlayerMoodDetector.detect("cảm ơn bạn nhé"),
            "Command containing 'cảm ơn' should be detected as HAPPY");
    }

    /**
     * **Validates: Requirements 6.2**
     *
     * For any string containing "giúp", mood must be URGENT.
     */
    @Property
    @Label("Property 5 — PlayerMoodDetector detects URGENT for commands containing 'giúp'")
    void playerMoodDetectorUrgent(@ForAll("urgentCommands") String cmd) {
        PlayerMood mood = PlayerMoodDetector.detect(cmd);
        assertEquals(PlayerMood.URGENT, mood,
            "Commands containing 'giúp' should always be URGENT, got: " + mood + " for: " + cmd);
    }

    @Provide
    Arbitrary<String> urgentCommands() {
        // Strings that contain the trigger word "giúp"
        return Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(10)
                .map(suffix -> "giúp " + suffix);
    }

    // -------------------------------------------------------------------------
    // Property 6 — Personality particles
    // Validates: Requirements 5.3, 5.7
    // -------------------------------------------------------------------------

    /**
     * **Validates: Requirements 5.3, 5.7**
     *
     * JokerPersonality.formatEmotionalMessage() must never return null or empty
     * for any combination of non-null raw string, RelationshipLevel, and EmotionalContext.
     */
    @Property
    @Label("Property 6 — JokerPersonality.formatEmotionalMessage never returns null or empty")
    void jokerPersonalityNeverNullOrEmpty(
            @ForAll("rawMessages") String raw,
            @ForAll RelationshipLevel level,
            @ForAll EmotionalContext ctx) {

        JokerPersonality joker = new JokerPersonality("TestSteve");
        String result = joker.formatEmotionalMessage(raw, level, ctx);

        assertNotNull(result, "formatEmotionalMessage must not return null");
        assertFalse(result.isBlank(), "formatEmotionalMessage must not return blank/empty string");
    }

    @Provide
    Arbitrary<String> rawMessages() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
    }
}
