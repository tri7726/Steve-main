import { LLMProvider } from '../llm/LLMProvider.js';
import { KnowledgeBase } from '../knowledge/minecraftData.js';
import { SkillLibrary } from './SkillLibrary.js';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GRPC_PRIMITIVES = [
    { name: "MOVE_TO_BLOCK", description: "Di chuyển đến tọa độ (x, y, z) chính xác.", params: ["x", "y", "z"] },
    { name: "MINE_BLOCK", description: "Khai thác khối (block) tại tọa độ cụ thể hoặc tìm kiếm loại khối đó để đào.", params: ["x", "y", "z", "block", "quantity"] },
    { name: "PLACE_BLOCK", description: "Đặt một khối (block) từ túi đồ vào tọa độ mục tiêu.", params: ["x", "y", "z", "block"] },
    { name: "CRAFT", description: "Cửa sổ chế tạo: Tạo vật phẩm từ nguyên liệu có sẵn trong túi đồ.", params: ["item", "quantity"] },
    { name: "SMELT", description: "Nung nấu: Chế biến thức ăn hoặc luyện quặng bằng lò nung.", params: ["item", "quantity"] },
    { name: "ATTACK", description: "Chiến đấu: Tấn công mục tiêu (quái vật hoặc động vật) chỉ định.", params: ["entity_type"] },
    { name: "INTERACT_CHEST", description: "Quản lý kho: Cất đồ vào rương (store) hoặc lấy đồ (retrieve).", params: ["x", "y", "z", "mode", "item", "quantity"] },
    { name: "TRADE", description: "Giao thương: Trao đổi vật phẩm với dân làng để lấy đồ hiếm.", params: ["item", "quantity"] },
    { name: "FISH", description: "Câu cá: Thực hiện hoạt động câu cá để kiếm thức ăn hoặc vật phẩm báu vật.", params: ["duration_seconds"] },
    { name: "FARM", description: "Nông nghiệp: Thu hoạch cây trưởng thành (harvest) hoặc gieo hạt (plant).", params: ["mode", "crop", "radius"] },
    { name: "GOTO_WAYPOINT", description: "Di chuyển nhanh: Đi tới các địa điểm quan trọng đã đánh dấu (home, base).", params: ["label"] },
    { name: "SLEEP", description: "Nghỉ ngơi: Tìm giường gần nhất để ngủ qua đêm và đặt lại điểm hồi sinh.", params: [] }
];
export class SkillGenerator {
    llm;
    library;
    maxRetries = 3;
    constructor(llm, library) {
        this.llm = llm;
        this.library = library ?? new SkillLibrary();
    }
    /** Build RAG context từ KnowledgeBase và Wiki cho task cụ thể */
    buildRAGContext(taskName) {
        const context = KnowledgeBase.getFullContext(taskName);
        return context ? `## Minecraft Tactical Knowledge:\n${context}` : '## Minecraft Knowledge: No specific tactical data for this task.';
    }
    async generateSkill(taskName) {
        // Inject RAG knowledge vào prompt
        const ragContext = this.buildRAGContext(taskName);
        // Inject similar skills từ library
        const similarContext = this.library.formatSimilarForPrompt(taskName, 3);
        let systemPrompt = `You are the Skill Generator of the Voyager Minecraft AI.
Your job is to generate a JSON array of commands to complete a given task.
The bot operates using these fundamental gRPC primitives:
${JSON.stringify(GRPC_PRIMITIVES, null, 2)}

${ragContext}

${similarContext ? similarContext + '\n' : ''}
Task assigned: ${taskName}

You must return ONLY a strictly valid JSON object adhering to this schema:
{
    "id": "${taskName}",
    "name": "Human readable name of the task",
    "description": "Short explanation of the technique",
    "commands": [
        {
            "type": "ONE_OF_THE_PRIMITIVE_NAMES",
            "payload": { "param_name": "param_value" }
        }
    ]
}

No markdown tags, no explanations. ONLY the JSON string block.`;
        for (let i = 0; i < this.maxRetries; i++) {
            console.log(`[SkillGenerator] Attempt ${i + 1}/${this.maxRetries} to synthesize skill: ${taskName}`);
            try {
                const response = await this.llm.chat(systemPrompt);
                // Trimming code blocks if LLM accidentally includes them
                let cleanJsonStr = response.replace(/^```json/i, '').replace(/^```/i, '').replace(/```$/i, '').trim();
                const parsed = JSON.parse(cleanJsonStr);
                // Lưu file tĩnh (Self-expanding library)
                const targetDir = path.join(__dirname, 'imported_skills');
                if (!fs.existsSync(targetDir)) {
                    fs.mkdirSync(targetDir, { recursive: true });
                }
                const filePath = path.join(targetDir, `${taskName}.json`);
                fs.writeFileSync(filePath, JSON.stringify(parsed, null, 4));
                console.log(`[SkillGenerator] Successfully synthesized and saved: ${filePath}`);
                return parsed;
            }
            catch (error) {
                console.error(`[SkillGenerator] JSON Parsing or Generation Error: ${error.message}`);
                // Phản hồi feedback để LLM tự chấn chỉnh
                systemPrompt += `\n\nERROR IN YOUR PREVIOUS RESPONSE:\n${error.message}\nFix the invalid JSON formatting and send only valid JSON!`;
            }
        }
        console.error(`[SkillGenerator] Failed to generate valid skill for ${taskName} after ${this.maxRetries} attempts.`);
        return null;
    }
    /**
     * Regenerate skill với critique feedback từ Critic.
     */
    async generateSkillWithCritique(taskName, critique) {
        const ragContext = this.buildRAGContext(taskName);
        const similarContext = this.library.formatSimilarForPrompt(taskName, 2);
        const systemPrompt = `You are the Skill Generator of the Voyager Minecraft AI.
A previous skill for task "${taskName}" FAILED. You must generate an IMPROVED version.

## Critic Feedback (what went wrong):
${critique}

## Minecraft Knowledge:
${ragContext}

${similarContext ? similarContext + '\n' : ''}
## Available primitives:
${JSON.stringify(GRPC_PRIMITIVES, null, 2)}

Generate an improved skill that addresses the critique. Return ONLY valid JSON:
{
    "id": "${taskName}",
    "name": "Improved ${taskName}",
    "description": "Improved version addressing: ${critique.slice(0, 80)}",
    "commands": [{ "type": "PRIMITIVE_NAME", "payload": { ... } }]
}`;
        for (let i = 0; i < this.maxRetries; i++) {
            try {
                const response = await this.llm.chat(systemPrompt);
                const clean = response.replace(/^```json/i, '').replace(/^```/i, '').replace(/```$/i, '').trim();
                const parsed = JSON.parse(clean);
                console.log(`[SkillGenerator] Regenerated improved skill: ${taskName}`);
                return parsed;
            }
            catch (e) {
                console.error(`[SkillGenerator] Critique regeneration attempt ${i + 1} failed: ${e.message}`);
            }
        }
        return null;
    }
}
//# sourceMappingURL=SkillGenerator.js.map