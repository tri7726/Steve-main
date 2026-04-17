import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
export class SkillLibrary {
    skills = new Map();
    skillFolder;
    constructor(folderPath = path.join(__dirname, 'imported_skills')) {
        this.skillFolder = folderPath;
    }
    async loadAllSkills() {
        if (!fs.existsSync(this.skillFolder)) {
            console.log('[SkillLibrary] Folder not found, skipping...');
            return;
        }
        const files = fs.readdirSync(this.skillFolder);
        for (const file of files) {
            if (file.endsWith('.json')) {
                try {
                    const content = fs.readFileSync(path.join(this.skillFolder, file), 'utf8');
                    const skill = JSON.parse(content);
                    this.skills.set(skill.id, skill);
                    console.log(`[SkillLibrary] Loaded: ${skill.id}`);
                }
                catch (e) {
                    console.error(`[SkillLibrary] Failed to load ${file}:`, e);
                }
            }
        }
        console.log(`[SkillLibrary] Total skills loaded: ${this.skills.size}`);
    }
    getSkill(id) {
        return this.skills.get(id);
    }
    saveSkill(skill) {
        this.skills.set(skill.id, skill);
        if (!fs.existsSync(this.skillFolder)) {
            fs.mkdirSync(this.skillFolder, { recursive: true });
        }
        fs.writeFileSync(path.join(this.skillFolder, `${skill.id}.json`), JSON.stringify(skill, null, 2));
        console.log(`[SkillLibrary] Saved: ${skill.id}`);
    }
    /**
     * Cập nhật stats sau khi skill được thực thi.
     */
    recordResult(skillId, success) {
        const skill = this.skills.get(skillId);
        if (!skill)
            return;
        if (success) {
            skill.successCount = (skill.successCount ?? 0) + 1;
        }
        else {
            skill.failCount = (skill.failCount ?? 0) + 1;
        }
        skill.lastUsed = Date.now();
        this.saveSkill(skill);
    }
    getSuccessRate(skillId) {
        const skill = this.skills.get(skillId);
        if (!skill)
            return 0;
        const total = (skill.successCount ?? 0) + (skill.failCount ?? 0);
        return total === 0 ? 1.0 : (skill.successCount ?? 0) / total;
    }
    /**
     * Tìm skill tương tự bằng TF-IDF cosine similarity — không cần Vector DB.
     * @param query  task description hoặc task id
     * @param topK   số kết quả trả về
     */
    findSimilar(query, topK = 3) {
        if (this.skills.size === 0)
            return [];
        const queryTokens = this.tokenize(query);
        const scored = [];
        for (const skill of this.skills.values()) {
            const docTokens = this.tokenize(`${skill.id} ${skill.name} ${skill.description}`);
            const score = this.cosineSimilarity(queryTokens, docTokens);
            if (score > 0)
                scored.push({ skill, score });
        }
        return scored
            .sort((a, b) => b.score - a.score)
            .slice(0, topK)
            .map(s => s.skill);
    }
    /**
     * Format similar skills thành context string để inject vào LLM prompt.
     */
    formatSimilarForPrompt(query, topK = 3) {
        const similar = this.findSimilar(query, topK);
        if (similar.length === 0)
            return '';
        const lines = ['## Similar skills in library (reference):'];
        for (const s of similar) {
            const rate = (this.getSuccessRate(s.id) * 100).toFixed(0);
            lines.push(`- ${s.id} (${rate}% success): ${s.description}`);
            if (s.commands.length > 0) {
                const cmds = s.commands.map(c => `${c.type}(${JSON.stringify(c.payload)})`).join(' → ');
                lines.push(`  commands: ${cmds}`);
            }
        }
        return lines.join('\n');
    }
    listSkillIds() {
        return Array.from(this.skills.keys());
    }
    getAllSkillsDescription() {
        const lines = ['Available Skills:'];
        for (const skill of this.skills.values()) {
            const rate = (this.getSuccessRate(skill.id) * 100).toFixed(0);
            lines.push(`- ${skill.id} (${rate}%): ${skill.description}`);
        }
        return lines.join('\n');
    }
    // ── TF-IDF helpers ────────────────────────────────────────────────────────
    tokenize(text) {
        const tokens = text.toLowerCase()
            .replace(/[^a-z0-9_]/g, ' ')
            .split(/\s+/)
            .filter(t => t.length > 1);
        const freq = new Map();
        for (const t of tokens) {
            freq.set(t, (freq.get(t) ?? 0) + 1);
        }
        return freq;
    }
    cosineSimilarity(a, b) {
        let dot = 0, normA = 0, normB = 0;
        for (const [term, countA] of a) {
            normA += countA * countA;
            const countB = b.get(term) ?? 0;
            dot += countA * countB;
        }
        for (const countB of b.values()) {
            normB += countB * countB;
        }
        const denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom === 0 ? 0 : dot / denom;
    }
}
//# sourceMappingURL=SkillLibrary.js.map