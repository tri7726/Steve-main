import { LLMProvider } from "./LLMProvider.js";
export declare class GroqProvider extends LLMProvider {
    private clients;
    private currentIndex;
    private modelName;
    constructor(model?: string);
    private getNextClient;
    chat(prompt: string, modelOverride?: string, imageBuffer?: Buffer): Promise<string>;
    private chatWithRetry;
}
//# sourceMappingURL=GroqProvider.d.ts.map