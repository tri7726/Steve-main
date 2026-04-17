import * as minecraftData from 'minecraft-data';

// Tải dữ liệu của phiên bản Minecraft Forge 1.20.1 đang dùng
const mcData = minecraftData.default('1.20.1');

export class KnowledgeBase {
    /**
     * Get block name by block ID or typed text
     */
    static getBlockInfo(idOrName: string | number) {
        if (typeof idOrName === 'number') {
            return mcData.blocks[idOrName];
        } else {
            return mcData.blocksByName[idOrName.replace('minecraft:', '')];
        }
    }

    /**
     * Get item recipe basic info
     */
    static getRecipe(itemName: string) {
        const item = mcData.itemsByName[itemName.replace('minecraft:', '')];
        if (!item) return null;
        
        const recipes = mcData.recipes[item.id];
        return recipes ? recipes : null;
    }
}
