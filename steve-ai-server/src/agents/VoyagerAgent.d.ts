import * as grpc from '@grpc/grpc-js';
export declare class VoyagerAgent {
    private llm;
    private visionLlm;
    private skills;
    private curriculum;
    private critic;
    private skillGenerator;
    private memory;
    private isExecutingTask;
    private currentTask;
    constructor();
    initialize(): Promise<void>;
    /**
     * Phương thức đánh giá State và gửi action.
     * Hàm này được gọi định kỳ hoặc khi có sự kiện từ bot.
     */
    evaluateAndAct(botState: any, stream: grpc.ServerDuplexStream<any, any>): Promise<void>;
    /**
     * Build critique prompt từ failed observation để feed vào SkillGenerator.
     */
    private buildCritiquePrompt;
}
//# sourceMappingURL=VoyagerAgent.d.ts.map