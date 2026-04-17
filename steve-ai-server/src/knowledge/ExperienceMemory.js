import * as fs from 'fs';
import * as path from 'path';
export class ExperienceMemory {
    memories = [];
    filePath;
    constructor(storageDir = './data') {
        if (!fs.existsSync(storageDir)) {
            fs.mkdirSync(storageDir, { recursive: true });
        }
        this.filePath = path.join(storageDir, 'experience.json');
        this.load();
    }
    add(text) {
        this.memories.push({ text, timestamp: Date.now() });
        this.save();
    }
    /**
     * Simple keyword search for RAG.
     */
    search(query, limit = 3) {
        const keywords = query.toLowerCase().split(' ').filter(w => w.length > 3);
        if (keywords.length === 0)
            return this.memories.slice(-limit).map(m => m.text);
        return this.memories
            .map(m => ({
            m,
            score: keywords.reduce((s, k) => s + (m.text.toLowerCase().includes(k) ? 1 : 0), 0)
        }))
            .filter(o => o.score > 0)
            .sort((a, b) => b.score - a.score || b.m.timestamp - a.m.timestamp)
            .slice(0, limit)
            .map(o => o.m.text);
    }
    save() {
        fs.writeFileSync(this.filePath, JSON.stringify(this.memories, null, 2));
    }
    load() {
        if (fs.existsSync(this.filePath)) {
            const data = fs.readFileSync(this.filePath, 'utf-8');
            this.memories = JSON.parse(data);
        }
    }
}
//# sourceMappingURL=ExperienceMemory.js.map