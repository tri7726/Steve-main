import { GoogleGenerativeAI } from "@google/generative-ai";
import type { Part } from "@google/generative-ai";
import { LLMProvider } from "./LLMProvider.js";
import * as dotenv from 'dotenv';

dotenv.config();

export class GeminiProvider extends LLMProvider {
    private genAI: GoogleGenerativeAI;
    private modelName: string;

    constructor(apiKey?: string, model: string = "gemini-1.5-flash") {
        super();
        const finalKey = apiKey || process.env.GEMINI_API_KEY || "";
        this.genAI = new GoogleGenerativeAI(finalKey);
        this.modelName = model;
    }

    async chat(prompt: string, modelOverride?: string, imageBuffer?: Buffer): Promise<string> {
        const model = this.genAI.getGenerativeModel({ model: modelOverride || this.modelName });
        
        try {
            const parts: (string | Part)[] = [prompt];
            
            if (imageBuffer) {
                parts.push({
                    inlineData: {
                        data: imageBuffer.toString("base64"),
                        mimeType: "image/png"
                    }
                });
            }

            const result = await model.generateContent(parts);
            const response = await result.response;
            return response.text();
        } catch (error: any) {
            console.error("[GeminiProvider] Error:", error.message);
            return `[Error] ${error.message}`;
        }
    }
}
