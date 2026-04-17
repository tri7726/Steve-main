package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import com.steve.ai.memory.WaypointMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

@SuppressWarnings("null")
public class StripMineAction extends BaseAction {
    private enum Phase {
        DIG_DOWN, MAIN_TUNNEL, EXTRACT_ORE, ESCAPE_HAZARD, RETURN_HOME
    }

    private Phase currentPhase = Phase.DIG_DOWN;
    private int targetY;
    private int mainTunnelLength;
    private int branchLength;
    private boolean discardJunk;
    private int torchInterval;

    private int ticksRunning = 0;
    private static final int MAX_TICKS = 72000; // ~1 giờ
    private static final int MINING_DELAY = 10;
    private int ticksSinceLastMine = 0;
    private Direction tunnelDirection;
    private int distanceMined = 0;
    private int blocksSinceTorch = 0;
    // Branches
    private int blocksSinceBranch = 0;
    private boolean doingBranch = false;
    private Direction branchDirection;
    private int branchDistanceMined = 0;
    private BlockPos branchStartPos;

    // Ore Extraction
    private Queue<BlockPos> oreQueue = new LinkedList<>();
    private Set<BlockPos> extractedOres = new HashSet<>();
    private BlockPos preOrePos;
    private Phase returnPhase;

    // Target Ores
    private static final Set<Block> TARGET_ORES = Set.of(
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE
    );

    // Filter Junk
    private static final Set<Block> JUNK_BLOCKS = Set.of(
        Blocks.DIRT, Blocks.COBBLESTONE, Blocks.ANDESITE, Blocks.GRANITE, 
        Blocks.DIORITE, Blocks.GRAVEL, Blocks.STONE, Blocks.DEEPSLATE
    );

    public StripMineAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        this.targetY = task.getIntParameter("targetY", -58);
        this.mainTunnelLength = task.getIntParameter("mainTunnelLength", 200);
        this.branchLength = task.getIntParameter("branchLength", 35);
        this.discardJunk = task.getParameters().containsKey("discardJunk") ? Boolean.parseBoolean(task.getStringParameter("discardJunk")) : true;
        this.torchInterval = task.getIntParameter("torchInterval", 10);

        this.tunnelDirection = steve.getDirection();
        if (tunnelDirection == Direction.UP || tunnelDirection == Direction.DOWN) {
            tunnelDirection = Direction.NORTH;
        }

        steve.sendChatMessage("Bắt đầu chiến dịch Smart Strip Mining! Đang đào xuống lớp Y: " + targetY);
        SteveMod.LOGGER.info("Steve '{}' starting SMART strip mine at Y: {}", steve.getSteveName(), targetY);

        // Check Pickaxe
        equipBestPickaxe();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastMine++;

        if (ticksRunning > MAX_TICKS || distanceMined >= mainTunnelLength) {
            steve.sendChatMessage("Kết thúc Strip Mine. Tunnel length: " + distanceMined);
            result = ActionResult.success("Strip mine completed");
            return;
        }

        manageInventory();

        switch (currentPhase) {
            case DIG_DOWN -> processDigDown();
            case MAIN_TUNNEL -> processMainTunnel();
            case EXTRACT_ORE -> processOreExtraction();
            case ESCAPE_HAZARD -> handleHazards();
            case RETURN_HOME -> processReturnHome();
        }
    }

    private void processDigDown() {
        BlockPos pos = steve.blockPosition();
        if (pos.getY() <= targetY) {
            currentPhase = Phase.MAIN_TUNNEL;
            steve.sendChatMessage("Đã tới tầng Y: " + targetY + ". Bắt đầu đào Branch Mine!");
            return;
        }

        if (ticksSinceLastMine >= MINING_DELAY) {
            BlockPos target = pos.below().relative(tunnelDirection);
            BlockPos targetAbove = target.above();
            BlockPos targetBelow = target.below();

            // Hazard Check
            if (isHazard(target) || isHazard(targetAbove) || isHazard(targetBelow)) {
                steve.sendChatMessage("Phát hiện dung nham/nước khi đào xuống! Chuyển hướng...");
                tunnelDirection = tunnelDirection.getClockWise();
                return;
            }

            // Mine next step
            if (!steve.level().getBlockState(targetAbove).isAir()) {
                mineBlock(targetAbove);
            } else if (!steve.level().getBlockState(target).isAir()) {
                mineBlock(target);
            } else {
                steve.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.2);
                blocksSinceTorch++;
            }

            if (blocksSinceTorch >= 4) {
                placeTorch(pos);
                blocksSinceTorch = 0;
            }
        }
    }

    private void processMainTunnel() {
        if (ticksSinceLastMine < MINING_DELAY) return;

        BlockPos pos = steve.blockPosition();
        
        // Scan for ores nearby
        if (scanForOres()) {
            returnPhase = Phase.MAIN_TUNNEL;
            currentPhase = Phase.EXTRACT_ORE;
            preOrePos = pos;
            return;
        }

        Direction currentDir = doingBranch ? branchDirection : tunnelDirection;
        BlockPos nextBottom = pos.relative(currentDir);
        BlockPos nextTop = nextBottom.above();

        // Hazard & Floor Stability Control
        boolean floorUnstable = isGravityBlock(steve.level().getBlockState(nextBottom.below()).getBlock());
        if (isHazard(nextBottom) || isHazard(nextTop) || isHazard(nextBottom.below()) || floorUnstable) {
            if (floorUnstable) {
                SteveMod.LOGGER.info("StripMineAction: Unstable floor (gravity block) detected, stabilizing...");
                placeBlock(nextBottom.below(), Items.COBBLESTONE);
            } else {
                steve.sendChatMessage("Nguy hiểm (Nước/Lava) phía trước! Tự lấp block.");
                placeBlock(nextBottom, Items.COBBLESTONE);
                placeBlock(nextTop, Items.COBBLESTONE);
                if (doingBranch) {
                    doingBranch = false;
                    steve.getNavigation().moveTo(branchStartPos.getX() + 0.5, branchStartPos.getY(), branchStartPos.getZ() + 0.5, 1.2);
                } else {
                    result = ActionResult.success("Main tunnel blocked by hazard.");
                }
                return;
            }
        }

        // Mining forward
        if (!steve.level().getBlockState(nextTop).isAir()) {
            mineBlock(nextTop);
        } else if (!steve.level().getBlockState(nextBottom).isAir()) {
            mineBlock(nextBottom);
        } else {
            steve.getNavigation().moveTo(nextBottom.getX() + 0.5, nextBottom.getY(), nextBottom.getZ() + 0.5, 1.2);
            
            if (doingBranch) {
                branchDistanceMined++;
                if (branchDistanceMined >= branchLength) {
                    doingBranch = false;
                    steve.getNavigation().moveTo(branchStartPos.getX() + 0.5, branchStartPos.getY(), branchStartPos.getZ() + 0.5, 1.2);
                }
            } else {
                distanceMined++;
                blocksSinceTorch++;
                blocksSinceBranch++;

                // Torch placement
                if (blocksSinceTorch >= torchInterval) {
                    placeTorch(pos);
                    blocksSinceTorch = 0;
                }

                // Branch every 20 blocks
                if (blocksSinceBranch >= 20) {
                    doingBranch = true;
                    branchDirection = tunnelDirection.getClockWise();
                    branchDistanceMined = 0;
                    branchStartPos = pos;
                    blocksSinceBranch = 0;
                }
            }
        }
    }

    private void processOreExtraction() {
        if (oreQueue.isEmpty()) {
            currentPhase = returnPhase;
            steve.getNavigation().moveTo(preOrePos.getX() + 0.5, preOrePos.getY(), preOrePos.getZ() + 0.5, 1.2);
            steve.sendChatMessage("Đã xử lý xong ổ quặng. Trở lại vị trí cũ.");
            return;
        }

        if (ticksSinceLastMine >= MINING_DELAY) {
            BlockPos target = oreQueue.peek();
            double dist = steve.blockPosition().distSqr(target);
            
            // Check hazard before mining ore
            if (isHazard(target.above()) || isHazard(target.relative(Direction.NORTH))) {
                 oreQueue.poll(); // skip risky ore
                 return;
            }

            if (dist <= 9) {
                BlockState state = steve.level().getBlockState(target);
                if (TARGET_ORES.contains(state.getBlock())) {
                    mineBlock(target);
                    findAdjacentOres(target);
                }
                oreQueue.poll();
            } else {
                steve.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.2);
            }
        }
    }

    private boolean scanForOres() {
        BlockPos center = steve.blockPosition();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (extractedOres.contains(p)) continue;
                    Block stateBlock = steve.level().getBlockState(p).getBlock();
                    if (TARGET_ORES.contains(stateBlock)) {
                        oreQueue.add(p);
                        extractedOres.add(p);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void findAdjacentOres(BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos adj = p.relative(d);
            if (extractedOres.contains(adj)) continue;
            if (TARGET_ORES.contains(steve.level().getBlockState(adj).getBlock())) {
                oreQueue.add(adj);
                extractedOres.add(adj);
            }
        }
    }

    private void handleHazards() {
        // Scan for nearby fluid (lava/water) that could flow into the tunnel
        BlockPos current = steve.blockPosition();
        int range = 2;
        
        for (int x = -range; x <= range; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos p = current.offset(x, y, z);
                    net.minecraft.world.level.material.FluidState fluid = steve.level().getFluidState(p);
                    
                    if (!fluid.isEmpty()) {
                        // Found fluid! Check if we have blocks to seal it
                        SteveInventory inv = steve.getMemory().getInventory();
                        net.minecraft.world.item.ItemStack cobblestone = inv.findFirstItem(net.minecraft.world.item.Items.COBBLESTONE);
                        if (cobblestone.isEmpty()) cobblestone = inv.findFirstItem(net.minecraft.world.item.Items.DIRT);
                        
                        if (!cobblestone.isEmpty()) {
                            // Place block to seal fluid source or flow
                            steve.level().setBlock(p, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
                            inv.removeItem(cobblestone.getItem(), 1);
                            SteveMod.LOGGER.info("StripMineAction: Sealed hazard at {}", p);
                        }
                    }
                }
            }
        }
    }

    private void processReturnHome() {
        steve.sendChatMessage("Nhiệm vụ hoàn thành hoặc túi đồ đầy, về nhà thôi!");
        WaypointMemory waypoints = steve.getMemory().getWaypoints();
        if (waypoints.has("home")) {
            steve.getActionExecutor().enqueue(new Task("waypoint", Map.of("action", "goto", "label", "home")));
        } else {
            steve.sendChatMessage("Không tìm thấy đường về nhà, đứng đây chờ vậy.");
        }
    }

    private void mineBlock(BlockPos pos) {
        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.level().destroyBlock(pos, true);
        ticksSinceLastMine = 0;
    }

    private boolean isHazard(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return true;
        
        // Scan surrounding blocks for fluid that could flow in if this block is mined
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue; // Flowing from below is rare/impossible for mining
            if (!steve.level().getFluidState(pos.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    private boolean isGravityBlock(Block block) {
        return block == Blocks.GRAVEL || block == Blocks.SAND || block == Blocks.RED_SAND;
    }

    private void placeTorch(BlockPos pos) {
        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(Items.TORCH, 1)) {
            steve.swing(InteractionHand.MAIN_HAND, true);
            steve.level().setBlock(pos, Blocks.TORCH.defaultBlockState(), 3);
            inv.removeItem(Items.TORCH, 1);
        }
    }

    private void placeBlock(BlockPos pos, net.minecraft.world.item.Item item) {
        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(item, 1)) {
            Block b = Block.byItem(item);
            steve.level().setBlock(pos, b.defaultBlockState(), 3);
            inv.removeItem(item, 1);
        }
    }

    private void manageInventory() {
        if (!discardJunk) return;
        var inv = steve.getMemory().getInventory();
        int emptySlots = 54 - inv.size();
        
        if (emptySlots <= 2) { // Extremely full
            boolean discarded = false;
            for (ItemStack stack : inv.getItems()) {
                if (!stack.isEmpty() && JUNK_BLOCKS.contains(Block.byItem(stack.getItem()))) {
                    inv.removeItem(stack.getItem(), stack.getCount());
                    discarded = true;
                }
            }
            if (!discarded && emptySlots == 0) {
                steve.sendChatMessage("Túi đồ đã đầy và không còn rác để vứt. Đang quay về!");
                currentPhase = Phase.RETURN_HOME;
            }
        }
        
        // Check tool durability
        ItemStack mainHand = steve.getItemInHand(InteractionHand.MAIN_HAND);
        if (mainHand.isEmpty() || (mainHand.isDamageableItem() && mainHand.getDamageValue() >= mainHand.getMaxDamage() - 5)) {
            steve.sendChatMessage("Cúp sắp hỏng rồi, phải về sửa thôi!");
            currentPhase = Phase.RETURN_HOME;
        }
    }

    private void equipBestPickaxe() {
        var inv = steve.getMemory().getInventory();
        ItemStack pick = inv.findFirstItem(Items.DIAMOND_PICKAXE);
        if (pick.isEmpty()) pick = inv.findFirstItem(Items.IRON_PICKAXE);
        if (pick.isEmpty()) pick = inv.findFirstItem(Items.STONE_PICKAXE);
        if (!pick.isEmpty()) {
            steve.setItemInHand(InteractionHand.MAIN_HAND, pick);
        } else {
            steve.sendChatMessage("Cảnh báo: Không có cúp đá trở lên!");
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Smart Strip Mining at Y=" + targetY + " (Mined: " + distanceMined + "/" + mainTunnelLength + ")";
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }
}
