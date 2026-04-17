import { OllamaProvider } from '../llm/OllamaProvider.js';
import { GeminiProvider } from '../llm/GeminiProvider.js';
import { GroqProvider } from '../llm/GroqProvider.js';
import { LLMProvider } from '../llm/LLMProvider.js';
import { KnowledgeBase } from '../knowledge/minecraftData.js';
import { ExperienceMemory } from '../knowledge/ExperienceMemory.js';
import { SkillLibrary } from '../skills/SkillLibrary.js';
import { Curriculum } from './Curriculum.js';
import { Critic } from './Critic.js';
import { SkillGenerator } from '../skills/SkillGenerator.js';
import * as grpc from '@grpc/grpc-js';
import * as dotenv from 'dotenv';
dotenv.config();
// Timeout wrapper to prevent SkillGenerator from hanging forever
function withTimeout(promise, ms) {
    return Promise.race([
        promise,
        new Promise((_, reject) => setTimeout(() => reject(new Error(`Timed out after ${ms}ms`)), ms))
    ]);
}
export class VoyagerAgent {
    llm;
    visionLlm;
    skills;
    curriculum;
    critic;
    skillGenerator;
    memory;
    // Lưu tạm trạng thái agent
    isExecutingTask = false;
    currentTask = "";
    constructor() {
        // Chọn Provider dựa trên cấu hình .env
        const providerName = process.env.AI_PROVIDER?.toLowerCase() || 'ollama';
        console.log(`[Voyager] Initializing with provider: ${providerName.toUpperCase()}`);
        if (providerName === 'groq') {
            this.llm = new GroqProvider();
            this.visionLlm = this.llm; // Groq hỗ trợ multimodal tốt
        }
        else if (providerName === 'gemini') {
            this.llm = new GeminiProvider();
            this.visionLlm = this.llm;
        }
        else {
            this.llm = new OllamaProvider('llama3.1');
            this.visionLlm = new GeminiProvider(); // Fallback vision to Gemini if possible
        }
        this.skills = new SkillLibrary();
        this.memory = new ExperienceMemory();
        this.curriculum = new Curriculum(this.llm, this.memory);
        this.critic = new Critic(this.llm);
        this.skillGenerator = new SkillGenerator(this.llm, this.skills);
    }
    async initialize() {
        await this.skills.loadAllSkills();
    }
    /**
     * Phương thức đánh giá State và gửi action.
     * Hàm này được gọi định kỳ hoặc khi có sự kiện từ bot.
     */
    async evaluateAndAct(botState, stream) {
        if (botState.tick % 40 === 0) { // Mỗi 2 giây xử lý 1 lần
            // Build the Observation interface strict
            const obs = {
                tick: botState.tick,
                health: botState.health,
                hunger: botState.hunger,
                position: { x: botState.x, y: botState.y, z: botState.z },
                inventory: JSON.parse(botState.inventoryJson || '{}'),
                nearbyEntities: JSON.parse(botState.nearbyEntitiesJson || '[]'),
                observations: JSON.parse(botState.observationsJson || '{}'),
                lastActionSuccess: botState.lastActionSuccess,
                lastTask: botState.lastTask,
                lastError: botState.lastError,
                executedSkill: botState.executedSkill,
                durationSeconds: botState.durationSeconds,
                // Sovereign Telemetry
                biome: botState.biome || "unknown",
                dimension: botState.dimension || "overworld",
                worldTime: `Tick ${botState.world_time}`, // Cung cấp dưới dạng chuỗi mô tả
                isRaining: botState.is_raining || false
            };
            // Nếu có Screenshot, cho LLM "nhìn" để bối cảnh hóa
            let visionInsights = "";
            if (botState.screenshot && botState.screenshot.length > 0) {
                try {
                    console.log("[Voyager] Analyzing screenshot with Vision...");
                    visionInsights = await this.visionLlm.chat("Describe what you see in this Minecraft screenshot. Focus on entities, block structures, and immediate threats.", undefined, // Dùng model mặc định của Provider
                    Buffer.from(botState.screenshot));
                    console.log(`[Voyager] Vision Insights: ${visionInsights}`);
                    obs.observations.vision_insghts = visionInsights; // Inject into observation
                }
                catch (e) {
                    console.error("[Voyager] Vision failed:", e);
                }
            }
            // Nếu vừa execute xong 1 action, cho Critic chấm điểm
            if (this.isExecutingTask && obs.lastTask !== "") {
                const isSuccess = await this.critic.evaluateAction(obs);
                const memEntry = `Task: ${obs.lastTask} | Result: ${isSuccess ? 'SUCCESS' : 'FAILURE'} | Inventory: ${JSON.stringify(obs.inventory).slice(0, 100)}`;
                this.memory.add(memEntry);
                if (obs.executedSkill) {
                    this.skills.recordResult(obs.executedSkill, isSuccess);
                }
                // ── Self-critique loop: nếu fail → regenerate với feedback ──
                if (!isSuccess && obs.lastError && obs.lastError.trim() !== '') {
                    console.log(`[Voyager] Task '${obs.lastTask}' FAILED. Running self-critique...`);
                    try {
                        const critique = await this.buildCritiquePrompt(obs);
                        const improved = await withTimeout(this.skillGenerator.generateSkillWithCritique(obs.lastTask, critique), 30_000);
                        if (improved) {
                            this.skills.saveSkill(improved);
                            console.log(`[Voyager] Regenerated skill '${obs.lastTask}' after critique.`);
                            this.memory.add(`[LESSON] Task ${obs.lastTask} failed: ${obs.lastError}. Regenerated skill.`);
                        }
                    }
                    catch (e) {
                        console.error('[Voyager] Self-critique failed:', e);
                    }
                }
                this.isExecutingTask = false;
            }
            // Nếu đang rảnh rỗi, kêu Curriculum bốc việc mới
            if (!this.isExecutingTask) {
                let nextTask = "wander";
                try {
                    // Nếu có Vision Insights, đính kèm vào prompt của curriculum
                    const prompt = visionInsights
                        ? `Vision context from current view: ${visionInsights}\nBased on this and state, propose next task.`
                        : undefined;
                    nextTask = await this.curriculum.proposeNextTask(obs);
                }
                catch (e) {
                    console.error('[Voyager] Curriculum failed, defaulting to wander:', e);
                }
                console.log(`[Voyager] Assigned new goal: ${nextTask}`);
                this.currentTask = nextTask;
                this.isExecutingTask = true;
                try {
                    // Cố gắng tra cứu trong Thư Viện Kỹ Năng cứng
                    let chosenSkill = this.skills.getSkill(nextTask);
                    // Nếu chưa biết làm, gọi SkillGenerator đẻ ra Skill mới — với timeout 30s
                    if (!chosenSkill) {
                        console.log(`[Voyager] Skill library missing technique for: ${nextTask}. Asking LLM to generate...`);
                        const synthesized = await withTimeout(this.skillGenerator.generateSkill(nextTask), 30_000);
                        if (synthesized) {
                            this.skills.saveSkill(synthesized); // Persist vào library
                            chosenSkill = this.skills.getSkill(nextTask);
                        }
                        else {
                            console.error(`[Voyager] Skill Generation failed for: ${nextTask}. Dropping task.`);
                            this.isExecutingTask = false;
                            return;
                        }
                    }
                    if (chosenSkill) {
                        for (const cmd of chosenSkill.commands) {
                            // Serialize payload — resolve any template placeholders dynamically
                            let payload = { ...cmd.payload };
                            // Dynamically substitute known template keys using real observation data
                            for (const key of Object.keys(payload)) {
                                const val = String(payload[key]);
                                if (val.includes('${')) {
                                    // Replace common templates with real values from botState
                                    payload[key] = val
                                        .replace('${pos.x}', String(Math.round(botState.x)))
                                        .replace('${pos.y}', String(Math.round(botState.y)))
                                        .replace('${pos.z}', String(Math.round(botState.z)))
                                        .replace('${payload.target_block}', 'oak_log') // sensible default
                                        .replace(/\$\{[^}]+\}/g, ''); // strip any unknown placeholders
                                }
                            }
                            stream.write({
                                command_id: `skill-${chosenSkill.id}-${botState.tick}`,
                                command_type: cmd.type,
                                payload_json: JSON.stringify(payload)
                            });
                        }
                    }
                }
                catch (err) {
                    // Always reset executing state on unexpected errors to avoid permanent lock
                    console.error(`[Voyager] Unexpected error executing task '${nextTask}':`, err.message);
                    this.isExecutingTask = false;
                }
            }
        }
    }
    /**
     * Build critique prompt từ failed observation để feed vào SkillGenerator.
     */
    async buildCritiquePrompt(obs) {
        const critiquePrompt = `You are the Critic Agent of a Minecraft bot.
The bot just FAILED a task. Analyze the failure and provide specific feedback.

Failed Task: ${obs.lastTask}
Error: ${obs.lastError}
Bot State at failure:
- Health: ${obs.health}/20, Hunger: ${obs.hunger}/20
- Inventory: ${JSON.stringify(obs.inventory).slice(0, 200)}
- Biome: ${obs.biome}, Time: ${obs.worldTime}

Provide a SHORT critique (2-3 sentences) explaining:
1. Why the task likely failed
2. What the skill should do differently
3. Any missing prerequisites

Output ONLY the critique text, no JSON.`;
        try {
            return await this.llm.chat(critiquePrompt);
        }
        catch {
            return `Task ${obs.lastTask} failed with error: ${obs.lastError}. Regenerate with better error handling.`;
        }
    }
}
//# sourceMappingURL=VoyagerAgent.js.map