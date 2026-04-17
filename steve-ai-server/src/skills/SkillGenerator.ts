import { LLMProvider } from '../llm/LLMProvider.js';
import type { SkillSchema } from './skillSchema.js';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const GRPC_PRIMITIVES = [
    { name: "MOVE_TO_BLOCK", description: "Đi bộ an toàn tới tọa độ (x, y, z)", params: ["x", "y", "z"] },
    { name: "MINE_BLOCK", description: "Đào block nhất định tại tọa độ (x, y, z)", params: ["x", "y", "z", "block_id"] },
    { name: "CRAFT", description: "Chế tạo vật phẩm tại bàn chế tạo hoặc túi đồ", params: ["item_id", "count"] },
    { name: "ATTACK", description: "Tấn công quái vật lân cận đe dọa sinh tồn", params: ["entity_type"] }
];

export class SkillGenerator {
    private llm: LLMProvider;
    private maxRetries = 3;

    constructor(llm: LLMProvider) {
        this.llm = llm;
    }

    public async generateSkill(taskName: string): Promise<SkillSchema | null> {
        let systemPrompt = `You are the Skill Generator of the Voyager Minecraft AI.
Your job is to generate a JSON array of commands to complete a given task.
The bot operates using these fundamental gRPC primitives:
${JSON.stringify(GRPC_PRIMITIVES, null, 2)}

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

                const parsed: SkillSchema = JSON.parse(cleanJsonStr);

                // Lưu file tĩnh (Self-expanding library)
                const targetDir = path.join(__dirname, 'imported_skills');
                if (!fs.existsSync(targetDir)) {
                    fs.mkdirSync(targetDir, { recursive: true });
                }

                const filePath = path.join(targetDir, `${taskName}.json`);
                fs.writeFileSync(filePath, JSON.stringify(parsed, null, 4));
                console.log(`[SkillGenerator] Successfully synthesized and saved: ${filePath}`);

                return parsed;

            } catch (error: any) {
                console.error(`[SkillGenerator] JSON Parsing or Generation Error: ${error.message}`);
                
                // Phản hồi feedback để LLM tự chấn chỉnh
                systemPrompt += `\n\nERROR IN YOUR PREVIOUS RESPONSE:\n${error.message}\nFix the invalid JSON formatting and send only valid JSON!`;
            }
        }

        console.error(`[SkillGenerator] Failed to generate valid skill for ${taskName} after ${this.maxRetries} attempts.`);
        return null;
    }
}
