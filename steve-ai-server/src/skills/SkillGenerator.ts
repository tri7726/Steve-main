import { LLMProvider } from '../llm/LLMProvider.js';
import type { SkillSchema } from './skillSchema.js';
import { KnowledgeBase } from '../knowledge/minecraftData.js';
import { SkillLibrary } from './SkillLibrary.js';
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
    private library: SkillLibrary;
    private maxRetries = 3;

    constructor(llm: LLMProvider, library?: SkillLibrary) {
        this.llm = llm;
        this.library = library ?? new SkillLibrary();
    }

    /** Build RAG context từ KnowledgeBase cho task cụ thể */
    private buildRAGContext(taskName: string): string {
        const lines: string[] = ['## Minecraft Knowledge (verified data):'];

        // Extract item name từ task (vd: "craft_wooden_pickaxe" → "wooden_pickaxe")
        const parts = taskName.split('_');
        const action = parts[0]; // mine, craft, smelt, etc.

        // Tìm item name: bỏ action prefix
        const itemName = parts.slice(1).join('_');

        if (action === 'craft' && itemName) {
            const chain = KnowledgeBase.formatCraftingChainForPrompt(itemName);
            lines.push(chain);

            // Tool tier cần thiết
            const recipes = KnowledgeBase.getRecipes(itemName);
            if (recipes.length > 0) {
                const method = recipes[0]?.method;
                if (method) lines.push(`Crafting method: ${method}`);
            }
        }

        if (action === 'mine' && itemName) {
            const tier = KnowledgeBase.getRequiredToolTier(itemName);
            if (tier !== 'none') {
                lines.push(`To mine ${itemName}, required tool: ${tier}`);
                // Thêm recipe của tool đó
                const toolChain = KnowledgeBase.formatCraftingChainForPrompt(tier);
                lines.push(`If you don't have ${tier}:\n${toolChain}`);
            } else {
                lines.push(`${itemName} can be mined with any tool (or bare hands)`);
            }
        }

        if (action === 'smelt' && itemName) {
            lines.push(`Smelting ${itemName} requires: furnace + fuel (coal/wood) + raw material`);
            // Map raw → smelted
            const smeltMap: Record<string, string> = {
                'iron_ingot': 'raw_iron', 'gold_ingot': 'raw_gold',
                'copper_ingot': 'raw_copper', 'cooked_beef': 'beef',
                'cooked_chicken': 'chicken', 'glass': 'sand',
            };
            const raw = smeltMap[itemName];
            if (raw) lines.push(`Raw material needed: ${raw}`);
        }

        // Fallback: nếu không parse được, thêm tool tier table
        if (lines.length === 1) {
            lines.push('Tool tiers: wooden→stone/coal | stone→iron/copper | iron→gold/diamond/redstone/lapis');
        }

        return lines.join('\n');
    }

    public async generateSkill(taskName: string): Promise<SkillSchema | null> {
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

    /**
     * Regenerate skill với critique feedback từ Critic.
     */
    public async generateSkillWithCritique(taskName: string, critique: string): Promise<SkillSchema | null> {
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
                const parsed: SkillSchema = JSON.parse(clean);
                console.log(`[SkillGenerator] Regenerated improved skill: ${taskName}`);
                return parsed;
            } catch (e: any) {
                console.error(`[SkillGenerator] Critique regeneration attempt ${i+1} failed: ${e.message}`);
            }
        }
        return null;
    }
}
