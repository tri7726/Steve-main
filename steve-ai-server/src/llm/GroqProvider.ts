import Groq from "groq-sdk";
import { LLMProvider } from "./LLMProvider.js";
import * as dotenv from 'dotenv';

dotenv.config();

export class GroqProvider extends LLMProvider {
    private clients: Groq[] = [];
    private currentIndex: number = 0;
    private modelName: string;

    constructor(model: string = "llama-3.2-11b-vision-preview") {
        super();
        const keysStr = process.env.GROQ_API_KEYS || process.env.GROQ_API_KEY || "";
        const keys = keysStr.split(',').map(k => k.trim()).filter(k => k.length > 0);
        
        if (keys.length === 0) {
            console.error("[GroqProvider] Warning: No API keys found in .env");
        }

        this.clients = keys.map(key => new Groq({ apiKey: key }));
        this.modelName = model;
        
        console.log(`[GroqProvider] Initialized with ${this.clients.length} API keys for rotation.`);
    }

    private getNextClient(): Groq {
        if (this.clients.length === 0) throw new Error("No Groq API keys available");
        const client = this.clients[this.currentIndex];
        if (!client) throw new Error(`Groq client at index ${this.currentIndex} is undefined`);
        
        this.currentIndex = (this.currentIndex + 1) % this.clients.length;
        return client;
    }

    async chat(prompt: string, modelOverride?: string, imageBuffer?: Buffer): Promise<string> {
        return this.chatWithRetry(0, prompt, modelOverride, imageBuffer);
    }

    private async chatWithRetry(retryCount: number, prompt: string, modelOverride?: string, imageBuffer?: Buffer): Promise<string> {
        const model = modelOverride || this.modelName;
        
        // Limit retries to number of clients to avoid infinite loops
        if (retryCount >= this.clients.length) {
            return "[Error] All Groq API keys reached their rate limits. Please wait.";
        }

        const client = this.getNextClient();
        
        try {
            const messages: any[] = [];
            if (imageBuffer) {
                messages.push({
                    role: "user",
                    content: [
                        { type: "text", text: prompt },
                        {
                            type: "image_url",
                            image_url: {
                                url: `data:image/png;base64,${imageBuffer.toString("base64")}`,
                            },
                        },
                    ],
                });
            } else {
                messages.push({
                    role: "user",
                    content: prompt,
                });
            }

            const chatCompletion = await client.chat.completions.create({
                messages: messages,
                model: model,
                temperature: 0.1,
                max_tokens: 1024,
            });

            return chatCompletion.choices[0]?.message?.content || "";

        } catch (error: any) {
            // Handle Rate Limit (429) errors by rotating to the next key
            if (error.status === 429 || error.message?.includes("rate_limit")) {
                console.warn(`[GroqProvider] Key ${this.currentIndex} rate limited. Rotating to next key... (Retry ${retryCount + 1}/${this.clients.length})`);
                return this.chatWithRetry(retryCount + 1, prompt, modelOverride, imageBuffer);
            }

            console.error("[GroqProvider] Fatal Error:", error.message);
            return `[Error] ${error.message}`;
        }
    }
}
