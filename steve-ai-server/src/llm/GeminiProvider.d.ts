import { LLMProvider } from "./LLMProvider.js";
export declare class GeminiProvider extends LLMProvider {
    private genAI;
    private modelName;
    constructor(apiKey?: string, model?: string);
    chat(prompt: string, modelOverride?: string, imageBuffer?: Buffer): Promise<string>;
}
//# sourceMappingURL=GeminiProvider.d.ts.map