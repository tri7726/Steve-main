import { LLMProvider } from '../llm/LLMProvider.js';
import type { BotObservation } from '../skills/skillSchema.js';

export class Critic {
    private llm: LLMProvider;

    constructor(llm: LLMProvider) {
        this.llm = llm;
    }

    /**
     * Dựa vào Observation báo cáo từ bot sau hành động, LLM sẽ phản biện success/fail.
     * @returns Điểm hoặc boolean Success
     */
    public async evaluateAction(obs: BotObservation): Promise<boolean> {
        const systemPrompt = `You are the Critic Agent of an autonomous Minecraft bot.
The bot has just finished executing a task. You must evaluate if the task was a SUCCESS or FAILURE based on the bot's current observation.

Task attempted: ${obs.lastTask}
Java-side reported error (if any): ${obs.lastError}
Java-side reported execution success: ${obs.lastActionSuccess}

Current State:
- Health: ${obs.health}/20
- Hunger: ${obs.hunger}/20
- Environment: Biome: ${obs.biome}, Dimension: ${obs.dimension}, Time: ${obs.worldTime}, Weather: ${obs.isRaining ? "Raining" : "Clear"}
- Current Inventory: ${JSON.stringify(obs.inventory)}

Rules:
1. If the task was to acquire an item, and the item is in the inventory, it is a SUCCESS.
2. If Java-side reported success is false, it is a FAILURE.
3. If the bot's health is severely declining or it died, it is a FAILURE.
4. Consider the environment: failure could be related to night threats or hazardous conditions.
5. Output EXACTLY ONE WORD: "SUCCESS" or "FAILURE".`;

        try {
            console.log(`[Critic] Evaluating outcome of task: ${obs.lastTask}...`);
            const response = await this.llm.chat(systemPrompt);
            const verdict = response.trim().toUpperCase();
            
            if (verdict.includes("SUCCESS") || verdict === "SUCCESS") {
                console.log(`[Critic] Verdict: SUCCESS`);
                return true;
            } else {
                console.log(`[Critic] Verdict: FAILURE (${response})`);
                return false;
            }
        } catch (e) {
            console.error('[Critic] Failed to evaluate action, defaulting to false', e);
            return false;
        }
    }
}
