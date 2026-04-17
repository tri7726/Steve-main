import { LLMProvider } from '../llm/LLMProvider.js';
import type { BotObservation } from '../skills/skillSchema.js';
import { ExperienceMemory } from '../knowledge/ExperienceMemory.js';
export declare class Curriculum {
    private llm;
    private memory;
    constructor(llm: LLMProvider, memory: ExperienceMemory);
    proposeNextTask(obs: BotObservation): Promise<string>;
    /** Phân tích inventory → gợi ý tool tier tiếp theo */
    private buildToolHint;
}
//# sourceMappingURL=Curriculum.d.ts.map