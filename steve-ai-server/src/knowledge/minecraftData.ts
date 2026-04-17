import minecraftData from 'minecraft-data';

const mcData = minecraftData('1.20.1');

// ── Types ─────────────────────────────────────────────────────────────────────

export interface RecipeIngredient {
    id: number;
    name: string;
    count: number;
}

export interface RecipeResult {
    method: 'crafting_table' | 'inventory' | 'furnace' | 'unknown';
    ingredients: RecipeIngredient[];
    output: { name: string; count: number };
    canCraft?: boolean;
    missing?: Array<{ name: string; need: number; have: number }>;
}

export interface CraftingNode {
    name: string;
    recipes: RecipeResult[];
    subDeps: CraftingNode[];
}

// ── KnowledgeBase ─────────────────────────────────────────────────────────────

export class KnowledgeBase {

    // ── Item / Block lookup ───────────────────────────────────────────────────

    static getItemByName(name: string) {
        return mcData.itemsByName[name.replace('minecraft:', '')] ?? null;
    }

    static getBlockByName(name: string) {
        return mcData.blocksByName[name.replace('minecraft:', '')] ?? null;
    }

    static getEntityByName(name: string) {
        return (mcData as any).entitiesByName?.[name] ?? null;
    }

    // ── Recipe query ──────────────────────────────────────────────────────────

    /**
     * Trả về danh sách recipe cho item, kèm thông tin có thể craft không.
     * @param itemName  tên item (vd: "wooden_pickaxe")
     * @param inventory map tên → số lượng hiện có (optional)
     */
    static getRecipes(itemName: string, inventory: Record<string, number> = {}): RecipeResult[] {
        const item = this.getItemByName(itemName);
        if (!item) return [];

        const rawRecipes: any[] = mcData.recipes[item.id] ?? [];
        const results: RecipeResult[] = [];

        for (const raw of rawRecipes) {
            const ingredients = this.parseIngredients(raw);
            const method = raw.requiresTable ? 'crafting_table' : 'inventory';
            const outputCount = raw.result?.count ?? 1;

            // Check availability
            const missing: Array<{ name: string; need: number; have: number }> = [];
            for (const ing of ingredients) {
                const have = inventory[ing.name] ?? 0;
                if (have < ing.count) {
                    missing.push({ name: ing.name, need: ing.count, have });
                }
            }

            results.push({
                method,
                ingredients,
                output: { name: item.name, count: outputCount },
                canCraft: missing.length === 0,
                missing,
            });
        }

        return results;
    }

    private static parseIngredients(raw: any): RecipeIngredient[] {
        const ingredients: RecipeIngredient[] = [];
        const countMap: Record<number, number> = {};

        // shaped / shapeless both have `ingredients` or `inShape`
        const rawIngs: any[] = raw.ingredients ?? raw.inShape?.flat() ?? [];
        for (const ing of rawIngs) {
            if (!ing || ing.id == null) continue;
            countMap[ing.id] = (countMap[ing.id] ?? 0) + (ing.count ?? 1);
        }

        for (const [idStr, count] of Object.entries(countMap)) {
            const id = Number(idStr);
            const item = mcData.items[id];
            if (item) {
                ingredients.push({ id, name: item.name, count: count as number });
            }
        }
        return ingredients;
    }

    // ── Crafting tree (recursive) ─────────────────────────────────────────────

    /**
     * Xây dựng cây phụ thuộc craft đệ quy (max depth 6).
     */
    static getCraftingTree(itemName: string, depth = 0): CraftingNode | null {
        if (depth > 6) return null;
        const item = this.getItemByName(itemName);
        if (!item) return null;

        const recipes = this.getRecipes(itemName);
        const subDeps: CraftingNode[] = [];

        for (const recipe of recipes) {
            for (const ing of recipe.ingredients) {
                const sub = this.getCraftingTree(ing.name, depth + 1);
                if (sub && !subDeps.find(s => s.name === sub.name)) {
                    subDeps.push(sub);
                }
            }
        }

        return { name: item.name, recipes, subDeps };
    }

    // ── Format for LLM prompt ─────────────────────────────────────────────────

    /**
     * Format recipe thành text ngắn gọn để inject vào LLM prompt.
     */
    static formatRecipesForPrompt(itemName: string, inventory: Record<string, number> = {}): string {
        const recipes = this.getRecipes(itemName, inventory);
        if (recipes.length === 0) return `No recipe found for: ${itemName}`;

        const lines: string[] = [`Recipe for ${itemName}:`];
        for (const r of recipes) {
            const ings = r.ingredients.map(i => `${i.count}x ${i.name}`).join(', ');
            const status = r.canCraft ? '✅ can craft' : `❌ missing: ${r.missing?.map(m => `${m.name}(${m.have}/${m.need})`).join(', ')}`;
            lines.push(`  [${r.method}] ${ings} → ${r.output.count}x ${r.output.name} | ${status}`);
        }
        return lines.join('\n');
    }

    /**
     * Format danh sách items cần để craft một item (flat, không đệ quy).
     * Dùng để inject vào Curriculum/SkillGenerator prompt.
     */
    static formatCraftingChainForPrompt(itemName: string): string {
        const tree = this.getCraftingTree(itemName);
        if (!tree) return `Unknown item: ${itemName}`;

        const lines: string[] = [`Crafting chain for ${itemName}:`];
        const walk = (node: CraftingNode, indent = 0) => {
            const prefix = '  '.repeat(indent);
            if (node.recipes.length > 0) {
                const r = node.recipes[0];
                if (r) {
                    const ings = r.ingredients.map(i => `${i.count}x ${i.name}`).join(' + ');
                    lines.push(`${prefix}${node.name}: needs ${ings} [${r.method}]`);
                }
            }
            for (const sub of node.subDeps) walk(sub, indent + 1);
        };
        walk(tree);
        return lines.join('\n');
    }

    // ── Tool tier helper ──────────────────────────────────────────────────────

    static getRequiredToolTier(blockName: string): string {
        const tiers: Record<string, string> = {
            stone: 'wooden_pickaxe', cobblestone: 'wooden_pickaxe', coal_ore: 'wooden_pickaxe',
            iron_ore: 'stone_pickaxe', copper_ore: 'stone_pickaxe',
            gold_ore: 'iron_pickaxe', lapis_ore: 'iron_pickaxe',
            diamond_ore: 'iron_pickaxe', redstone_ore: 'iron_pickaxe',
            emerald_ore: 'iron_pickaxe', ancient_debris: 'diamond_pickaxe',
        };
        return tiers[blockName.replace('minecraft:', '')] ?? 'none';
    }
}
