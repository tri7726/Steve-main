package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.CollaborativeBuildManager;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.StructureRegistry;
import com.steve.ai.structure.BlockPlacement;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("null")
public class BuildStructureAction extends BaseAction {
    
    private String structureType;
    private List<BlockPlacement> buildPlan;
    private List<Block> buildMaterials;
    private int ticksRunning;
    private CollaborativeBuildManager.CollaborativeBuild collaborativeBuild; // For multi-Steve collaboration
    private boolean isCollaborative;
    private static final int MAX_TICKS = 120000;
    private static final int BLOCKS_PER_TICK = 1;

    public BuildStructureAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        structureType = task.getStringParameter("structure").toLowerCase();
        ticksRunning = 0;
        collaborativeBuild = CollaborativeBuildManager.findActiveBuild(structureType);
        if (collaborativeBuild != null) {
            isCollaborative = true;
            
            steve.setFlying(true);
            
            SteveMod.LOGGER.info("Steve '{}' JOINING collaborative build of '{}' ({}% complete) - FLYING & INVULNERABLE ENABLED", 
                steve.getSteveName(), structureType, collaborativeBuild.getProgressPercentage());
            
            buildMaterials = new ArrayList<>();
            buildMaterials.add(Blocks.OAK_PLANKS); // Default material
            buildMaterials.add(Blocks.COBBLESTONE);
            buildMaterials.add(Blocks.GLASS_PANE);
            
            return; // Skip structure generation, just join the existing build
        }
        
        isCollaborative = false;
        
        buildMaterials = new ArrayList<>();
        Object blocksParam = task.getParameter("blocks");
        if (blocksParam instanceof List) {
            List<?> blocksList = (List<?>) blocksParam;
            for (Object blockObj : blocksList) {
                Block block = parseBlock(blockObj.toString());
                if (block != Blocks.AIR) {
                    buildMaterials.add(block);
                }
            }
        }
        
        if (buildMaterials.isEmpty()) {
            String materialName = task.getStringParameter("material", "oak_planks");
            Block block = parseBlock(materialName);
            buildMaterials.add(block != Blocks.AIR ? block : Blocks.OAK_PLANKS);
        }
        
        Object dimensionsParam = task.getParameter("dimensions");
        int width = 9;  // Increased from 5
        int height = 6; // Increased from 4
        int depth = 9;  // Increased from 5
        
        if (dimensionsParam instanceof List) {
            List<?> dims = (List<?>) dimensionsParam;
            if (dims.size() >= 3) {
                width = ((Number) dims.get(0)).intValue();
                height = ((Number) dims.get(1)).intValue();
                depth = ((Number) dims.get(2)).intValue();
            }
        } else {
            width = task.getIntParameter("width", 5);
            height = task.getIntParameter("height", 4);
            depth = task.getIntParameter("depth", 5);
        }
        
        net.minecraft.world.entity.player.Player nearestPlayer = findNearestPlayer();
        BlockPos groundPos;
        
        if (nearestPlayer != null) {
            net.minecraft.world.phys.Vec3 eyePos = nearestPlayer.getEyePosition(1.0F);
            net.minecraft.world.phys.Vec3 lookVec = nearestPlayer.getLookAngle();
            
            net.minecraft.world.phys.Vec3 targetPos = eyePos.add(lookVec.scale(12));
            
            BlockPos lookTarget = new BlockPos(
                (int)Math.floor(targetPos.x),
                (int)Math.floor(targetPos.y),
                (int)Math.floor(targetPos.z)
            );
            
            groundPos = findGroundLevel(lookTarget);
            
            if (groundPos == null) {
                groundPos = findGroundLevel(nearestPlayer.blockPosition().offset(
                    (int)Math.round(lookVec.x * 10),
                    0,
                    (int)Math.round(lookVec.z * 10)
                ));
            }
            
            SteveMod.LOGGER.info("Building in player's field of view at {} (looking from {} towards {})", 
                groundPos, eyePos, targetPos);
        } else {
            BlockPos buildPos = steve.blockPosition().offset(2, 0, 2);
            groundPos = findGroundLevel(buildPos);
        }
        
        if (groundPos == null) {
            steve.sendChatMessage("❌ Không tìm được chỗ bằng phẳng để xây " + structureType + "!");
            result = ActionResult.failure("Cannot find suitable ground for building in your field of view");
            return;
        }
        
        SteveMod.LOGGER.info("Found ground at Y={} (Build starting at {})", groundPos.getY(), groundPos);
        
        BlockPos clearPos = groundPos;
        
        buildPlan = tryLoadFromTemplate(structureType, clearPos);
        
        if (buildPlan == null) {
            // Fall back to procedural generation
            buildPlan = generateBuildPlan(structureType, clearPos, width, height, depth);
        } else {
            SteveMod.LOGGER.info("Loaded '{}' from NBT template with {} blocks", structureType, buildPlan.size());
        }
        
        if (buildPlan == null || buildPlan.isEmpty()) {
            steve.sendChatMessage("❌ Không biết cách xây '" + structureType + "'. Thử: house, shelter, tower, barn.");
            result = ActionResult.failure("Cannot generate build plan for: " + structureType);
            return;
        }

        // ── Prerequisite check: có đủ tool để xây không? ─────────────────────
        // Xây cần stone → cần pickaxe. Tự resolve nếu thiếu.
        var inv = steve.getMemory().getInventory();
        boolean hasPick = inv.hasItem(net.minecraft.world.item.Items.WOODEN_PICKAXE, 1)
                       || inv.hasItem(net.minecraft.world.item.Items.STONE_PICKAXE, 1)
                       || inv.hasItem(net.minecraft.world.item.Items.IRON_PICKAXE, 1)
                       || inv.hasItem(net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1);
        if (!hasPick) {
            steve.sendChatMessage("🪓 Cần cuốc để xây! Để tao đi kiếm gỗ và craft trước...");
            var executor = steve.getActionExecutor();
            // Full chain: gather wood → craft table → craft pickaxe → build lại
            java.util.List<Task> prereqs = com.steve.ai.action.ResourceDependencyResolver.resolve(
                steve, net.minecraft.world.item.Items.WOODEN_PICKAXE, 1);
            prereqs.forEach(executor::enqueue);
            executor.enqueue(task); // Re-enqueue build task sau khi có tool
            result = ActionResult.failure("Missing pickaxe, queued prerequisite chain");
            return;
        }
        
        StructureRegistry.register(clearPos, width, height, depth, structureType);
        
        collaborativeBuild = CollaborativeBuildManager.findActiveBuild(structureType);
        
        if (collaborativeBuild != null) {
            isCollaborative = true;
            SteveMod.LOGGER.info("Steve '{}' JOINING existing {} collaborative build at {}", 
                steve.getSteveName(), structureType, collaborativeBuild.startPos);
        } else {
            List<BlockPlacement> collaborativeBlocks = new ArrayList<>();
            for (BlockPlacement bp : buildPlan) {
                collaborativeBlocks.add(new BlockPlacement(bp.pos, bp.block));
            }
            
            collaborativeBuild = CollaborativeBuildManager.registerBuild(structureType, collaborativeBlocks, clearPos);
            isCollaborative = true;
            SteveMod.LOGGER.info("Steve '{}' CREATED new {} collaborative build at {}", 
                steve.getSteveName(), structureType, clearPos);
        }
        
        steve.setFlying(true);
        
        SteveMod.LOGGER.info("Steve '{}' starting COLLABORATIVE build of {} at {} with {} blocks using materials: {} [FLYING ENABLED]", 
            steve.getSteveName(), structureType, clearPos, buildPlan.size(), buildMaterials);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false); // Disable flying on timeout
            result = ActionResult.failure("Building timeout");
            return;
        }
        
        if (isCollaborative && collaborativeBuild != null) {
            if (collaborativeBuild.isComplete()) {
                CollaborativeBuildManager.completeBuild(collaborativeBuild.structureId);
                steve.setFlying(false);

                // Auto-save "home" waypoint khi xây xong nhà/trại
                if (structureType.equals("house") || structureType.equals("barn")
                        || structureType.equals("oldhouse") || structureType.equals("modern")) {
                    BlockPos buildPos = collaborativeBuild.startPos;
                    if (buildPos != null) {
                        String dim = steve.level().dimension().location().getPath();
                        steve.getMemory().getWaypoints().save("home", buildPos, dim, "unknown");
                        steve.sendChatMessage("Nhà xây xong! Tao đã đặt waypoint 'home' ở đây!");
                        SteveMod.LOGGER.info("Steve '{}' set home waypoint at {} after building {}",
                            steve.getSteveName(), buildPos, structureType);
                    }
                }

                result = ActionResult.success("Built " + structureType + " collaboratively!");
                return;
            }
            
            for (int i = 0; i < BLOCKS_PER_TICK; i++) {
                BlockPlacement placement = 
                    CollaborativeBuildManager.getNextBlock(collaborativeBuild, steve.getSteveName());
                
                if (placement == null) {
                    if (ticksRunning % 20 == 0) {
                        SteveMod.LOGGER.info("Steve '{}' has no more blocks! Build {}% complete", 
                            steve.getSteveName(), collaborativeBuild.getProgressPercentage());
                    }
                    break;
                }
                
                BlockPos pos = placement.pos;
                double distance = Math.sqrt(steve.blockPosition().distSqr(pos));
                if (distance > 5) {
                    steve.teleportTo(pos.getX() + 2, pos.getY(), pos.getZ() + 2);
                    SteveMod.LOGGER.info("Steve '{}' teleported to block at {}", steve.getSteveName(), pos);
                }
                
                steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                
                steve.swing(InteractionHand.MAIN_HAND, true);
                
                BlockState blockState = placement.block.defaultBlockState();
                steve.level().setBlock(pos, blockState, 3);
                
                SteveMod.LOGGER.info("Steve '{}' PLACED BLOCK at {} - Total: {}/{}", 
                    steve.getSteveName(), pos, collaborativeBuild.getBlocksPlaced(), 
                    collaborativeBuild.getTotalBlocks());
                
                // Particles and sound
                if (steve.level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(
                        new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        15, 0.4, 0.4, 0.4, 0.15
                    );
                    
                    var soundType = blockState.getSoundType(steve.level(), pos, steve);
                    steve.level().playSound(null, pos, soundType.getPlaceSound(), 
                        SoundSource.BLOCKS, 1.0f, soundType.getPitch());
                }
            }
            
            if (ticksRunning % 100 == 0 && collaborativeBuild.getBlocksPlaced() > 0) {
                int percentComplete = collaborativeBuild.getProgressPercentage();
                SteveMod.LOGGER.info("{} build progress: {}/{} ({}%) - {} Steves working", 
                    structureType, 
                    collaborativeBuild.getBlocksPlaced(), 
                    collaborativeBuild.getTotalBlocks(), 
                    percentComplete,
                    collaborativeBuild.participatingSteves.size());
            }
        } else {
            steve.setFlying(false);
            steve.sendChatMessage("❌ Lỗi hệ thống xây dựng, thử lại sau.");
            result = ActionResult.failure("Build system error: not in collaborative mode");
        }
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false); // Disable flying when cancelled
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Build " + structureType + " (" + (collaborativeBuild != null ? collaborativeBuild.getBlocksPlaced() : 0) + "/" + (buildPlan != null ? buildPlan.size() : 0) + ")";
    }

    private List<BlockPlacement> generateBuildPlan(String type, BlockPos pos, int w, int h, int d) {
        if (type.contains("shelter") || type.contains("camp")) {
            return generateShelterPlan(pos);
        } else {
            // Default to box/house for other types
            return generateBoxPlan(pos, w, h, d);
        }
    }

    private List<BlockPlacement> generateBoxPlan(BlockPos pos, int w, int h, int d) {
        List<BlockPlacement> plan = new ArrayList<>();
        Block material = buildMaterials.get(0);
        
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                for (int y = 0; y < h; y++) {
                    if (x == 0 || x == w - 1 || z == 0 || z == d - 1 || y == h - 1) {
                        plan.add(new BlockPlacement(pos.offset(x, y, z), material));
                    }
                }
            }
        }
        return plan;
    }

    private List<BlockPlacement> generateShelterPlan(BlockPos pos) {
        List<BlockPlacement> plan = new ArrayList<>();
        Block mat = buildMaterials.get(0);
        
        // 3x3x3 Shelter
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                for (int y = 0; y < 3; y++) {
                    // Floor (y=0), Roof (y=2), Walls (x=0,2 or z=0,2)
                    if (y == 0 || y == 2 || x == 0 || x == 2 || z == 0 || z == 2) {
                        // Leave a gap for the door at (1, 1, 0)
                        if (x == 1 && y == 1 && z == 0) continue;
                        
                        plan.add(new BlockPlacement(pos.offset(x, y, z), mat));
                    }
                }
            }
        }
        // Add a torch inside
        plan.add(new BlockPlacement(pos.offset(1, 1, 1), Blocks.TORCH));
        return plan;
    }

    private Block parseBlock(String blockName) {
        if (blockName == null || blockName.isBlank()) return Blocks.OAK_PLANKS;
        blockName = blockName.toLowerCase().trim().replace(" ", "_");

        // Map tiếng Việt và alias phổ biến sang minecraft block id
        java.util.Map<String, String> aliases = new java.util.HashMap<>();
        aliases.put("đá", "cobblestone"); aliases.put("da", "cobblestone");
        aliases.put("gỗ", "oak_planks"); aliases.put("go", "oak_planks");
        aliases.put("gach", "stone_bricks"); aliases.put("gạch", "stone_bricks");
        aliases.put("cat", "sand"); aliases.put("cát", "sand");
        aliases.put("dat", "dirt"); aliases.put("đất", "dirt");
        aliases.put("cobblestone", "cobblestone");
        aliases.put("oak_planks", "oak_planks");
        aliases.put("stone_bricks", "stone_bricks");
        aliases.put("stone", "stone");
        aliases.put("glass", "glass");
        aliases.put("glass_pane", "glass_pane");
        if (aliases.containsKey(blockName)) blockName = aliases.get(blockName);

        // Strip namespace nếu có
        if (blockName.contains(":")) blockName = blockName.split(":")[1];

        // Chỉ giữ ký tự hợp lệ cho ResourceLocation
        blockName = blockName.replaceAll("[^a-z0-9/_.-]", "");
        if (blockName.isBlank()) return Blocks.OAK_PLANKS;

        try {
            ResourceLocation resourceLocation = ResourceLocation.parse("minecraft:" + blockName);
            Block block = ForgeRegistries.BLOCKS.getValue(resourceLocation);
            return (block != null && block != Blocks.AIR) ? block : Blocks.OAK_PLANKS;
        } catch (Exception e) {
            return Blocks.OAK_PLANKS;
        }
    }
    
    /**
     * Find the actual ground level from a starting position
     * Scans downward to find solid ground, or upward if underground
     */
    private BlockPos findGroundLevel(BlockPos startPos) {
        int maxScanDown = 20; // Scan up to 20 blocks down
        int maxScanUp = 10;   // Scan up to 10 blocks up if we're underground
        
        // First, try scanning downward to find ground
        for (int i = 0; i < maxScanDown; i++) {
            BlockPos checkPos = startPos.below(i);
            BlockPos belowPos = checkPos.below();
            
            if (steve.level().getBlockState(checkPos).isAir() && 
                isSolidGround(belowPos)) {
                return checkPos; // This is ground level
            }
        }
        
        // Scan upward to find the surface
        for (int i = 1; i < maxScanUp; i++) {
            BlockPos checkPos = startPos.above(i);
            BlockPos belowPos = checkPos.below();
            
            if (steve.level().getBlockState(checkPos).isAir() && 
                isSolidGround(belowPos)) {
                return checkPos;
            }
        }
        
        // but make sure there's something solid below
        BlockPos fallbackPos = startPos;
        while (!isSolidGround(fallbackPos.below()) && fallbackPos.getY() > -64) {
            fallbackPos = fallbackPos.below();
        }
        
        return fallbackPos;
    }
    
    /**
     * Check if a position has solid ground suitable for building
     */
    private boolean isSolidGround(BlockPos pos) {
        var blockState = steve.level().getBlockState(pos);
        var block = blockState.getBlock();
        
        // Not solid if it's air or liquid
        if (blockState.isAir() || block == Blocks.WATER || block == Blocks.LAVA) {
            return false;
        }
        
        return blockState.isSolidRender(steve.level(), pos);
    }
    
    /**
     * Find a suitable building site with flat, clear ground
     * Searches for an area that is:
     * - Relatively flat (max 2 block height difference)
     * - Clear of obstructions (trees, rocks, etc.)
     * - Has enough vertical space for the structure
     */
    
    
    /**
     * Try to load structure from NBT template file
     * Returns null if no template found (falls back to procedural generation)
     */
    private List<BlockPlacement> tryLoadFromTemplate(String structureName, BlockPos startPos) {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        
        var template = StructureTemplateLoader.loadFromNBT(serverLevel, structureName);
        if (template == null) {
            return null;
        }
        
        List<BlockPlacement> blocks = new ArrayList<>();
        for (var templateBlock : template.blocks) {
            BlockPos worldPos = startPos.offset(templateBlock.relativePos);
            Block block = templateBlock.blockState.getBlock();
            blocks.add(new BlockPlacement(worldPos, block));
        }
        
        return blocks;
    }
    
    /**
     * Find the nearest player to build in front of
     */
    private net.minecraft.world.entity.player.Player findNearestPlayer() {
        java.util.List<? extends net.minecraft.world.entity.player.Player> players = steve.level().players();
        
        if (players.isEmpty()) {
            return null;
        }
        
        net.minecraft.world.entity.player.Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (net.minecraft.world.entity.player.Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            
            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        
        return nearest;
    }
    
}

