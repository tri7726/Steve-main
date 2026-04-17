package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.core.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CraftItemAction: checks SteveInventory for required ingredients before crafting.
 * No item spawning from thin air — must have materials in inventory.
 */
@SuppressWarnings("null") // Minecraft/Forge API (getBlockState, registryAccess, blockPosition) guaranteed non-null at runtime
public class CraftItemAction extends BaseAction {
    private String itemName;
    private int quantity;
    private int ticksRunning;
    private static final int MAX_TICKS = 200;

    public CraftItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item");
        quantity = task.getIntParameter("quantity", 1);
        ticksRunning = 0;

        if (itemName == null || itemName.isEmpty()) {
            result = ActionResult.failure("No item specified for crafting");
            return;
        }

        // Normalize item name — handle aliases and Vietnamese
        itemName = normalizeItemName(itemName);

        String fullId = itemName.contains(":") ? itemName
                : "minecraft:" + itemName.toLowerCase().replace(" ", "_");

        // Strip invalid chars
        fullId = fullId.replaceAll("[^a-z0-9/:._-]", "");

        ResourceLocation itemRL;
        try {
            itemRL = ResourceLocation.parse(fullId);
        } catch (Exception e) {
            result = ActionResult.failure("Invalid item name: " + itemName);
            return;
        }

        if (ForgeRegistries.ITEMS.getValue(itemRL) == null) {
            result = ActionResult.failure("Unknown item: " + itemName);
            return;
        }
        net.minecraft.world.item.Item targetItem = ForgeRegistries.ITEMS.getValue(itemRL);
        assert targetItem != null;

        List<CraftingRecipe> recipes = steve.level().getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING);

        Optional<CraftingRecipe> matchingRecipe = recipes.stream()
                .filter(r -> r.getResultItem(steve.level().registryAccess()).getItem() == targetItem)
                .findFirst();

        if (matchingRecipe.isEmpty()) {
            result = ActionResult.failure("No crafting recipe found for: " + itemName);
            return;
        }

        CraftingRecipe recipe = matchingRecipe.get();

        // ── Kiểm tra nguyên liệu trong SteveInventory ────────────────────────
        SteveInventory inv = steve.getMemory().getInventory();
        Map<net.minecraft.world.item.Item, Integer> needed = buildIngredientMap(recipe, quantity);

        for (Map.Entry<net.minecraft.world.item.Item, Integer> entry : needed.entrySet()) {
            if (!inv.hasItem(entry.getKey(), entry.getValue())) {
                String missingName = entry.getKey().getDescription().getString();
                
                // ── Tự động giải quyết phụ thuộc (Dependency Resolution) ────────
                try {
                    List<Task> prerequisites = com.steve.ai.action.ResourceDependencyResolver.resolve(
                        steve, entry.getKey(), entry.getValue());

                    if (!prerequisites.isEmpty()) {
                        // Chỉ log, không chat spam
                        SteveMod.LOGGER.info("Steve '{}' resolving dependency for {}: enqueuing {} tasks",
                            steve.getSteveName(), missingName, prerequisites.size());

                        for (Task t : prerequisites) {
                            steve.getActionExecutor().enqueue(t);
                        }
                        // Enqueue lại craft task sau khi có nguyên liệu
                        steve.getActionExecutor().enqueue(task);

                        // Report status but don't trigger AI re-plan
                        result = new ActionResult(false, "Đang chuẩn bị nguyên liệu: " + missingName, false);
                        return;
                    }
                } catch (Throwable t) {
                    SteveMod.LOGGER.error("CRITICAL: ResourceDependencyResolver failed", t);
                    result = new ActionResult(false, "Lỗi phân giải nguyên liệu: " + t.getMessage(), false);
                    return;
                }

                result = new ActionResult(false, "Thiếu nguyên liệu: " + missingName, false);
                return;
            }
        }


        // Trừ nguyên liệu
        for (Map.Entry<net.minecraft.world.item.Item, Integer> entry : needed.entrySet()) {
            inv.removeItem(entry.getKey(), entry.getValue());
        }

        // Cần bàn chế tạo 3x3?
        boolean needs3x3 = recipe.getIngredients().size() > 4;
        if (needs3x3 && findCraftingTable() == null) {
            // Đặt bàn chế tạo nếu có trong inventory
            if (inv.hasItem(net.minecraft.world.item.Items.CRAFTING_TABLE, 1)) {
                inv.removeItem(net.minecraft.world.item.Items.CRAFTING_TABLE, 1);
                BlockPos placePos = com.steve.ai.action.BlockPlacementHelper.findPlacementPos(steve, 4);
                if (placePos == null) placePos = steve.blockPosition().above();
                steve.level().setBlock(placePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                SteveMod.LOGGER.info("Steve '{}' placed crafting table from inventory", steve.getSteveName());
            } else {
                // Hoàn lại nguyên liệu đã trừ
                for (Map.Entry<net.minecraft.world.item.Item, Integer> entry : needed.entrySet()) {
                    inv.addItem(new ItemStack(entry.getKey(), entry.getValue()));
                }
                result = ActionResult.failure("Can khong co ban che tao de craft " + itemName);
                steve.sendChatMessage("Tui can ban che tao nhung khong co!");
                return;
            }
        }

        // Thêm kết quả vào SteveInventory (không spawn từ hư không)
        ItemStack craftedStack = recipe.getResultItem(steve.level().registryAccess()).copy();
        craftedStack.setCount(quantity);
        inv.addItem(craftedStack);

        steve.swing(InteractionHand.MAIN_HAND, true);
        result = ActionResult.success("Crafted " + quantity + "x " + itemName);
        steve.sendChatMessage("Da craft " + quantity + "x " + itemName + "!");
        SteveMod.LOGGER.info("Steve '{}' crafted {}x {}", steve.getSteveName(), quantity, itemName);
    }

    /**
     * Tính tổng nguyên liệu cần thiết cho quantity lần craft.
     */
    private Map<net.minecraft.world.item.Item, Integer> buildIngredientMap(CraftingRecipe recipe, int qty) {
        Map<net.minecraft.world.item.Item, Integer> map = new HashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            ItemStack[] items = ingredient.getItems();
            if (items.length == 0) continue;
            net.minecraft.world.item.Item item = items[0].getItem();
            map.merge(item, qty, Integer::sum);
        }
        return map;
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS && result == null) {
            result = ActionResult.failure("Crafting timed out");
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Craft " + quantity + "x " + itemName;
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.INTERACTION);
    }

    private BlockPos findCraftingTable() {
        BlockPos center = steve.blockPosition();
        for (int r = 1; r <= 10; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        var state = steve.level().getBlockState(pos);
                        if (state != null && state.getBlock() == Blocks.CRAFTING_TABLE) return pos;
                    }
                }
            }
        }
        return null;
    }

    /** Normalize item names from LLM — handles aliases, Vietnamese, common mistakes */
    private String normalizeItemName(String name) {
        if (name == null) return "";
        name = name.toLowerCase().trim().replace(" ", "_");

        Map<String, String> aliases = new HashMap<>();
        // Tools
        aliases.put("wooden_pickaxe", "wooden_pickaxe");
        aliases.put("wood_pickaxe", "wooden_pickaxe");
        aliases.put("stone_pickaxe", "stone_pickaxe");
        aliases.put("iron_pickaxe", "iron_pickaxe");
        aliases.put("diamond_pickaxe", "diamond_pickaxe");
        aliases.put("pickaxe", "wooden_pickaxe");
        aliases.put("cuoc", "wooden_pickaxe");
        aliases.put("cuốc", "wooden_pickaxe");
        aliases.put("wooden_sword", "wooden_sword");
        aliases.put("wood_sword", "wooden_sword");
        aliases.put("sword", "wooden_sword");
        aliases.put("kiem", "wooden_sword");
        aliases.put("kiếm", "wooden_sword");
        aliases.put("axe", "wooden_axe");
        aliases.put("wooden_axe", "wooden_axe");
        aliases.put("wood_axe", "wooden_axe");
        aliases.put("riu", "wooden_axe");
        aliases.put("rìu", "wooden_axe");
        aliases.put("shovel", "wooden_shovel");
        aliases.put("wooden_shovel", "wooden_shovel");
        aliases.put("wood_shovel", "wooden_shovel");
        aliases.put("xeng", "wooden_shovel");
        aliases.put("xẻng", "wooden_shovel");
        // Blocks/items
        aliases.put("planks", "oak_planks");
        aliases.put("wood_planks", "oak_planks");
        aliases.put("oak_planks", "oak_planks");
        aliases.put("go_van", "oak_planks");
        aliases.put("gỗ_ván", "oak_planks");
        aliases.put("stick", "stick");
        aliases.put("gay", "stick");
        aliases.put("gậy", "stick");
        aliases.put("torch", "torch");
        aliases.put("duoc", "torch");
        aliases.put("đuốc", "torch");
        aliases.put("crafting_table", "crafting_table");
        aliases.put("workbench", "crafting_table");
        aliases.put("ban_che_tao", "crafting_table");
        aliases.put("furnace", "furnace");
        aliases.put("lo_nung", "furnace");
        aliases.put("lò_nung", "furnace");
        aliases.put("chest", "chest");
        aliases.put("rui_ro", "chest");
        aliases.put("hom", "chest");
        aliases.put("hòm", "chest");
        aliases.put("bread", "bread");
        aliases.put("banh_mi", "bread");
        aliases.put("bánh_mì", "bread");
        aliases.put("bowl", "bowl");
        aliases.put("bat", "bowl");
        aliases.put("bát", "bowl");
        aliases.put("bucket", "bucket");
        aliases.put("xo", "bucket");
        aliases.put("xô", "bucket");
        aliases.put("ladder", "ladder");
        aliases.put("thang", "ladder");
        aliases.put("thang_leo", "ladder");
        aliases.put("fence", "oak_fence");
        aliases.put("hang_rao", "oak_fence");
        aliases.put("hàng_rào", "oak_fence");
        aliases.put("door", "oak_door");
        aliases.put("cua", "oak_door");
        aliases.put("cửa", "oak_door");
        aliases.put("bed", "white_bed");
        aliases.put("giuong", "white_bed");
        aliases.put("giường", "white_bed");
        aliases.put("bow", "bow");
        aliases.put("cung", "bow");
        aliases.put("arrow", "arrow");
        aliases.put("ten", "arrow");
        aliases.put("tên", "arrow");
        aliases.put("shield", "shield");
        aliases.put("khien", "shield");
        aliases.put("khiên", "shield");
        aliases.put("armor", "iron_chestplate");
        aliases.put("helmet", "iron_helmet");
        aliases.put("chestplate", "iron_chestplate");
        aliases.put("leggings", "iron_leggings");
        aliases.put("boots", "iron_boots");

        // Strip invalid unicode chars after alias lookup
        String resolved = aliases.getOrDefault(name, name);
        resolved = resolved.replaceAll("[^a-z0-9/_.-]", "");
        return resolved.isBlank() ? name : resolved;
    }
}
