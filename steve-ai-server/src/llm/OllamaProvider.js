import { LLMProvider } from './LLMProvider.js';
import ollama from 'ollama';
export class OllamaProvider extends LLMProvider {
    defaultModel;
    constructor(defaultModel = 'llama3.1') {
        super();
        this.defaultModel = defaultModel;
    }
    async chat(prompt, model, imageBuffer) {
        try {
            const response = await ollama.chat({
                model: model || this.defaultModel,
                messages: [{ role: 'user', content: prompt }],
            });
            return response.message.content;
        }
        catch (error) {
            console.error('Ollama Error:', error);
            return `[Ollama Error] ${error}`;
        }
    }
}
//# sourceMappingURL=OllamaProvider.js.map