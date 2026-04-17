export interface SkillCommand {
    type: string;
    payload: any;
}
export interface Skill {
    id: string;
    name: string;
    description: string;
    commands: SkillCommand[];
    successCount?: number;
    failCount?: number;
    lastUsed?: number;
}
export declare class SkillLibrary {
    private skills;
    private skillFolder;
    constructor(folderPath?: string);
    loadAllSkills(): Promise<void>;
    getSkill(id: string): Skill | undefined;
    saveSkill(skill: Skill): void;
    /**
     * Cập nhật stats sau khi skill được thực thi.
     */
    recordResult(skillId: string, success: boolean): void;
    getSuccessRate(skillId: string): number;
    /**
     * Tìm skill tương tự bằng TF-IDF cosine similarity — không cần Vector DB.
     * @param query  task description hoặc task id
     * @param topK   số kết quả trả về
     */
    findSimilar(query: string, topK?: number): Skill[];
    /**
     * Format similar skills thành context string để inject vào LLM prompt.
     */
    formatSimilarForPrompt(query: string, topK?: number): string;
    listSkillIds(): string[];
    getAllSkillsDescription(): string;
    private tokenize;
    private cosineSimilarity;
}
//# sourceMappingURL=SkillLibrary.d.ts.map