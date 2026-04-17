package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.BlockPlacementHelper;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

import java.util.EnumSet;
import java.util.Map;

/**
 * BrewingAction: pha chế potion từ nguyên liệu trong SteveInventory.
 * Hỗ trợ các potion cơ bản: healing, strength, speed, fire_resistance, night_vision.
 * Cần brewing stand (tìm hoặc đặt từ inventory) + blaze powder + nguyên liệu.
 */
@SuppressWarnings("null")
public class BrewingAction extends BaseAction {
    private String potionType;
    private int quantity;
    private int ticksRunning;
    private static final int MAX_TICKS = 1200; // 60 giây

    // Công thức pha chế: potionType → {ingredient, base}
    private static final Map<String, net.minecraft.world.item.Item[]> RECIPES = Map.of(
        "healing",         new net.minecraft.world.item.Item[]{Items.GLISTERING_MELON_SLICE, Items.NETHER_WART},
        "strength",        new net.minecraft.world.item.Item[]{Items.BLAZE_POWDER,           Items.NETHER_WART},
        "speed",           new net.minecraft.world.item.Item[]{Items.SUGAR,                  Items.NETHER_WART},
        "fire_resistance", new net.minecraft.world.item.Item[]{Items.MAGMA_CREAM,             Items.NETHER_WART},
        "night_vision",    new net.minecraft.world.item.Item[]{Items.GOLDEN_CARROT,           Items.NETHER_WART},
        "regeneration",    new net.minecraft.world.item.Item[]{Items.GHAST_TEAR,              Items.NETHER_WART},
        "poison",          new net.minecraft.world.item.Item[]{Items.SPIDER_EYE,              Items.NETHER_WART}
    );

    public BrewingAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        potionType   = task.getStringParameter("potion");
        quantity     = task.getIntParameter("quantity", 1);
        ticksRunning = 0;

        if (potionType == null) potionType = "healing";

        net.minecraft.world.item.Item[] recipe = RECIPES.get(potionType.toLowerCase());
        if (recipe == null) {
            result = ActionResult.failure("Khong biet cong thuc potion: " + potionType);
            return;
        }

        SteveInventory inv = steve.getMemory().getInventory();

        // Kiểm tra nguyên liệu
        net.minecraft.world.item.Item ingredient = recipe[0];
        net.minecraft.world.item.Item base       = recipe[1]; // nether wart

        if (!inv.hasItem(Items.GLASS_BOTTLE, quantity)) {
            result = ActionResult.failure("Can " + quantity + "x glass bottle!");
            steve.sendChatMessage("Thieu binh thuy tinh de pha potion!");
            return;
        }
        if (!inv.hasItem(base, 1)) {
            result = ActionResult.failure("Can nether wart de pha potion!");
            steve.sendChatMessage("Thieu nether wart!");
            return;
        }
        if (!inv.hasItem(ingredient, 1)) {
            result = ActionResult.failure("Thieu nguyen lieu: " + ingredient.getDescription().getString());
            steve.sendChatMessage("Thieu " + ingredient.getDescription().getString() + " de pha " + potionType + "!");
            return;
        }
        if (!inv.hasItem(Items.BLAZE_POWDER, 1)) {
            result = ActionResult.failure("Can blaze powder de chay brewing stand!");
            steve.sendChatMessage("Thieu blaze powder!");
            return;
        }

        // Tìm hoặc đặt brewing stand
        BlockPos standPos = findBrewingStand();
        if (standPos == null) {
            if (inv.hasItem(Items.BREWING_STAND, 1)) {
                standPos = BlockPlacementHelper.findPlacementPos(steve, 4);
                if (standPos == null) standPos = steve.blockPosition().above();
                steve.level().setBlock(standPos, Blocks.BREWING_STAND.defaultBlockState(), 3);
                inv.removeItem(Items.BREWING_STAND, 1);
                SteveMod.LOGGER.info("Steve '{}' placed brewing stand at {}", steve.getSteveName(), standPos);
            } else {
                result = ActionResult.failure("Khong co brewing stand!");
                steve.sendChatMessage("Can brewing stand de pha potion!");
                return;
            }
        }

        // Đi đến brewing stand
        steve.getNavigation().moveTo(standPos.getX(), standPos.getY(), standPos.getZ(), 1.0);

        BlockEntity be = steve.level().getBlockEntity(standPos);
        if (!(be instanceof BrewingStandBlockEntity stand)) {
            result = ActionResult.failure("Khong the truy cap brewing stand!");
            return;
        }

        // Trừ nguyên liệu
        inv.removeItem(Items.GLASS_BOTTLE, quantity);
        inv.removeItem(base, 1);
        inv.removeItem(ingredient, 1);
        inv.removeItem(Items.BLAZE_POWDER, 1);

        // Đặt vào brewing stand (slot 0-2 = bottles, slot 3 = ingredient, slot 4 = fuel)
        for (int i = 0; i < Math.min(quantity, 3); i++) {
            stand.setItem(i, new ItemStack(Items.GLASS_BOTTLE));
        }
        stand.setItem(3, new ItemStack(ingredient));
        stand.setItem(4, new ItemStack(Items.BLAZE_POWDER));

        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.sendChatMessage("Dang pha " + quantity + "x " + potionType + " potion...");

        // Simulate kết quả ngay (simplified — không chờ 400 ticks thật)
        net.minecraft.world.item.Item potionItem = getPotionItem(potionType);
        for (int i = 0; i < quantity; i++) {
            inv.addItem(new ItemStack(potionItem));
        }

        result = ActionResult.success("Pha duoc " + quantity + "x " + potionType + " potion!");
        steve.sendChatMessage("Xong! Pha duoc " + quantity + "x " + potionType + " potion!");
        SteveMod.LOGGER.info("Steve '{}' brewed {}x {}", steve.getSteveName(), quantity, potionType);
    }

    @Override protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS && result == null) {
            result = ActionResult.failure("Brewing timeout");
        }
    }

    @Override protected void onCancel() { steve.getNavigation().stop(); }

    @Override
    public String getDescription() { return "Brew " + quantity + "x " + potionType; }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    private BlockPos findBrewingStand() {
        BlockPos center = steve.blockPosition();
        for (int r = 1; r <= 12; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        if (steve.level().getBlockState(p).getBlock() == Blocks.BREWING_STAND) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    private net.minecraft.world.item.Item getPotionItem(String type) {
        return switch (type.toLowerCase()) {
            case "healing"         -> Items.POTION; // simplified — dùng generic potion
            case "strength"        -> Items.POTION;
            case "speed"           -> Items.POTION;
            case "fire_resistance" -> Items.POTION;
            case "night_vision"    -> Items.POTION;
            default                -> Items.POTION;
        };
    }
}
