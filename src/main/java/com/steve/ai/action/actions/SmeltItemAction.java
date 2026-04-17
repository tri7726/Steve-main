package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.EnumSet;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SmeltItemAction: finds or places a furnace, then smelts the requested item.
 * Covers: ore → ingot, raw food → cooked food, sand → glass, etc.
 */
@SuppressWarnings("null")
public class SmeltItemAction extends BaseAction {
    private String inputName;
    private int quantity;

    public SmeltItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        inputName = task.getStringParameter("item");
        quantity   = task.getIntParameter("quantity", 1);

        if (inputName == null || inputName.isEmpty()) {
            result = ActionResult.failure("No item specified for smelting");
            return;
        }

        // Normalize — handle aliases
        inputName = normalizeSmeltInput(inputName);

        // Resolve item
        String fullId = inputName.contains(":") ? inputName
                : "minecraft:" + inputName.toLowerCase().replace(" ", "_");
        fullId = fullId.replaceAll("[^a-z0-9/:._-]", "");

        ResourceLocation inputRL;
        try { inputRL = ResourceLocation.parse(fullId); }
        catch (Exception e) { result = ActionResult.failure("Invalid item: " + inputName); return; }

        if (ForgeRegistries.ITEMS.getValue(inputRL) == null) {
            result = ActionResult.failure("Unknown item to smelt: " + inputName);
            return;
        }
        net.minecraft.world.item.Item inputItem = ForgeRegistries.ITEMS.getValue(inputRL);
        ItemStack inputStack = new ItemStack(inputItem);

        // Find smelting recipe
        List<? extends AbstractCookingRecipe> recipes = steve.level().getRecipeManager()
                .getAllRecipesFor(RecipeType.SMELTING);

        Optional<? extends AbstractCookingRecipe> match = recipes.stream()
                .filter(r -> r.getIngredients().stream().anyMatch(ing -> ing.test(inputStack)))
                .findFirst();

        if (match.isEmpty()) {
            result = ActionResult.failure("No smelting recipe found for: " + inputName);
            return;
        }

        // Ensure furnace exists nearby; place one if needed
        BlockPos furnacePos = findFurnace();
        if (furnacePos == null) {
            furnacePos = placeFurnace();
        }
        if (furnacePos == null) {
            result = ActionResult.failure("Không có lò nung! Cần craft furnace trước.");
            steve.sendChatMessage("Tui cần lò nung nhưng không có! Craft furnace trước đi.");
            return;
        }

        // Put fuel + input into furnace if possible, then immediately collect result (fast sim)
        BlockEntity be = steve.level().getBlockEntity(furnacePos);
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            // Slot 0 = input, Slot 1 = fuel, Slot 2 = output

            // Lấy than từ SteveInventory thay vì sinh từ hư không
            com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
            int coalAvailable = 0;
            for (net.minecraft.world.item.ItemStack s : inv.getItems()) {
                if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) coalAvailable += s.getCount();
            }
            int coalNeeded = Math.max(1, (int) Math.ceil(quantity / 8.0)); // 1 coal = 8 smelt
            if (coalAvailable >= coalNeeded) {
                // Trừ than từ inventory
                int remaining = coalNeeded;
                remaining -= inv.removeItem(Items.COAL, remaining);
                if (remaining > 0) inv.removeItem(Items.CHARCOAL, remaining);
                furnace.setItem(1, new ItemStack(Items.COAL, coalNeeded));
            } else {
                result = ActionResult.failure(
                    "Không đủ than để nung " + quantity + "x " + inputName
                    + " (cần " + coalNeeded + " coal, có " + coalAvailable + ")");
                steve.sendChatMessage("Hết than rồi! Cần " + coalNeeded + " coal để nung.");
                return;
            }

            // Kiểm tra nguyên liệu đầu vào trong inventory
            if (!inv.hasItem(inputItem, quantity)) {
                result = ActionResult.failure(
                    "Không có " + quantity + "x " + inputName + " trong túi để nung");
                steve.sendChatMessage("Túi không có " + inputName + " để nung!");
                return;
            }
            inv.removeItem(inputItem, quantity);
            for (int i = 0; i < quantity; i++) {
                furnace.setItem(0, new ItemStack(inputItem));
            }
        }

        // Deliver smelted result immediately (simulated)
        if (match.isEmpty()) {
            result = ActionResult.failure("No smelting recipe for: " + inputName);
            return;
        }
        ItemStack output = match.get().getResultItem(steve.level().registryAccess()).copy();
        output.setCount(quantity);
        // Add to SteveInventory instead of spawning
        steve.getMemory().getInventory().addItem(output.copy());
        net.minecraft.world.entity.player.Player nearestPlayer = steve.level().getNearestPlayer(steve, 10.0);
        if (nearestPlayer != null) {
            nearestPlayer.getInventory().add(output);
        }

        steve.swing(InteractionHand.MAIN_HAND, true);
        result = ActionResult.success("Smelted " + quantity + "x " + inputName
                + " → " + output.getItem().getDescription().getString());
        SteveMod.LOGGER.info("Steve '{}' smelted {}x {}", steve.getSteveName(), quantity, inputName);
    }

    @Override protected void onTick() { }
    @Override protected void onCancel() { steve.getNavigation().stop(); }

    @Override
    public String getDescription() { return "Smelt " + quantity + "x " + inputName; }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.INTERACTION);
    }

    private BlockPos findFurnace() {
        BlockPos center = steve.blockPosition();
        for (int r = 1; r <= 12; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        if (steve.level().getBlockState(p).getBlock() == Blocks.FURNACE
                                || steve.level().getBlockState(p).getBlock() == Blocks.BLAST_FURNACE) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    private BlockPos placeFurnace() {
        com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
        if (!inv.hasItem(Items.FURNACE, 1)) {
            SteveMod.LOGGER.warn("Steve '{}' has no furnace in inventory", steve.getSteveName());
            return null;
        }
        inv.removeItem(Items.FURNACE, 1);
        BlockPos pos = com.steve.ai.action.BlockPlacementHelper.findPlacementPos(steve, 4);
        if (pos == null) pos = steve.blockPosition().north();
        steve.level().setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
        SteveMod.LOGGER.info("Steve '{}' placed furnace at {}", steve.getSteveName(), pos);
        return pos;
    }

    /** Normalize smelt input names */
    private String normalizeSmeltInput(String name) {
        if (name == null) return "";
        name = name.toLowerCase().trim().replace(" ", "_");
        Map<String, String> aliases = new java.util.HashMap<>();
        aliases.put("iron", "raw_iron"); aliases.put("iron_ore", "raw_iron");
        aliases.put("raw_iron", "raw_iron"); aliases.put("sat", "raw_iron");
        aliases.put("gold", "raw_gold"); aliases.put("gold_ore", "raw_gold");
        aliases.put("raw_gold", "raw_gold"); aliases.put("vang", "raw_gold");
        aliases.put("copper", "raw_copper"); aliases.put("copper_ore", "raw_copper");
        aliases.put("raw_copper", "raw_copper");
        aliases.put("coal_ore", "coal_ore");
        aliases.put("sand", "sand"); aliases.put("cat", "sand");
        aliases.put("chicken", "chicken"); aliases.put("ga", "chicken");
        aliases.put("beef", "beef"); aliases.put("bo", "beef");
        aliases.put("porkchop", "porkchop"); aliases.put("lon", "porkchop");
        aliases.put("salmon", "salmon"); aliases.put("cod", "cod");
        aliases.put("potato", "potato"); aliases.put("khoai", "potato");
        String resolved = aliases.getOrDefault(name, name);
        return resolved.replaceAll("[^a-z0-9/_.-]", "");
    }
}
