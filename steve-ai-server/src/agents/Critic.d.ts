import { LLMProvider } from '../llm/LLMProvider.js';
import type { BotObservation } from '../skills/skillSchema.js';
export declare class Critic {
    private llm;
    constructor(llm: LLMProvider);
    /**
     * Dựa vào Observation báo cáo từ bot sau hành động, LLM sẽ phản biện success/fail.
     * @returns Điểm hoặc boolean Success
     */
    evaluateAction(obs: BotObservation): Promise<boolean>;
}
//# sourceMappingURL=Critic.d.ts.map