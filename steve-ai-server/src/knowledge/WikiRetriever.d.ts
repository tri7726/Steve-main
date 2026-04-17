export interface WikiEntry {
    category: string;
    topic: string;
    content: string;
}
export declare class WikiRetriever {
    private static knowledge;
    private static loadKnowledge;
    /**
     * Search for relevant wiki context based on query keywords.
     */
    static search(query: string): string[];
    /**
     * Get tactical advice for a specific task.
     */
    static getAdviceForTask(taskName: string): string;
}
//# sourceMappingURL=WikiRetriever.d.ts.map