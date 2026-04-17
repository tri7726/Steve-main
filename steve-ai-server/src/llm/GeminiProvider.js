import { GoogleGenerativeAI } from "@google/generative-ai";
import { LLMProvider } from "./LLMProvider.js";
import * as dotenv from 'dotenv';
dotenv.config();
export class GeminiProvider extends LLMProvider {
    genAI;
    modelName;
    constructor(apiKey, model = "gemini-1.5-flash") {
        super();
        const finalKey = apiKey || process.env.GEMINI_API_KEY || "";
        this.genAI = new GoogleGenerativeAI(finalKey);
        this.modelName = model;
    }
    async chat(prompt, modelOverride, imageBuffer) {
        const model = this.genAI.getGenerativeModel({ model: modelOverride || this.modelName });
        try {
            const parts = [prompt];
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
        }
        catch (error) {
            console.error("[GeminiProvider] Error:", error.message);
            return `[Error] ${error.message}`;
        }
    }
}
//# sourceMappingURL=GeminiProvider.js.map