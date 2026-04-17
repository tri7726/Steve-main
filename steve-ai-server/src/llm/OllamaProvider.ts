import { LLMProvider } from './LLMProvider.js';
import ollama from 'ollama';

export class OllamaProvider extends LLMProvider {
    private defaultModel: string;

    constructor(defaultModel: string = 'llama3.1') {
        super();
        this.defaultModel = defaultModel;
    }

    async chat(prompt: string, model?: string, imageBuffer?: Buffer): Promise<string> {
        try {
            const response = await ollama.chat({
                model: model || this.defaultModel,
                messages: [{ role: 'user', content: prompt }],
            });
            return response.message.content;
        } catch (error) {
            console.error('Ollama Error:', error);
            return `[Ollama Error] ${error}`;
        }
    }
}
