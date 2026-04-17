import { LLMProvider } from '../llm/LLMProvider.js';
import type { BotObservation } from '../skills/skillSchema.js';
import { ExperienceMemory } from '../knowledge/ExperienceMemory.js';

export class Curriculum {
    private llm: LLMProvider;
    private memory: ExperienceMemory;

    constructor(llm: LLMProvider, memory: ExperienceMemory) {
        this.llm = llm;
        this.memory = memory;
    }

    /**
     * Dựa vào Observation báo cáo từ bot, đề xuất mục tiêu tiếp theo.
     */
    public async proposeNextTask(obs: BotObservation): Promise<string> {
        // Retrieve relevant memories for context
        const context = this.memory.search(obs.lastTask || "survival", 3).join("\n");

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

Past experiences (Memories):
${context || "No relevant memories yet."}

Rules:
1. If hunger is strictly less than 10, the task MUST focus on acquiring food.
2. If it is Raining and health is low (< 10), suggest finding shelter.
3. If the bot is in the Nether, prioritize survival over resource gathering.
4. If the bot has no tools, suggest gathering basic resources (e.g. mine_wood).
5. Use short identifiers like mine_wood, craft_stone_pickaxe.
6. Output ONLY the task identifier, nothing else.`;

        try {
            console.log('[Curriculum] Asking LLM for next goal...');
            const response = await this.llm.chat(systemPrompt);
            const task = response.trim().toLowerCase();
            return task;
        } catch (e) {
            console.error('[Curriculum] LLM failed to propose task, defaulting to wander', e);
            return "wander";
        }
    }
}
