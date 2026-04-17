/**
 * Định dạng chuẩn bắt buộc cho mọi Skill JSON sinh ra bởi LLM
 * File này đóng vai trò làm Guideline cứng khi nhét vào Prompt.
 */
export interface SkillCommand {
    type: string;
    payload: Record<string, any>;
}
export interface SkillSchema {
    id: string;
    name: string;
    description: string;
    commands: SkillCommand[];
}
/**
 * Định dạng Observation phản hồi từ Java gRPC (Critic's feed)
 */
export interface BotObservation {
    tick: number;
    health: number;
    hunger: number;
    position: {
        x: number;
        y: number;
        z: number;
    };
    inventory: any;
    nearbyEntities: any[];
    observations: any;
    biome: string;
    dimension: string;
    worldTime: string;
    isRaining: boolean;
    lastActionSuccess: boolean;
    lastTask: string;
    lastError: string;
    executedSkill: string;
    durationSeconds: number;
}
//# sourceMappingURL=skillSchema.d.ts.map