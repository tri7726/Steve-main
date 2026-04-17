import { LLMProvider } from '../llm/LLMProvider.js';
import type { SkillSchema } from './skillSchema.js';
import { SkillLibrary } from './SkillLibrary.js';
export declare class SkillGenerator {
    private llm;
    private library;
    private maxRetries;
    constructor(llm: LLMProvider, library?: SkillLibrary);
    /** Build RAG context từ KnowledgeBase và Wiki cho task cụ thể */
    private buildRAGContext;
    generateSkill(taskName: string): Promise<SkillSchema | null>;
    /**
     * Regenerate skill với critique feedback từ Critic.
     */
    generateSkillWithCritique(taskName: string, critique: string): Promise<SkillSchema | null>;
}
//# sourceMappingURL=SkillGenerator.d.ts.map