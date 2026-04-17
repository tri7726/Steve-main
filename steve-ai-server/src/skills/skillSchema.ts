/**
 * Định dạng chuẩn bắt buộc cho mọi Skill JSON sinh ra bởi LLM
 * File này đóng vai trò làm Guideline cứng khi nhét vào Prompt.
 */

export interface SkillCommand {
    type: string;        // Ví dụ: MOVE_TO_BLOCK, MINE_BLOCK, CRAFT, EQUIP_BEST_TOOL
    payload: Record<string, any>;
}

export interface SkillSchema {
    id: string;          // Identifier ngắn gọn, vd: mine_wood
    name: string;        // Tên đọc được, vd: Mine Wood
    description: string; // LLM đọc field này để hiểu Skill làm gì
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
    inventory: any;           // Tạm parse từ inventory_json
    nearbyEntities: any[];    // Tạm parse từ nearby_entities_json
    observations: any;        // Tạm parse từ observations_json
    
    // Telemetry môi trường (Sovereign Tier)
    biome: string;
    dimension: string;
    worldTime: string; // Có thể cast về number/long nếu cần, ở đây dùng string/formatted cho LLM
    isRaining: boolean;

    // Dữ liệu cho Critic chấm điểm
    lastActionSuccess: boolean;
    lastTask: string;
    lastError: string;
    executedSkill: string;
    durationSeconds: number;
}
