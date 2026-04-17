import minecraftData from 'minecraft-data';
export interface RecipeIngredient {
    id: number;
    name: string;
    count: number;
}
export interface RecipeResult {
    method: 'crafting_table' | 'inventory' | 'furnace' | 'unknown';
    ingredients: RecipeIngredient[];
    output: {
        name: string;
        count: number;
    };
    canCraft?: boolean;
    missing?: Array<{
        name: string;
        need: number;
        have: number;
    }>;
}
export interface CraftingNode {
    name: string;
    recipes: RecipeResult[];
    subDeps: CraftingNode[];
}
export declare class KnowledgeBase {
    static getItemByName(name: string): minecraftData.Item | null;
    static getBlockByName(name: string): minecraftData.IndexedBlock | null;
    static getEntityByName(name: string): any;
    static getRecipes(itemName: string, inventory?: Record<string, number>): RecipeResult[];
    private static parseIngredients;
    static getCraftingTree(itemName: string, depth?: number): CraftingNode | null;
    static formatRecipesForPrompt(itemName: string, inventory?: Record<string, number>): string;
    static formatCraftingChainForPrompt(itemName: string): string;
    static getRequiredToolTier(blockName: string): string;
    /**
     * Build a full context block including structured data and Wiki RAG.
     */
    static getFullContext(taskName: string): string;
}
//# sourceMappingURL=minecraftData.d.ts.map