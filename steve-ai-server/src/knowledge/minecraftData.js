import minecraftData from 'minecraft-data';
import { WikiRetriever } from './WikiRetriever.js';
const mcData = minecraftData('1.20.1');
// ── KnowledgeBase ─────────────────────────────────────────────────────────────
export class KnowledgeBase {
    // ── Item / Block lookup ───────────────────────────────────────────────────
    static getItemByName(name) {
        return mcData.itemsByName[name.replace('minecraft:', '')] ?? null;
    }
    static getBlockByName(name) {
        return mcData.blocksByName[name.replace('minecraft:', '')] ?? null;
    }
    static getEntityByName(name) {
        return mcData.entitiesByName?.[name] ?? null;
    }
    // ── Recipe query ──────────────────────────────────────────────────────────
    static getRecipes(itemName, inventory = {}) {
        const item = this.getItemByName(itemName);
        if (!item)
            return [];
        const rawRecipes = mcData.recipes[item.id] ?? [];
        const results = [];
        for (const raw of rawRecipes) {
            const ingredients = this.parseIngredients(raw);
            const method = raw.requiresTable ? 'crafting_table' : 'inventory';
            const outputCount = raw.result?.count ?? 1;
            const missing = [];
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
    static parseIngredients(raw) {
        const ingredients = [];
        const countMap = {};
        const rawIngs = raw.ingredients ?? raw.inShape?.flat() ?? [];
        for (const ing of rawIngs) {
            if (!ing || ing.id == null)
                continue;
            countMap[ing.id] = (countMap[ing.id] ?? 0) + (ing.count ?? 1);
        }
        for (const [idStr, count] of Object.entries(countMap)) {
            const id = Number(idStr);
            const item = mcData.items[id];
            if (item) {
                ingredients.push({ id, name: item.name, count: count });
            }
        }
        return ingredients;
    }
    static getCraftingTree(itemName, depth = 0) {
        if (depth > 6)
            return null;
        const item = this.getItemByName(itemName);
        if (!item)
            return null;
        const recipes = this.getRecipes(itemName);
        const subDeps = [];
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
    static formatRecipesForPrompt(itemName, inventory = {}) {
        const recipes = this.getRecipes(itemName, inventory);
        if (recipes.length === 0)
            return `No recipe found for: ${itemName}`;
        const lines = [`Recipe for ${itemName}:`];
        for (const r of recipes) {
            const ings = r.ingredients.map(i => `${i.count}x ${i.name}`).join(', ');
            const status = r.canCraft ? '✅ can craft' : `❌ missing: ${r.missing?.map(m => `${m.name}(${m.have}/${m.need})`).join(', ')}`;
            lines.push(`  [${r.method}] ${ings} → ${r.output.count}x ${r.output.name} | ${status}`);
        }
        return lines.join('\n');
    }
    static formatCraftingChainForPrompt(itemName) {
        const tree = this.getCraftingTree(itemName);
        if (!tree)
            return `Unknown item: ${itemName}`;
        const lines = [`Crafting chain for ${itemName}:`];
        const walk = (node, indent = 0) => {
            const prefix = '  '.repeat(indent);
            if (node.recipes.length > 0) {
                const r = node.recipes[0];
                if (r) {
                    const ings = r.ingredients.map(i => `${i.count}x ${i.name}`).join(' + ');
                    lines.push(`${prefix}${node.name}: needs ${ings} [${r.method}]`);
                }
            }
            for (const sub of node.subDeps)
                walk(sub, indent + 1);
        };
        walk(tree);
        return lines.join('\n');
    }
    static getRequiredToolTier(blockName) {
        const tiers = {
            stone: 'wooden_pickaxe', cobblestone: 'wooden_pickaxe', coal_ore: 'wooden_pickaxe',
            iron_ore: 'stone_pickaxe', copper_ore: 'stone_pickaxe',
            gold_ore: 'iron_pickaxe', lapis_ore: 'iron_pickaxe',
            diamond_ore: 'iron_pickaxe', redstone_ore: 'iron_pickaxe',
            emerald_ore: 'iron_pickaxe', ancient_debris: 'diamond_pickaxe',
            obsidian: 'diamond_pickaxe', crying_obsidian: 'diamond_pickaxe',
            netherite_block: 'diamond_pickaxe', deepslate_diamond_ore: 'iron_pickaxe'
        };
        return tiers[blockName.replace('minecraft:', '')] ?? 'none';
    }
    // ── Wiki RAG Integration ─────────────────────────────────────────────────
    /**
     * Build a full context block including structured data and Wiki RAG.
     */
    static getFullContext(taskName) {
        const lines = [];
        // Tier 1: Structured Data
        if (taskName.includes('craft')) {
            const itemName = taskName.replace('craft_', '');
            lines.push(this.formatCraftingChainForPrompt(itemName));
        }
        else if (taskName.includes('mine')) {
            const itemName = taskName.replace('mine_', '');
            const tier = this.getRequiredToolTier(itemName);
            if (tier !== 'none')
                lines.push(`Required tool tier: ${tier}`);
        }
        else if (taskName.includes('smelt')) {
            const itemName = taskName.replace('smelt_', '');
            const recipes = this.getRecipes(itemName, {});
            const firstRecipe = recipes[0];
            if (firstRecipe && firstRecipe.ingredients && firstRecipe.ingredients[0]) {
                lines.push(`Smelting info for ${itemName}: Needs furnace and coal. Ingredients: ${firstRecipe.ingredients[0].name}.`);
            }
        }
        // Tier 2: Wiki RAG
        const wikiInsight = WikiRetriever.getAdviceForTask(taskName);
        if (wikiInsight) {
            lines.push(wikiInsight);
        }
        return lines.join('\n');
    }
}
//# sourceMappingURL=minecraftData.js.map