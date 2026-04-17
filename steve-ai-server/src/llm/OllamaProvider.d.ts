import { LLMProvider } from './LLMProvider.js';
export declare class OllamaProvider extends LLMProvider {
    private defaultModel;
    constructor(defaultModel?: string);
    chat(prompt: string, model?: string, imageBuffer?: Buffer): Promise<string>;
}
//# sourceMappingURL=OllamaProvider.d.ts.map