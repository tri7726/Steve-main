package com.steve.ai.llm;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;
import net.minecraft.core.BlockPos;

import java.util.List;

public class PromptBuilder {
    
    public static String buildSystemPrompt() {
        return """
            You are a Minecraft AI. Respond ONLY with valid JSON.
            FORMAT: {"reasoning":"brief","plan":"description","tasks":[{"action":"type","parameters":{...}}]}
            ACTIONS: mine{block,quantity} smelt{item,quantity} craft{item,quantity} build{structure,blocks[],dimensions[]} attack{target} follow{player} pathfind{x,y,z} farm{mode,crop,radius} chest{mode} trade{mode} sleep{} fish{quantity} brew{potion,quantity} waypoint{action,label}
            STRUCTURES: house oldhouse castle tower barn modern powerplant
            BLOCK NAMES (EXACT): iron_ore, diamond_ore, coal_ore, gold_ore, copper_ore, oak_log, stone, cobblestone, dirt, sand, oak_leaves
            ITEM NAMES (EXACT): wooden_pickaxe, stone_pickaxe, iron_pickaxe, wooden_sword, iron_sword, oak_planks, stick, torch, crafting_table, furnace, apple, bread, cooked_beef, wheat
            RULES:
            1. Use EXACT block/item names above. NEVER use categories like "food", "wood", "ore", or "animal".
            2. If hungry, mine "oak_leaves" for "apple" or find animals to "attack".
            3. Target hostile mobs with "hostile".
            4. Keep reasoning under 10 words.
            5. Output ONLY JSON.
            6. TOOL TIER: wooden_pickaxe→stone/coal, stone_pickaxe→iron/copper, iron_pickaxe→gold/diamond.
               If correct pickaxe not in inventory, craft it first.
            EXAMPLES:
            find food -> {"reasoning":"Starving, looking for food","plan":"Mine leaves for apples","tasks":[{"action":"mine","parameters":{"block":"oak_leaves","quantity":10}}]}
            mine iron -> {"reasoning":"Mining iron ore","plan":"Mine iron","tasks":[{"action":"mine","parameters":{"block":"iron_ore","quantity":16}}]}
            craft pickaxe -> {"reasoning":"Need pickaxe","plan":"Craft wooden pickaxe","tasks":[{"action":"mine","parameters":{"block":"oak_log","quantity":3}},{"action":"craft","parameters":{"item":"wooden_pickaxe","quantity":1}}]}
            follow me -> {"reasoning":"Following player","plan":"Follow","tasks":[{"action":"follow","parameters":{"player":"USE_NEARBY_PLAYER_NAME"}}]}
            kill mobs -> {"reasoning":"Attacking hostiles","plan":"Combat","tasks":[{"action":"attack","parameters":{"target":"hostile"}}]}
            """;
    }

    public static String buildUserPrompt(SteveEntity steve, String command, WorldKnowledge worldKnowledge) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("=== SITUATION ===\n");
        prompt.append("Pos:").append(formatPosition(steve.blockPosition()));
        prompt.append(" HP:").append(String.format("%.0f", steve.getHealth())).append("/20");
        prompt.append(" Hunger:").append(steve.getSteveHunger()).append("/20");

        // Tool durability
        net.minecraft.world.item.ItemStack mainHand = steve.getMainHandItem();
        String toolInfo = com.steve.ai.memory.SteveEquipmentTracker.getSlotSummary(mainHand);
        prompt.append(" Tool:").append(toolInfo);

        long dayTime = steve.level().getDayTime() % 24000;
        prompt.append(" Time:").append((dayTime >= 13000 && dayTime < 23000) ? "Night" : "Day").append("\n");
        prompt.append("Players:").append(worldKnowledge.getNearbyPlayerNames()).append("\n");
        prompt.append("Mobs:").append(worldKnowledge.getNearbyEntitiesSummary()).append("\n");
        prompt.append("Blocks:").append(worldKnowledge.getNearbyBlocksSummary()).append("\n");
        prompt.append("Inv:").append(formatInventory(steve)).append("\n");

        // Waypoints nếu có
        String wpSummary = steve.getMemory().getWaypoints().getSummary();
        if (!"[none]".equals(wpSummary)) {
            prompt.append("Waypoints:").append(wpSummary).append("\n");
        }

        prompt.append("CMD:\"").append(command).append("\"\n");
        return prompt.toString();
    }

    private static String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatInventory(SteveEntity steve) {
        List<String> summary = steve.getMemory().getInventory().getSummary();
        if (summary.isEmpty()) return "[empty]";
        // Giới hạn 10 dòng để không làm phình prompt quá mức
        List<String> capped = summary.size() > 10 ? summary.subList(0, 10) : summary;
        return String.join(", ", capped)
            + (summary.size() > 10 ? " (+" + (summary.size() - 10) + " more)" : "");
    }

    public static String buildPersonalityContext(
            com.steve.ai.personality.PersonalityProfile personality,
            com.steve.ai.personality.RelationshipLevel level) {
        return "=== PERSONALITY CONTEXT ===\n"
            + "Name: " + personality.getName() + "\n"
            + "Personality: " + personality.getType().name() + "\n"
            + "Relationship: " + level.name() + "\n"
            + "Joke Chance: " + personality.getJokeChance() + "\n"
            + "Proactive Chance: " + personality.getProactiveChance() + "\n";
    }

    public static String buildDecompositionPrompt(
            String command,
            com.steve.ai.personality.PersonalityProfile personality,
            com.steve.ai.personality.RelationshipLevel level,
            AgentObservation obs) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildSystemPrompt()).append("\n");
        sb.append(buildPersonalityContext(personality, level)).append("\n");
        if (obs != null) {
            sb.append(obs.toPromptSection()).append("\n");
        }
        sb.append("=== COMPLEX COMMAND TO DECOMPOSE ===\n");
        sb.append("\"").append(command).append("\"\n");
        sb.append("Break this into ordered subtasks. Respond in the same JSON format.\n");
        return sb.toString();
    }

    public static String buildSystemPrompt(
            com.steve.ai.personality.PersonalityProfile personality,
            com.steve.ai.personality.RelationshipLevel level) {
        return buildSystemPrompt() + "\n" + buildPersonalityContext(personality, level);
    }
}

