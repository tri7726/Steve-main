import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
export class WikiRetriever {
    static knowledge = null;
    static loadKnowledge() {
        if (this.knowledge && Object.keys(this.knowledge).length > 0)
            return;
        try {
            const filePath = path.join(__dirname, 'minecraft_wiki.json');
            if (!fs.existsSync(filePath)) {
                console.warn('[WikiRetriever] minecraft_wiki.json not found at:', filePath);
                this.knowledge = {};
                return;
            }
            const raw = fs.readFileSync(filePath, 'utf-8');
            if (!raw || raw.trim() === '') {
                this.knowledge = {};
                return;
            }
            this.knowledge = JSON.parse(raw);
        }
        catch (e) {
            console.error('[WikiRetriever] Error loading wiki knowledge:', e);
            this.knowledge = {};
        }
    }
    /**
     * Search for relevant wiki context based on query keywords.
     */
    static search(query) {
        this.loadKnowledge();
        const results = [];
        const normalizedQuery = query.toLowerCase();
        // Simple keyword matching across all categories and keys
        for (const [category, topics] of Object.entries(this.knowledge)) {
            for (const [topic, content] of Object.entries(topics)) {
                if (normalizedQuery.includes(topic.toLowerCase()) ||
                    topic.toLowerCase().includes(normalizedQuery) ||
                    content.toLowerCase().includes(normalizedQuery)) {
                    results.push(`[Wiki:${category}] ${topic}: ${content}`);
                }
            }
        }
        return results;
    }
    /**
     * Get tactical advice for a specific task.
     */
    static getAdviceForTask(taskName) {
        const results = this.search(taskName.replace(/_/g, ' '));
        if (results.length === 0)
            return '';
        return '\n### Minecraft Wiki Insights:\n' + results.join('\n');
    }
}
//# sourceMappingURL=WikiRetriever.js.map