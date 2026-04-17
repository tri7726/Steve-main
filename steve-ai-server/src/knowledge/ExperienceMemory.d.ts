export interface Memory {
    text: string;
    timestamp: number;
}
export declare class ExperienceMemory {
    private memories;
    private filePath;
    constructor(storageDir?: string);
    add(text: string): void;
    /**
     * Simple keyword search for RAG.
     */
    search(query: string, limit?: number): string[];
    private save;
    private load;
}
//# sourceMappingURL=ExperienceMemory.d.ts.map