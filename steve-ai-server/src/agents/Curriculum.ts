import { LLMProvider } from '../llm/LLMProvider.js';
import type { BotObservation } from '../skills/skillSchema.js';
import { ExperienceMemory } from '../knowledge/ExperienceMemory.js';
import { KnowledgeBase } from '../knowledge/minecraftData.js';

export class Curriculum {
    private llm: LLMProvider;
    private memory: ExperienceMemory;

    constructor(llm: LLMProvider, memory: ExperienceMemory) {
        this.llm = llm;
        this.memory = memory;
    }

    public async proposeNextTask(obs: BotObservation): Promise<string> {
        const context = this.memory.search(obs.lastTask || "survival", 3).join("\n");

        // Inject RAG: tool tier hint dựa trên inventory
        const toolHint = this.buildToolHint(obs.inventory);

        const systemPrompt = `You are the Curriculum Agent of an autonomous Minecraft bot. 
Your goal is to suggest the next single, actionable task for the bot to perform to advance its survival and tech tree.
Consider the bot's current state: 
- Health: ${obs.health}/20
- Hunger: ${obs.hunger}/20
- Biome: ${obs.biome}
- Dimension: ${obs.dimension}
- Time: ${obs.worldTime}
- Weather: ${obs.isRaining ? "Raining/Storming" : "Clear"}
- Inventory: ${JSON.stringify(obs.inventory)}

Tool Tier Analysis:
${toolHint}

Past experiences (Memories):
${context || "No relevant memories yet."}

Rules:
1. If hunger is strictly less than 10, the task MUST focus on acquiring food.
2. If it is Raining and health is low (< 10), suggest finding shelter.
3. If the bot is in the Nether, prioritize survival over resource gathering.
4. If the bot has no tools, suggest gathering basic resources (e.g. mine_wood).
5. Follow Minecraft tech tree progression: wood → stone → iron → diamond.
6. Use short identifiers like mine_wood, craft_stone_pickaxe, mine_iron_ore.
7. Output ONLY the task identifier, nothing else.`;

        try {
            console.log('[Curriculum] Asking LLM for next goal...');
            const response = await this.llm.chat(systemPrompt);
            const task = response.trim().toLowerCase().replace(/\s+/g, '_');
            return task;
        } catch (e) {
            console.error('[Curriculum] LLM failed to propose task, defaulting to mine_wood', e);
            return "mine_wood";
        }
    }

    /** Phân tích inventory → gợi ý tool tier tiếp theo */
    private buildToolHint(inventory: Record<string, number>): string {
        const inv = inventory ?? {};
        const lines: string[] = [];

        const hasDiamondPick = (inv['diamond_pickaxe'] ?? 0) > 0;
        const hasIronPick    = (inv['iron_pickaxe'] ?? 0) > 0;
        const hasStonePick   = (inv['stone_pickaxe'] ?? 0) > 0;
        const hasWoodPick    = (inv['wooden_pickaxe'] ?? 0) > 0;
        const hasWood        = ((inv['oak_log'] ?? 0) + (inv['birch_log'] ?? 0) + (inv['spruce_log'] ?? 0)) > 0;

        if (hasDiamondPick) {
            lines.push('- Has diamond pickaxe → can mine ancient_debris, obsidian');
        } else if (hasIronPick) {
            lines.push('- Has iron pickaxe → can mine diamond_ore, gold_ore, redstone_ore');
            lines.push('- Next upgrade: craft diamond_pickaxe');
        } else if (hasStonePick) {
            lines.push('- Has stone pickaxe → can mine iron_ore, copper_ore');
            lines.push('- Next upgrade: mine iron_ore → smelt → craft iron_pickaxe');
        } else if (hasWoodPick) {
            lines.push('- Has wooden pickaxe → can mine stone, coal_ore');
            lines.push('- Next upgrade: mine stone → craft stone_pickaxe');
        } else {
            lines.push('- No pickaxe → must craft wooden_pickaxe first');
            if (!hasWood) lines.push('- No wood → must mine oak_log first');
        }

        // Food hint
        const foodItems = ['bread', 'cooked_beef', 'cooked_chicken', 'apple', 'carrot', 'potato'];
        const totalFood = foodItems.reduce((sum, f) => sum + (inv[f] ?? 0), 0);
        if (totalFood === 0) lines.push('- No food in inventory → consider hunting or farming');

        return lines.join('\n');
    }
}
