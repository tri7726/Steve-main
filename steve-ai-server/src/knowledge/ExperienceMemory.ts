import * as fs from 'fs';
import * as path from 'path';

export interface Memory {
    text: string;
    timestamp: number;
}

export class ExperienceMemory {
    private memories: Memory[] = [];
    private filePath: string;

    constructor(storageDir: string = './data') {
        if (!fs.existsSync(storageDir)) {
            fs.mkdirSync(storageDir, { recursive: true });
        }
        this.filePath = path.join(storageDir, 'experience.json');
        this.load();
    }

    public add(text: string) {
        this.memories.push({ text, timestamp: Date.now() });
        this.save();
    }

    /**
     * Simple keyword search for RAG.
     */
    public search(query: string, limit: number = 3): string[] {
        const keywords = query.toLowerCase().split(' ').filter(w => w.length > 3);
        if (keywords.length === 0) return this.memories.slice(-limit).map(m => m.text);

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

    private save() {
        fs.writeFileSync(this.filePath, JSON.stringify(this.memories, null, 2));
    }

    private load() {
        if (fs.existsSync(this.filePath)) {
            const data = fs.readFileSync(this.filePath, 'utf-8');
            this.memories = JSON.parse(data);
        }
    }
}
