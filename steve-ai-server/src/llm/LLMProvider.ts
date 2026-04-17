export abstract class LLMProvider {
    abstract chat(prompt: string, model?: string, imageBuffer?: Buffer): Promise<string>;
}
