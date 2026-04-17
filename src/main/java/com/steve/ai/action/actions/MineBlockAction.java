package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("null") // Minecraft/Forge API (getBlockState, blockPosition, Vec3i, Direction, ItemStack) guaranteed non-null at runtime
public class MineBlockAction extends BaseAction {
    private Block targetBlock;
    private String blockType;
    private int targetQuantity;
    private int minedCount;
    private BlockPos currentTarget;
    private int ticksRunning;
    private int ticksMovingToTarget;
    private int ticksSinceLastMine = 0;
    private static long lastPrereqMsgTick = 0;
    private static final int MAX_TICKS = 24000;
    private static final int MINING_DELAY = 5;
    private static final int MOVE_TIMEOUT = 100; // ticks to reach a target before giving up

    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
        this.blockType = task.getStringParameter("block");
        this.targetQuantity = task.getIntParameter("quantity", 8);
    }

    @Override
    protected void onStart() {
        blockType = task.getStringParameter("block");
        targetQuantity = task.getIntParameter("quantity", 8);
        minedCount = 0;
        ticksRunning = 0;
        ticksSinceLastMine = 0;
        ticksMovingToTarget = 0;

        targetBlock = parseBlock(blockType);
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockType);
            return;
        }

        // ── Prerequisite check: đủ công cụ chưa? ────────────────────────────
        com.steve.ai.action.CraftingPrerequisiteChecker.PrerequisiteResult prereq =
            com.steve.ai.action.CraftingPrerequisiteChecker.check(steve, blockType);
        if (!prereq.satisfied()) {
            SteveMod.LOGGER.warn("Steve '{}' missing prerequisite for mining {}: {}",
                steve.getSteveName(), blockType, prereq.reason());

            long currentTick = steve.level().getGameTime();
            if (currentTick - lastPrereqMsgTick > 600) { // Giảm xuống 30 giây để vẫn nhắc nhưng không spam
                steve.sendChatMessage("Cần chuẩn bị trước: " + prereq.reason());
                lastPrereqMsgTick = currentTick;
            }

            // Enqueue full tool chain: gather wood → craft table → craft pickaxe → mine lại
            var executor = steve.getActionExecutor();
            executor.enqueue(new com.steve.ai.action.Task("gather", java.util.Map.of("resource", "wood")));
            executor.enqueue(new com.steve.ai.action.Task("craft",  java.util.Map.of("item", "crafting_table", "quantity", 1)));
            prereq.requiredTasks().forEach(executor::enqueue);
            
            // Re-enqueue mine task sau khi có tool
            executor.enqueue(task);

            // Report failure but WITHOUT requiring replanning (internal queue will solve it)
            result = new com.steve.ai.action.ActionResult(false, "Đang chuẩn bị công cụ: " + prereq.reason(), false);
            return;
        }

        steve.setFlying(false);
        equipBestPickaxe();
        steve.sendChatMessage("Bắt đầu đào " + blockType + "!");
        SteveMod.LOGGER.info("Steve '{}' mining {} qty={}", steve.getSteveName(), blockType, targetQuantity);
        findNextBlock();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastMine++;

        if (ticksRunning > MAX_TICKS) {
            finish(false, "Mining timeout - found " + minedCount + "/" + targetQuantity);
            return;
        }

        if (minedCount >= targetQuantity) {
            finish(true, "Mined " + minedCount + " " + blockType);
            return;
        }

        // No target — search for one
        if (currentTarget == null) {
            findNextBlock();
            if (currentTarget == null) {
                // Không thấy tài nguyên gần — thử hướng chưa khám phá trước
                com.steve.ai.memory.ExplorationMemory expMem =
                    steve.getMemory().getExplorationMemory();
                net.minecraft.core.BlockPos unexplored =
                    expMem.getUnexploredDirection(steve.blockPosition(), 160);
                
                if (unexplored != null) {
                    if (ticksRunning % 200 == 0) {
                        steve.sendChatMessage("Không thấy " + blockType + " quanh đây, để tao đi xa hơn tìm xem...");
                    }
                    steve.getNavigation().moveTo(unexplored.getX(), unexplored.getY(), unexplored.getZ(), 1.4);
                    SteveMod.LOGGER.info("Steve '{}' searching for {} in unexplored area at {}",
                        steve.getSteveName(), blockType, unexplored);
                    return;
                }
                
                // Tránh hì hục đào đất nếu đang tìm gỗ/lúa trên bề mặt
                if (isSurfaceBlock()) {
                    if (ticksRunning % 400 == 0) {
                        steve.sendChatMessage("Chịu rồi, vùng này hình như không có " + blockType + ".");
                    }
                    return;
                }

                // Tất cả đã khám phá — xuống sâu theo độ sâu quặng
                digTowardOreDepth();
                return;
            }
        }

        // Verify target still exists
        if (steve.level().getBlockState(currentTarget).getBlock() != targetBlock) {
            currentTarget = null;
            return;
        }

        double dist = steve.blockPosition().distSqr(currentTarget);

        if (dist <= 9) { // within 3 blocks — mine it
            if (ticksSinceLastMine >= MINING_DELAY) {
                mineBlock(currentTarget);
                currentTarget = null;
                ticksSinceLastMine = 0;
                ticksMovingToTarget = 0;
            }
        } else {
            // Navigate to target
            ticksMovingToTarget++;
            if (ticksMovingToTarget == 1 || (ticksMovingToTarget % 20 == 0)) {
                steve.getNavigation().moveTo(
                    currentTarget.getX() + 0.5,
                    currentTarget.getY(),
                    currentTarget.getZ() + 0.5,
                    1.2
                );
            }
            // Timeout — target unreachable, skip it
            if (ticksMovingToTarget > MOVE_TIMEOUT) {
                SteveMod.LOGGER.info("Steve '{}' can't reach {}, skipping", steve.getSteveName(), currentTarget);
                currentTarget = null;
                ticksMovingToTarget = 0;
            }
        }
    }

    private void mineBlock(BlockPos pos) {
        // Clear path to ore (mine blocks in between if needed)
        clearPathTo(pos);
        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.level().destroyBlock(pos, true);
        steve.pickupNearbyItems(3.0);
        minedCount++;
        SteveMod.LOGGER.info("Steve '{}' mined {} at {} ({}/{})",
            steve.getSteveName(), blockType, pos, minedCount, targetQuantity);

        if (minedCount % 4 == 0) {
            steve.sendChatMessage("Đào được " + minedCount + "/" + targetQuantity + " " + blockType);
        }
    }

    private void clearPathTo(BlockPos target) {
        BlockPos stevePos = steve.blockPosition();
        // Mine blocks directly between steve and target (simple line)
        int dx = Integer.signum(target.getX() - stevePos.getX());
        int dy = Integer.signum(target.getY() - stevePos.getY());
        int dz = Integer.signum(target.getZ() - stevePos.getZ());

        BlockPos check = stevePos.offset(dx, dy, dz);
        for (int i = 0; i < 5 && !check.equals(target); i++) {
            BlockState state = steve.level().getBlockState(check);
            if (!state.isAir() && state.getBlock() != Blocks.BEDROCK && state.getBlock() != targetBlock) {
                steve.level().destroyBlock(check, true);
            }
            check = check.offset(dx, dy, dz);
        }
    }

    private void digTowardOreDepth() {
        // Dig straight down toward the ideal Y level for this ore
        int targetY = getOreDepth();
        int currentY = steve.blockPosition().getY();

        if (Math.abs(currentY - targetY) < 3) {
            // At right depth, dig forward
            BlockPos forward = steve.blockPosition().offset(1, 0, 0);
            BlockState bs = steve.level().getBlockState(forward);
            if (!bs.isAir() && bs.getBlock() != Blocks.BEDROCK) {
                steve.swing(InteractionHand.MAIN_HAND, true);
                steve.level().destroyBlock(forward, true);
                steve.pickupNearbyItems(2.0);
            }
            // Also clear above
            BlockPos above = forward.above();
            BlockState abs = steve.level().getBlockState(above);
            if (!abs.isAir() && abs.getBlock() != Blocks.BEDROCK) {
                steve.level().destroyBlock(above, true);
            }
            // Move forward
            steve.getNavigation().moveTo(forward.getX() + 0.5, forward.getY(), forward.getZ() + 0.5, 1.0);
            
            // Branch Mining Pattern: thắp đuốc mỗi 10 block
            if (minedCount % 10 == 0) {
                placeMiningTorch(steve.blockPosition());
            }

            // Create side branches every 4 blocks
            if (minedCount % 8 == 0) {
                digSideBranch(steve.blockPosition());
            }
        } else if (currentY > targetY) {
            // Dig down (Safety check: don't dig straight down if possible, but for bot it's okay with Water MLG)
            BlockPos below = steve.blockPosition().below();
            BlockState bs = steve.level().getBlockState(below);
            if (!bs.isAir() && bs.getBlock() != Blocks.BEDROCK) {
                // Lava check
                if (bs.getBlock() == Blocks.LAVA || bs.getBlock() == Blocks.WATER) {
                    steve.sendChatMessage("Gặp nham thạch! Phải né thôi.");
                    steve.getNavigation().moveTo(steve.getX() + 2, currentY, steve.getZ() + 2, 1.0);
                    return;
                }
                steve.swing(InteractionHand.MAIN_HAND, true);
                steve.level().destroyBlock(below, true);
            }
            steve.getNavigation().moveTo(steve.getX(), targetY, steve.getZ(), 1.0);
        }
    }

    private void placeMiningTorch(BlockPos pos) {
        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.TORCH, 1)) {
            steve.level().setBlock(pos, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), 3);
            inv.removeItem(net.minecraft.world.item.Items.TORCH, 1);
        }
    }

    private void digSideBranch(BlockPos pos) {
        // Dig 4 blocks to the left
        net.minecraft.core.Direction dir = steve.getDirection().getClockWise();
        for (int i = 1; i <= 4; i++) {
            BlockPos side = pos.relative(dir, i);
            steve.level().destroyBlock(side, true);
            steve.level().destroyBlock(side.above(), true);
        }
    }

    private int getOreDepth() {
        return switch (blockType.toLowerCase()) {
            case "diamond", "diamond_ore" -> -59;
            case "iron", "iron_ore" -> 16;
            case "gold", "gold_ore" -> -16;
            case "coal", "coal_ore" -> 96;
            case "copper", "copper_ore" -> 48;
            case "redstone", "redstone_ore" -> -32;
            case "lapis", "lapis_ore" -> 0;
            case "emerald", "emerald_ore" -> 64;
            case "wood", "oak_log", "log", "birch_log", "spruce_log" -> 70; // surface
            default -> 32;
        };
    }

    private void findNextBlock() {
        BlockPos center = steve.blockPosition();
        int radius = isSurfaceBlock() ? 96 : 24; // Mở rộng tầm nhìn cho tài nguyên trên mặt đất

        // Tối ưu hóa: Dùng findClosestMatch của vanilla (scan xoắn ốc từ gần ra xa, dừng ngay khi thấy)
        // Nhanh hơn gấp 10-100 lần so với loop 48x48x48 brute-force (110k blocks)
        java.util.Optional<BlockPos> result = BlockPos.findClosestMatch(center, radius, radius, pos -> 
            steve.level().getBlockState(pos).getBlock() == targetBlock
        );

        BlockPos best = result.orElse(null);

        // Đánh dấu khu vực đã scan vào ExplorationMemory
        steve.getMemory().getExplorationMemory().markExplored(center, radius);

        currentTarget = best;
        ticksMovingToTarget = 0;
        if (best != null) {
            SteveMod.LOGGER.info("Steve '{}' found {} at {}", steve.getSteveName(), blockType, best);
        }
    }

    private void finish(boolean success, String msg) {
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        if (success) {
            result = ActionResult.success(msg);
            steve.sendChatMessage("Xong! " + msg);
        } else {
            result = ActionResult.failure(msg);
        }
    }

    private void equipBestPickaxe() {
        net.minecraft.world.item.Item[] pickaxes = {
            net.minecraft.world.item.Items.NETHERITE_PICKAXE,
            net.minecraft.world.item.Items.DIAMOND_PICKAXE,
            net.minecraft.world.item.Items.IRON_PICKAXE,
            net.minecraft.world.item.Items.STONE_PICKAXE,
            net.minecraft.world.item.Items.WOODEN_PICKAXE
        };
        for (net.minecraft.world.item.Item pick : pickaxes) {
            for (net.minecraft.world.item.ItemStack stack : steve.getMemory().getInventory().getItems()) {
                if (stack.is(pick) && !stack.isEmpty()) {
                    steve.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack.copy());
                    return;
                }
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        String blockName = targetBlock != null ? targetBlock.getName().getString() : blockType;
        return "Mine " + targetQuantity + " " + blockName + " (" + minedCount + " found)";
    }

    @Override
    public java.util.EnumSet<com.steve.ai.action.ActionSlot> getRequiredSlots() {
        return java.util.EnumSet.of(
            com.steve.ai.action.ActionSlot.LOCOMOTION,
            com.steve.ai.action.ActionSlot.INTERACTION
        );
    }

    private boolean isSurfaceBlock() {
        if (blockType == null) return false;
        String b = blockType.toLowerCase();
        return b.contains("log") || b.contains("wood") || b.contains("wheat") || b.contains("grass") || b.contains("flower");
    }

    private Block parseBlock(String blockName) {
        if (blockName == null || blockName.isBlank()) return null;
        blockName = blockName.toLowerCase().trim().replace(" ", "_");

        Map<String, String> aliases = new HashMap<>();
        aliases.put("iron", "iron_ore"); aliases.put("diamond", "diamond_ore");
        aliases.put("coal", "coal_ore"); aliases.put("gold", "gold_ore");
        aliases.put("copper", "copper_ore"); aliases.put("redstone", "redstone_ore");
        aliases.put("lapis", "lapis_ore"); aliases.put("emerald", "emerald_ore");
        aliases.put("sat", "iron_ore"); aliases.put("kim_cuong", "diamond_ore");
        aliases.put("than", "coal_ore"); aliases.put("vang", "gold_ore");
        aliases.put("da", "stone"); aliases.put("dat", "dirt");
        // Wood aliases
        aliases.put("wood", "oak_log"); aliases.put("log", "oak_log");
        aliases.put("go", "oak_log"); aliases.put("oak", "oak_log");
        aliases.put("oak_logs", "oak_log"); aliases.put("cui", "oak_log");
        aliases.put("birch", "birch_log"); aliases.put("spruce", "spruce_log");
        aliases.put("jungle", "jungle_log"); aliases.put("acacia", "acacia_log");
        aliases.put("dark_oak", "dark_oak_log"); aliases.put("mangrove", "mangrove_log");
        // Surface resources aliases
        aliases.put("lua", "wheat"); aliases.put("wheat", "wheat");
        aliases.put("seed", "wheat_seeds"); aliases.put("hat_giong", "wheat_seeds");
        // Stone aliases
        aliases.put("stone", "stone"); aliases.put("cobble", "cobblestone");
        aliases.put("gravel", "gravel"); aliases.put("sand", "sand");

        if (aliases.containsKey(blockName)) blockName = aliases.get(blockName);
        if (blockName.contains(":")) blockName = blockName.split(":")[1];
        blockName = blockName.replaceAll("[^a-z0-9/_.-]", "");
        if (blockName.isBlank()) return null;

        try {
            Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse("minecraft:" + blockName));
            return (block != null && block != Blocks.AIR) ? block : null;
        } catch (Exception e) { return null; }

    }
}
