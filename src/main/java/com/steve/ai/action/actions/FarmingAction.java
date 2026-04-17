package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * FarmingAction: handles both planting and harvesting crops.
 * Mode "harvest" → collect all mature crops and replant.
 * Mode "plant"   → till soil and plant seeds.
 */
@SuppressWarnings("null") // Minecraft API (getBlockState, blockPosition, getBoundingBox) guaranteed non-null at runtime
public class FarmingAction extends BaseAction {
    private String mode;        // "harvest" or "plant"
    private String cropType;    // "wheat", "carrot", "potato", "beetroot"
    private int radius;
    private int ticksRunning;
    private List<BlockPos> targets;
    private List<net.minecraft.world.entity.animal.Animal> animalTargets;
    private int targetIndex;
    private static final int MAX_TICKS = 6000;

    public FarmingAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        mode     = task.getStringParameter("mode");     // "harvest" or "plant"
        cropType = task.getStringParameter("crop");     // "wheat", "carrot", etc.
        radius   = task.getIntParameter("radius", 16);
        ticksRunning = 0;
        targetIndex  = 0;

        if (mode == null) mode = "harvest";
        if (cropType == null) cropType = "wheat";

        if (mode.equals("breed")) {
            animalTargets = findAnimals();
            if (animalTargets.isEmpty()) {
                result = ActionResult.success("No animals to breed nearby");
                return;
            }
        } else {
            targets = findTargets();
            if (targets.isEmpty()) {
                result = ActionResult.success("No " + mode + " targets found nearby");
                return;
            }
        }

        steve.getNavigation().stop();
        steve.sendChatMessage("Bắt đầu " + mode + " " + (mode.equals("breed") ? "gia súc" : cropType) + "!");
        SteveMod.LOGGER.info("Steve '{}' farming: {} mode, {} targets, crop={}",
                steve.getSteveName(), mode, targets.size(), cropType);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            result = ActionResult.success("Farming complete (" + targetIndex + " blocks processed)");
            return;
        }

        if (targetIndex >= targets.size()) {
            steve.setFlying(false);
            steve.sendChatMessage("Xong! " + (mode.equals("harvest") ? "Thu hoạch" : "Trồng") + " được " + targetIndex + " ô " + cropType + ".");
            result = ActionResult.success("Farming complete (" + targetIndex + " blocks processed)");
            return;
        }

        // Process one per tick interval
        if (ticksRunning % 20 != 0) return; // Chậm lại cho navigation (1s/lần)

        if (mode.equals("breed")) {
            processBreeding();
        } else {
            processFarming();
        }
    }

    private void processFarming() {
        if (targetIndex >= targets.size()) return;
        
        BlockPos pos = targets.get(targetIndex);
        double dist = steve.blockPosition().distSqr(pos);

        if (dist > 9) {
            steve.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.2);
        } else {
            targetIndex++;
            steve.swing(InteractionHand.MAIN_HAND, true);
            BlockState state = steve.level().getBlockState(pos);
            if (mode.equals("harvest")) {
                harvestBlock(pos, state);
            } else {
                plantBlock(pos);
            }
        }
    }

    private void processBreeding() {
        if (targetIndex >= animalTargets.size()) return;

        var animal = animalTargets.get(targetIndex);
        if (!animal.isAlive() || animal.isInLove()) {
            targetIndex++;
            return;
        }

        double dist = steve.distanceToSqr(animal);
        if (dist > 9) {
            steve.getNavigation().moveTo(animal, 1.2);
        } else {
            targetIndex++;
            var inv = steve.getMemory().getInventory();
            net.minecraft.world.item.Item food = getFoodForAnimal(animal);
            if (inv.hasItem(food, 1)) {
                animal.setInLove(null);
                inv.removeItem(food, 1);
                steve.swing(InteractionHand.MAIN_HAND, true);
                steve.level().addParticle(net.minecraft.core.particles.ParticleTypes.HEART, 
                    animal.getX(), animal.getY() + 1.0, animal.getZ(), 0, 0, 0);
            }
        }
    }

    private List<net.minecraft.world.entity.animal.Animal> findAnimals() {
        return steve.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, 
            steve.getBoundingBox().inflate(radius), 
            a -> !a.isInLove() && a.getAge() == 0 && hasFoodFor(a));
    }

    private boolean hasFoodFor(net.minecraft.world.entity.animal.Animal a) {
        return steve.getMemory().getInventory().hasItem(getFoodForAnimal(a), 1);
    }

    private net.minecraft.world.item.Item getFoodForAnimal(net.minecraft.world.entity.animal.Animal a) {
        if (a instanceof net.minecraft.world.entity.animal.Cow || a instanceof net.minecraft.world.entity.animal.Sheep) 
            return Items.WHEAT;
        if (a instanceof net.minecraft.world.entity.animal.Pig) 
            return Items.CARROT;
        if (a instanceof net.minecraft.world.entity.animal.Chicken) 
            return Items.WHEAT_SEEDS;
        return Items.AIR;
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false);
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Farming " + mode + " " + cropType + " (r=" + radius + ")";
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private List<BlockPos> findTargets() {
        List<BlockPos> result = new ArrayList<>();
        BlockPos center = steve.blockPosition();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = steve.level().getBlockState(pos);
                    Block block = state.getBlock();

                    if (mode.equals("harvest")) {
                        // Harvest: find mature crops
                        if (block instanceof CropBlock crop && crop.isMaxAge(state)) {
                            result.add(pos);
                        }
                    } else {
                        // Plant: find tilled farmland with air above
                        if (block instanceof FarmBlock
                                && steve.level().getBlockState(pos.above()).isAir()) {
                            result.add(pos.above());
                        }
                    }
                }
            }
        }
        return result;
    }

    private void harvestBlock(BlockPos pos, BlockState state) {
        // Break the crop — drops happen automatically
        steve.level().destroyBlock(pos, true);
        // Nhặt drop vào SteveInventory
        steve.pickupNearbyItems(2.5);

        // Replant nếu có hạt giống trong inventory
        Block block = state.getBlock();
        if (block instanceof CropBlock) {
            BlockPos farmland = pos.below();
            if (steve.level().getBlockState(farmland).getBlock() instanceof FarmBlock) {
                net.minecraft.world.item.Item seedItem = getSeedItemForCrop(cropType);
                com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
                if (inv.hasItem(seedItem, 1)) {
                    inv.removeItem(seedItem, 1);
                    steve.level().setBlock(pos, getCropBlock().defaultBlockState(), 3);
                    SteveMod.LOGGER.debug("Steve '{}' replanted at {}", steve.getSteveName(), pos);
                } else {
                    SteveMod.LOGGER.debug("Steve '{}' no seeds to replant at {}", steve.getSteveName(), pos);
                }
            }
        }
    }

    private void plantBlock(BlockPos pos) {
        if (!steve.level().getBlockState(pos).isAir()) return;
        BlockPos farmland = pos.below();
        if (steve.level().getBlockState(farmland).getBlock() instanceof FarmBlock) {
            net.minecraft.world.item.Item seedItem = getSeedItemForCrop(cropType);
            com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
            if (inv.hasItem(seedItem, 1)) {
                inv.removeItem(seedItem, 1);
                steve.level().setBlock(pos, getCropBlock().defaultBlockState(), 3);
            } else {
                SteveMod.LOGGER.debug("Steve '{}' no seeds for planting at {}", steve.getSteveName(), pos);
            }
        }
    }

    private Block getCropBlock() {
        return switch (cropType.toLowerCase()) {
            case "carrot"   -> Blocks.CARROTS;
            case "potato"   -> Blocks.POTATOES;
            case "beetroot" -> Blocks.BEETROOTS;
            default         -> Blocks.WHEAT;
        };
    }

    /** Trả về Item hạt giống tương ứng để kiểm tra inventory */
    private net.minecraft.world.item.Item getSeedItemForCrop(String crop) {
        return switch (crop.toLowerCase()) {
            case "carrot"   -> Items.CARROT;
            case "potato"   -> Items.POTATO;
            case "beetroot" -> Items.BEETROOT_SEEDS;
            default         -> Items.WHEAT_SEEDS;
        };
    }
}
