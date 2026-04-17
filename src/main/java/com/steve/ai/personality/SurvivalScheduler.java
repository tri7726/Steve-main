package com.steve.ai.personality;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveEquipmentTracker;
import com.steve.ai.memory.SteveInventory;
import com.steve.ai.memory.WaypointMemory;
import com.steve.ai.grpc.AiCommand;
import com.steve.ai.grpc.GrpcAiClient;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import java.util.Map;
import java.util.Optional;

/**
 * SurvivalScheduler: tập trung toàn bộ logic survival theo priority rõ ràng.
 *
 * <p><b>Priority order (cao → thấp):</b>
 * <ol>
 *   <li>CRITICAL: đang trong lava/fire → escape (handled bởi autoEscapeHazard)</li>
 *   <li>HIGH: HP thấp + có mob → flee/defend (handled bởi autoDefend)</li>
 *   <li>MEDIUM-HIGH: Hunger rất thấp (≤ 4) → INTERRUPT task + tìm đồ ăn</li>
 *   <li>MEDIUM: Tối + không có task + có home → về nhà</li>
 *   <li>LOW: Tool sắp gãy → thông báo</li>
 * </ol>
 *
 * <p>Thay thế cho việc rải check logic khắp nơi trong SteveEntity.tick().</p>
 */
@SuppressWarnings({"null", "deprecation"})
public class SurvivalScheduler {

    // Cooldown để tránh spam chat
    private int goHomeCooldown     = 0;  // cooldown giữa các lần "về nhà"
    private int toolWarnCooldown   = 0;  // cooldown cảnh báo tool gãy
    private int hungerSearchCooldown = 0; // cooldown tìm đồ ăn khẩn cấp
    private int internalTickCounter = 0;

    private static final Gson GSON = new Gson();
    
    // Trackers for Critic feedback loop
    private String lastCommandId = "None";
    private String lastCommandType = "None";
    private boolean lastCommandSuccess = true;
    private String lastCommandError = "";
    private long lastActionStartTime = 0;
    private double lastActionDuration = 0;

    /**
     * Gọi mỗi 40 ticks từ SteveEntity.tick() (thay thế các autoXxx() riêng lẻ trước đó).
     *
     * @param steve          Entity đang chạy
     * @param executor       ActionExecutor để enqueue task
     */
    public void tick(SteveEntity steve, ActionExecutor executor) {
        internalTickCounter++;

        // Đọc autonomy level từ config
        com.steve.ai.config.AutonomyLevel autonomy = com.steve.ai.config.AutonomyLevel.fromString(
            com.steve.ai.config.SteveConfig.AUTONOMY_LEVEL.get());

        // Process gRPC networking and observation updates every 40 ticks
        if (internalTickCounter % 40 == 0) {
            com.steve.ai.grpc.GrpcAiClient grpcClient = com.steve.ai.grpc.GrpcAiClient.getInstance();
            if (!grpcClient.isConnected()) {
                grpcClient.start();
            }
            
            // 1. Build Inventory JSON
            StringBuilder invJson = new StringBuilder("{");
            com.steve.ai.memory.SteveInventory inventory = steve.getMemory().getInventory();
            if (inventory != null) {
                java.util.Map<String, Integer> counts = new java.util.HashMap<>();
                for (net.minecraft.world.item.ItemStack stack : inventory.getItems()) {
                    if (stack.isEmpty()) continue;
                    String name = stack.getItem().getDescriptionId().replace("item.minecraft.", "").replace("block.minecraft.", "");
                    counts.put(name, counts.getOrDefault(name, 0) + stack.getCount());
                }
                int i = 0;
                for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
                    invJson.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
                    if (i++ < counts.size() - 1) invJson.append(",");
                }
            }
            invJson.append("}");

            // 2. Build Nearby Entities JSON
            StringBuilder entitiesJson = new StringBuilder("[");
            net.minecraft.world.phys.AABB box = steve.getBoundingBox().inflate(16.0);
            java.util.List<net.minecraft.world.entity.Entity> nearby = steve.level().getEntities(steve, box);
            int j = 0;
            for (net.minecraft.world.entity.Entity e : nearby) {
                if (e instanceof net.minecraft.world.entity.LivingEntity && !(e instanceof net.minecraft.world.entity.player.Player)) {
                    String eName = e.getType().getDescriptionId().replace("entity.minecraft.", "");
                    entitiesJson.append("\"").append(eName).append("\"");
                    if (j++ < nearby.size() - 1) entitiesJson.append(",");
                    if (j > 10) break; // Limit to ~10 entities to save bandwidth
                }
            }
            if (entitiesJson.length() > 1 && entitiesJson.charAt(entitiesJson.length() - 1) == ',') {
                entitiesJson.deleteCharAt(entitiesJson.length() - 1);
            }
            entitiesJson.append("]");

            // 3. Xử lý các lệnh gRPC đến từ Node.js
            processIncomingGrpcCommands(steve, executor);

            // Update duration if a command was active
            if (lastActionStartTime > 0) {
                lastActionDuration = (System.currentTimeMillis() - lastActionStartTime) / 1000.0;
            }
            
            // 4. Capture Vision (Screenshot) if in client
            byte[] screenshot = com.steve.ai.util.VisualCapture.captureScreenshot();

            // 5. Build Environment Data
            String biome = steve.level().getBiome(steve.blockPosition()).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
            String dimension = steve.level().dimension().location().toString();
            long worldTime = steve.level().getDayTime();
            boolean isRaining = steve.level().isRaining();

            grpcClient.sendBotState(
                steve.level().getGameTime(), 
                steve.getHealth(), 
                (double)steve.getSteveHunger(),
                steve.getX(), steve.getY(), steve.getZ(),
                invJson.toString(), 
                entitiesJson.toString(), 
                "{}", 
                lastCommandSuccess,
                lastCommandType,
                lastCommandError,
                lastCommandId,
                lastActionDuration,
                screenshot,
                biome,
                dimension,
                worldTime,
                isRaining
            );
        }

        // Giảm cooldown mỗi lần tick (vì tick() giờ được gọi mỗi tick)
        goHomeCooldown     = Math.max(0, goHomeCooldown - 1);
        toolWarnCooldown   = Math.max(0, toolWarnCooldown - 1);
        hungerSearchCooldown = Math.max(0, hungerSearchCooldown - 1);

        // Time Slicing: Distribute 25 heavy checks smoothly across 40 ticks
        int slice = internalTickCounter % 40;

        // REACTIVE+: Kiểm tra đói khẩn cấp
        if (slice == 1 && autonomy.isAtLeast(com.steve.ai.config.AutonomyLevel.REACTIVE)) {
            checkCriticalHunger(steve, executor);
        }

        // PROACTIVE+: Về nhà ban đêm + cảnh báo tool
        if (autonomy.isAtLeast(com.steve.ai.config.AutonomyLevel.PROACTIVE)) {
            switch (slice) {
                case 2 -> checkGoHomeAtNight(steve, executor);
                case 3 -> checkToolDurability(steve);
                case 4 -> checkAutoArmor(steve);
                case 5 -> checkInventoryManagement(steve);
                case 6 -> checkResourceNeeds(steve, executor);
                case 7 -> checkAgricultureNeeds(steve, executor);
                case 8 -> checkAnimalHusbandry(steve, executor);
                case 9 -> checkFishingOpportunity(steve, executor);
                case 10 -> checkVillageTrading(steve, executor);
                case 11 -> checkScoutingNeeds(steve, executor);
                case 12 -> checkDialoguePersonality(steve);
                case 13 -> checkBrewingNeeds(steve, executor);
                case 14 -> checkEnchantingNeeds(steve, executor);
                case 15 -> checkSmeltingNeeds(steve, executor);
                case 16 -> checkAutoPotion(steve);
                case 17 -> checkEmergencyShelter(steve, executor);
                case 18 -> checkMendingLogic(steve);
                case 19 -> checkSpawnerSecurity(steve);
                case 20 -> checkDimensionNeeds(steve, executor);
                case 21 -> checkSmithingNeeds(steve, executor);
                case 22 -> checkEndGamePrep(steve);
                case 23 -> checkWaterNeeds(steve, executor);
                case 24 -> checkAnnouncements(steve);
                case 25 -> checkStrongholdNeeds(steve);
                case 26 -> checkAncientDebrisMining(steve, executor);
                case 27 -> checkEmergencyRecall(steve);
                case 28 -> checkSupplyDepot(steve, executor);
                case 29 -> checkStatusRemediation(steve);
                case 30 -> checkToolUpgrade(steve, executor);
                case 31 -> checkSleepNeeds(steve, executor);
                case 32 -> checkMiningNeeds(steve, executor);
                case 33 -> checkStorageNeeds(steve, executor);
                case 34 -> checkIllumination(steve, executor);
            }
        }
    }

    /**
     * FastTick: Gọi mỗi 2 ticks cho các tình huống khẩn cấp (MLG, Totem).
     */
    public void fastTick(SteveEntity steve) {
        com.steve.ai.config.AutonomyLevel autonomy = com.steve.ai.config.AutonomyLevel.fromString(
            com.steve.ai.config.SteveConfig.AUTONOMY_LEVEL.get());
        
        if (autonomy.isAtLeast(com.steve.ai.config.AutonomyLevel.REACTIVE)) {
            checkAutoTotem(steve);
            checkWaterMLG(steve);
            checkAutoShield(steve);
            checkAntiLava(steve);
            checkEmergencyHealing(steve);
            checkInstantHealthPotion(steve);
            checkTacticalRetreat(steve);
        }
    }

    // ── Priority 6: Emergency Shelter ──────────────────────────────────────

    private boolean isInShelter = false;

    /**
     * Tự động xây trú ẩn nếu trời tối và ở xa nhà.
     */
    private void checkEmergencyShelter(SteveEntity steve, ActionExecutor executor) {
        boolean isNight = !steve.level().isDay();
        net.minecraft.core.BlockPos home = steve.getMemory().getWaypoints().get("home").orElse(null);
        double distToHome = (home != null) ? steve.blockPosition().distSqr(home) : Double.MAX_VALUE;

        if (isNight && distToHome > 10000) { // 100*100 = 10000
            if (!isInShelter && steve.getNavigation().isDone()) {
                if (distToHome > 250000) { // 500*500 = 250000 -> Far from home, build Outpost
                    executor.enqueue(new com.steve.ai.action.Task("build", java.util.Map.of("structure", "outpost")));
                } else {
                    buildCompactShelter(steve);
                }
                isInShelter = true;
            }
        } else if (!isNight && isInShelter) {
            // Sáng rồi, phá shelter chui ra (hoặc chỉ cần đánh dấu là không còn trong shelter)
            isInShelter = false;
        }
    }

    private void buildCompactShelter(SteveEntity steve) {
        net.minecraft.core.BlockPos pos = steve.blockPosition();
        net.minecraft.world.level.Level level = steve.level();

        // Xây hộp 3x3x3 xung quanh Steve bằng vật liệu tốt nhất có trong inv
        var inv = steve.getMemory().getInventory();
        net.minecraft.world.item.ItemStack material = inv.findFirstItem(net.minecraft.world.item.Items.DIRT);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.COBBLESTONE);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.STONE);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.OAK_PLANKS);

        if (material.isEmpty()) return;

        steve.sendChatMessage("Trời tối và xa nhà quá, xây 'Sovereign Sanctuary' trú tạm vậy!");
        
        net.minecraft.world.level.block.state.BlockState state = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        if (material.getItem() instanceof net.minecraft.world.item.BlockItem bi) state = bi.getBlock().defaultBlockState();

        // Walls & Roof
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 2; y++) {
                    if (x == 0 && z == 0 && (y == 0 || y == 1)) continue; // Space for Steve
                    
                    net.minecraft.core.BlockPos bpos = pos.offset(x, y, z);
                    if (level.getBlockState(bpos).isAir() || level.getBlockState(bpos).canBeReplaced()) {
                        level.setBlock(bpos, state, 3);
                    }
                }
            }
        }

        // Torch
        net.minecraft.world.item.ItemStack torch = inv.findFirstItem(net.minecraft.world.item.Items.TORCH);
        if (!torch.isEmpty()) {
            level.setBlock(pos.above(), net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), 3);
            inv.removeItem(net.minecraft.world.item.Items.TORCH, 1);
        }
    }

    private double lastRecallX, lastRecallZ;
    private int stuckRecallTicks = 0;

    /**
     * Emergency Recall: Nếu Steve bị kẹt hoặc quá xa người chơi mà không làm gì được, 
     * tự động teleport về cạnh người chơi.
     */
    private void checkEmergencyRecall(SteveEntity steve) {
        net.minecraft.world.entity.player.Player player = steve.level().getNearestPlayer(steve, 500.0);
        if (player == null) return;

        double distSq = steve.distanceToSqr(player);
        if (distSq > 22500) { // > 150 blocks
            double dx = Math.abs(steve.getX() - lastRecallX);
            double dz = Math.abs(steve.getZ() - lastRecallZ);
            
            if (dx < 0.1 && dz < 0.1) {
                stuckRecallTicks += 40;
            } else {
                stuckRecallTicks = 0;
            }
            
            if (stuckRecallTicks > 1200) { // 60 seconds stuck far away
                steve.sendChatMessage("Tao bị kẹt ở xó nào rồi, về với chủ nhân thôi! (Divine Recall)");
                steve.teleportTo(player.getX(), player.getY(), player.getZ());
                stuckRecallTicks = 0;
                steve.getNavigation().stop();
            }
        } else {
            stuckRecallTicks = 0;
        }
        lastRecallX = steve.getX();
        lastRecallZ = steve.getZ();
    }

    // ── Priority 4: Auto-Armor ─────────────────────────────────────────────

    /**
     * Tự động mặc giáp tốt nhất có trong túi đồ.
     */
    private void checkAutoArmor(SteveEntity steve) {
        var inv = steve.getMemory().getInventory();
        net.minecraft.world.entity.EquipmentSlot[] armorSlots = {
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET
        };

        for (net.minecraft.world.entity.EquipmentSlot slot : armorSlots) {
            net.minecraft.world.item.ItemStack current = steve.getItemBySlot(slot);
            net.minecraft.world.item.ItemStack bestInInv = inv.findBestArmorItem(slot);

            if (!bestInInv.isEmpty()) {
                int currentDefense = (current.getItem() instanceof net.minecraft.world.item.ArmorItem a) ? a.getDefense() : -1;
                int bestDefense = ((net.minecraft.world.item.ArmorItem) bestInInv.getItem()).getDefense();

                if (bestDefense > currentDefense) {
                    // Trả giáp cũ về inventory (nếu có)
                    if (!current.isEmpty()) {
                        inv.addItem(current.copy());
                    }
                    // Mặc giáp mới
                    steve.setItemSlot(slot, bestInInv.copy());
                    inv.removeItem(bestInInv.getItem(), 1);
                    
                    steve.sendChatMessage("Thay đồ mới thôi! " + bestInInv.getItem().getDescription().getString() + " xịn hơn nè.");
                    SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' equipped better armor in slot {}: {}", 
                        steve.getSteveName(), slot, bestInInv.getItem().getDescription().getString());
                }
            }
        }
    }

    // ── Priority 5: Auto-Potion ─────────────────────────────────────────────

    /**
     * Tự động dùng thuốc hỗ trợ (Strength, Speed, Fire Res) nếu có.
     */
    private void checkAutoPotion(SteveEntity steve) {
        var inv = steve.getMemory().getInventory();
        
        // 1. Fire Resistance if in Nether or near Lava
        if (steve.level().dimension() == net.minecraft.world.level.Level.NETHER || steve.isInLava()) {
            ensurePotionEffect(steve, inv, net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, "Kháng lửa");
        }

        // 2. Combat buffs if danger detected
        if (steve.isAggressive() || !steve.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, steve.getBoundingBox().inflate(8.0)).isEmpty()) {
            ensurePotionEffect(steve, inv, net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, "Sức mạnh");
            ensurePotionEffect(steve, inv, net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, "Tốc độ");
            ensurePotionEffect(steve, inv, net.minecraft.world.effect.MobEffects.REGENERATION, "Hồi phục");
        }
    }

    private void ensurePotionEffect(SteveEntity steve, SteveInventory inv, net.minecraft.world.effect.MobEffect effect, String name) {
        if (steve.hasEffect(effect)) return;

        // Tìm thuốc trong inventory (cả chai uống và chai ném)
        for (net.minecraft.world.item.ItemStack stack : inv.getItems()) {
            if (stack.getItem() instanceof net.minecraft.world.item.PotionItem || stack.getItem() instanceof net.minecraft.world.item.ThrowablePotionItem) {
                java.util.List<net.minecraft.world.effect.MobEffectInstance> effects = net.minecraft.world.item.alchemy.PotionUtils.getMobEffects(stack);
                for (net.minecraft.world.effect.MobEffectInstance inst : effects) {
                    if (inst.getEffect() == effect) {
                        // Sử dụng thuốc
                        steve.addEffect(new net.minecraft.world.effect.MobEffectInstance(inst));
                        inv.removeItem(stack.getItem(), 1);
                        if (stack.getItem() instanceof net.minecraft.world.item.PotionItem) {
                            inv.addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
                        }
                        steve.sendChatMessage("Uống thuốc " + name + " phát cho khỏe!");
                        SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' used potion for effect: {}", steve.getSteveName(), name);
                        return;
                    }
                }
            }
        }
    }

    // ── Priority -1: Emergency Healing (God Apple) ─────────────────────────

    /**
     * Nếu HP < 4 (2 tim) -> tự động ăn táo vàng (phù phép) nếu có.
     */
    private void checkEmergencyHealing(SteveEntity steve) {
        if (steve.getHealth() >= 4.0f) return;

        var inv = steve.getMemory().getInventory();
        
        // Priority 1: Enchanted Golden Apple
        net.minecraft.world.item.ItemStack godApple = inv.findFirstItem(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE);
        if (!godApple.isEmpty()) {
            applyGodAppleEffects(steve);
            inv.removeItem(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE, 1);
            steve.sendChatMessage("Cứu nguy! Cắn táo vàng phù phép thôi!");
            SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' used Enchanted Golden Apple", steve.getSteveName());
            return;
        }

        // Priority 2: Normal Golden Apple
        net.minecraft.world.item.ItemStack goldApple = inv.findFirstItem(net.minecraft.world.item.Items.GOLDEN_APPLE);
        if (!goldApple.isEmpty()) {
            applyGoldenAppleEffects(steve);
            inv.removeItem(net.minecraft.world.item.Items.GOLDEN_APPLE, 1);
            steve.sendChatMessage("Máu thấp quá, ăn táo vàng vậy!");
            SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' used Golden Apple", steve.getSteveName());
        }
    }

    /**
     * Tự động dùng thuốc Hồi máu tức thì (Instant Health) nếu HP quá thấp.
     * Chạy trong fastTick (2 ticks) để phản ứng cực nhanh.
     */
    private void checkInstantHealthPotion(SteveEntity steve) {
        if (steve.getHealth() >= 8.0f) return;

        var inv = steve.getMemory().getInventory();
        for (net.minecraft.world.item.ItemStack stack : inv.getItems()) {
            if (stack.getItem() instanceof net.minecraft.world.item.PotionItem || stack.getItem() instanceof net.minecraft.world.item.ThrowablePotionItem) {
                java.util.List<net.minecraft.world.effect.MobEffectInstance> effects = net.minecraft.world.item.alchemy.PotionUtils.getMobEffects(stack);
                for (net.minecraft.world.effect.MobEffectInstance inst : effects) {
                    if (inst.getEffect() == net.minecraft.world.effect.MobEffects.HEAL) {
                        // Bơm máu!
                        inst.getEffect().applyInstantenousEffect(steve, steve, steve, inst.getAmplifier(), 1.0D);
                        inv.removeItem(stack.getItem(), 1);
                        steve.sendChatMessage("Bơm bình máu cứu nguy cái!");
                        return;
                    }
                }
            }
        }
    }

    private void applyGodAppleEffects(SteveEntity steve) {
        steve.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 400, 1));
        steve.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 6000, 0));
        steve.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 6000, 0));
        steve.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION, 2400, 3));
    }

    private void applyGoldenAppleEffects(SteveEntity steve) {
        steve.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 100, 1));
        steve.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION, 2400, 0));
    }

    // ── Priority 0: Anti-Lava ──────────────────────────────────────────────

    /**
     * Nếu đang trong dung nham -> ngay lập tức đặt nước cứu nguy.
     */
    private void checkAntiLava(SteveEntity steve) {
        if (!steve.isInLava()) return;

        var inv = steve.getMemory().getInventory();
        net.minecraft.world.item.ItemStack waterBucket = inv.findFirstItem(net.minecraft.world.item.Items.WATER_BUCKET);
        
        if (!waterBucket.isEmpty()) {
            net.minecraft.core.BlockPos pos = steve.blockPosition();
            steve.level().setBlock(pos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
            inv.removeItem(net.minecraft.world.item.Items.WATER_BUCKET, 1);
            inv.addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET));
            
            steve.sendChatMessage("Nóng quá! Dập lửa bằng nước thôi!");
            SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' used Anti-Lava Water Bucket", steve.getSteveName());
            
            // Note: Don't remove water immediately in lava, let it obsidianize
        }
    }

    // ── Priority 1: Auto-Totem ─────────────────────────────────────────────

    /**
     * Nếu HP < 10 (5 tim) và có Totem trong túi -> tự động đeo vào tay phụ.
     */
    private void checkAutoTotem(SteveEntity steve) {
        if (steve.getHealth() >= 10.0f) return;
        
        // Kiểm tra xem tay phụ đang cầm gì
        net.minecraft.world.item.ItemStack offhand = steve.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        if (offhand.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) return;

        var inv = steve.getMemory().getInventory();
        net.minecraft.world.item.ItemStack totem = inv.findFirstItem(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
        
        if (!totem.isEmpty()) {
            // Đeo totem vào tay phụ
            steve.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, totem.copy());
            inv.removeItem(net.minecraft.world.item.Items.TOTEM_OF_UNDYING, 1);
            steve.sendChatMessage("Cấp cứu! Cầm Totem thôi!");
            SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' auto-equipped Totem (HP={})", 
                steve.getSteveName(), steve.getHealth());
        }
    }

    // ── Priority 2: Water MLG ──────────────────────────────────────────────

    /**
     * Nếu đang rơi (>3.5 block) và có Xô nước -> đặt nước dưới chân.
     */
    private void checkWaterMLG(SteveEntity steve) {
        if (steve.fallDistance < 3.5f) return;
        if (steve.isInWater() || steve.isInLava() || steve.isFlying()) return;
        
        // Chỉ đặt nếu bên dưới là không khí
        net.minecraft.core.BlockPos below = steve.blockPosition();
        if (!steve.level().getBlockState(below).isAir()) return;

        var inv = steve.getMemory().getInventory();
        net.minecraft.world.item.ItemStack waterBucket = inv.findFirstItem(net.minecraft.world.item.Items.WATER_BUCKET);
        
        if (!waterBucket.isEmpty()) {
            // Thực hiện MLG!
            steve.level().setBlock(below, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
            inv.removeItem(net.minecraft.world.item.Items.WATER_BUCKET, 1);
            inv.addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET));
            
            steve.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            steve.sendChatMessage("MLG nước nè!");
            SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' performed Water MLG (Height={})", 
                steve.getSteveName(), steve.fallDistance);

            // Thu hồi nước sau 2 giây (40 ticks)
            new java.util.Timer().schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    steve.level().getServer().execute(() -> {
                        if (steve.level().getBlockState(below).getBlock() == net.minecraft.world.level.block.Blocks.WATER) {
                            steve.level().setBlock(below, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                        }
                    });
                }
            }, 2000);
        }
    }

    // ── Priority 3: Auto-Shield ──────────────────────────────────────────────

    /**
     * Tự động giơ khiên nếu có projectile bay tới hoặc Creeper sắp nổ.
     */
    private void checkAutoShield(SteveEntity steve) {
        boolean dangerDetected = false;

        // 1. Detect Projectiles (Arrows, Fireballs)
        net.minecraft.world.phys.AABB box = steve.getBoundingBox().inflate(10.0);
        java.util.List<net.minecraft.world.entity.projectile.Projectile> projectiles = 
            steve.level().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class, box);
        
        for (net.minecraft.world.entity.projectile.Projectile p : projectiles) {
            // Check if projectile is moving towards Steve
            net.minecraft.world.phys.Vec3 vel = p.getDeltaMovement();
            net.minecraft.world.phys.Vec3 toSteve = steve.position().subtract(p.position()).normalize();
            if (vel.dot(toSteve) > 0.5) { // Projectile is heading approximately towards Steve
                dangerDetected = true;
                break;
            }
        }

        // 2. Detect Creepers about to explode
        if (!dangerDetected) {
            java.util.List<net.minecraft.world.entity.monster.Creeper> creepers = 
                steve.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, steve.getBoundingBox().inflate(5.0));
            for (net.minecraft.world.entity.monster.Creeper creeper : creepers) {
                if (creeper.getSwellDir() > 0) { // Is swelling (primed)
                    dangerDetected = true;
                    break;
                }
            }
        }

        // 3. Detect Ravagers & Vexes
        if (!dangerDetected) {
            java.util.List<net.minecraft.world.entity.LivingEntity> threats = 
                steve.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, steve.getBoundingBox().inflate(8.0),
                e -> e instanceof net.minecraft.world.entity.monster.Ravager || e instanceof net.minecraft.world.entity.monster.Vex);
            for (var threat : threats) {
                if (threat instanceof net.minecraft.world.entity.monster.Ravager ravager && ravager.getAttackTick() > 0) {
                    dangerDetected = true; // Ravager is charging/attacking
                    break;
                }
                if (threat instanceof net.minecraft.world.entity.monster.Vex) {
                    dangerDetected = true; // Vexes are erratic and hard to track, stay shielded
                    break;
                }
            }
        }

        if (dangerDetected) {
            if (SteveEquipmentTracker.ensureShieldEquipped(steve)) {
                steve.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
                // Note: stopUsingItem will be called naturally when danger is gone (next tick check)
            }
        } else {
            // No danger, but if we are manually using shield for Auto-Shield, stop it
            if (steve.isUsingItem() && steve.getUseItem().getItem() instanceof net.minecraft.world.item.ShieldItem) {
                // Check if this is a survival-driven shield use or if we are in SHIELD_APPROACH state of CombatAction
                // For simplicity, we just stop using if no danger is visible in this check.
                // Actual CombatAction will use shield manually in its own tick.
                steve.stopUsingItem();
            }
        }
    }


    // ── Priority 9: Mending Logic ──────────────────────────────────────────

    /**
     * Nếu có đồ Mending bị hỏng và có XP quanh đây -> cầm lên tay để sửa.
     */
    private void checkMendingLogic(SteveEntity steve) {
        if (steve.tickCount % 20 != 0) return;

        // Tìm kinh nghiệm quanh đây
        java.util.List<net.minecraft.world.entity.ExperienceOrb> orbs = 
            steve.level().getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, steve.getBoundingBox().inflate(8.0));
        
        if (orbs.isEmpty()) return;

        // Kiểm tra xem có đồ nào cần sửa bằng Mending không
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            net.minecraft.world.item.ItemStack stack = steve.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isDamaged()) continue;

            if (net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                    net.minecraft.world.item.enchantment.Enchantments.MENDING, stack) > 0) {
                
                // Nếu là giáp thì nó tự sửa, nếu là tool thì phải cầm trên tay
                if (slot == net.minecraft.world.entity.EquipmentSlot.MAINHAND || slot == net.minecraft.world.entity.EquipmentSlot.OFFHAND) {
                    // Đã cầm rồi, chỉ cần chờ XP bay vào
                    return;
                } else if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) {
                    // Giáp đang mặc rồi, tự sửa
                    return;
                } else {
                    // Item trong kho đồ? (Steve không có túi đồ thật cho mending nhảy vào, 
                    // nhưng ta giả định Steve sẽ cầm món đồ hỏng nhất lên tay chính nếu có XP)
                }
            }
        }

        // Kiểm tra trong SteveInventory có món mending nào hỏng không
        var inv = steve.getMemory().getInventory();
        for (net.minecraft.world.item.ItemStack stack : inv.getItems()) {
            if (stack.isDamaged() && net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                    net.minecraft.world.item.enchantment.Enchantments.MENDING, stack) > 0) {
                
                // Cầm món đồ hỏng nhất lên tay chính để hòng XP
                steve.sendChatMessage("Có kinh nghiệm! Để tao lôi nốt đồ Mending ra sửa.");
                steve.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack.copy());
                inv.removeItem(stack.getItem(), 1);
                return;
            }
        }
    }

    /**
     * Tự động đi đào mỏ nếu thiếu nguyên liệu quan trọng.
     */
    private void checkResourceNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 1200 != 0) return; // Check mỗi 1 phút
        if (executor.isExecuting()) return;

        var inv = steve.getMemory().getInventory();
        
        // Thiếu sắt?
        if (!inv.hasItem(net.minecraft.world.item.Items.IRON_INGOT, 8)) {
            steve.sendChatMessage("Hết sắt rồi, đi đào thêm thôi!");
            executor.enqueue(new com.steve.ai.action.Task("mine_block", java.util.Map.of("block", "iron_ore", "quantity", 16)));
            return;
        }

        // Thiếu kim cương?
        if (!inv.hasItem(net.minecraft.world.item.Items.DIAMOND, 2)) {
            steve.sendChatMessage("Cần thêm kim cương để nâng cấp đồ.");
            executor.enqueue(new com.steve.ai.action.Task("mine_block", java.util.Map.of("block", "diamond_ore", "quantity", 4)));
        }
    }

    /**
     * Tự động thu hoạch và trồng trọt nếu thấy ruộng chín hoặc ô trống.
     */
    private void checkAgricultureNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 600 != 0) return; // Mỗi 30s
        if (executor.isExecuting()) return;

        // Scan nearby crops
        BlockPos center = steve.blockPosition();
        int r = 16;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                BlockPos p = center.offset(x, 0, z);
                net.minecraft.world.level.block.state.BlockState s = steve.level().getBlockState(p);
                
                // 1. Thu hoạch nếu vụ mùa chín
                if (s.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop && crop.isMaxAge(s)) {
                    steve.sendChatMessage("Đến mùa gặt rồi! Để tao thu hoạch lúa.");
                    executor.enqueue(new com.steve.ai.action.Task("farm", java.util.Map.of("mode", "harvest", "radius", 16)));
                    return;
                }
                
                // 2. Trồng hạt nếu thấy đất trống
                if (s.getBlock() instanceof net.minecraft.world.level.block.FarmBlock && steve.level().getBlockState(p.above()).isAir()) {
                    var inv = steve.getMemory().getInventory();
                    if (inv.hasItem(net.minecraft.world.item.Items.WHEAT_SEEDS, 1) || inv.hasItem(net.minecraft.world.item.Items.CARROT, 1)) {
                        steve.sendChatMessage("Thấy đất trống kìa, gieo ít hạt giống cho xanh ruộng nào.");
                        executor.enqueue(new com.steve.ai.action.Task("farm", java.util.Map.of("mode", "plant", "radius", 16)));
                        return;
                    }
                }
            }
        }
    }

    /**
     * Tự động đi chăn nuôi nếu có đủ thức ăn.
     */
    private void checkAnimalHusbandry(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 1200 != 0) return; // Mỗi phút
        if (executor.isExecuting()) return;

        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.WHEAT, 2) 
            || inv.hasItem(net.minecraft.world.item.Items.CARROT, 2)
            || inv.hasItem(net.minecraft.world.item.Items.WHEAT_SEEDS, 2)) {
            
            // Kiểm tra xem có động vật quanh đây không
            var animals = steve.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, 
                steve.getBoundingBox().inflate(16));
            
            // 1. Phối giống nếu có thức ăn
            if (animals.size() >= 2) {
                steve.sendChatMessage("Gia súc đông phết, để tao cho tụi nó đẻ thêm.");
                executor.enqueue(new com.steve.ai.action.Task("farm", java.util.Map.of("mode", "breed", "radius", 16)));
            }
            
            // 2. Thu hoạch (giết thịt) bớt nếu quá đông (> 10 con cùng loại)
            java.util.Map<String, Integer> countMap = new java.util.HashMap<>();
            for (var a : animals) {
                String type = a.getType().getDescriptionId();
                countMap.put(type, countMap.getOrDefault(type, 0) + 1);
                
                if (countMap.get(type) > 10 && a.getAge() == 0) { // Giết những con trưởng thành dư thừa
                    steve.sendChatMessage("Trang trại đông quá rồi, thu hoạch ít " + a.getDisplayName().getString() + " lấy thịt thôi.");
                    executor.enqueue(new com.steve.ai.action.Task("attack", java.util.Map.of("target", a.getUUID().toString())));
                    return;
                }
            }
        }
    }

    /**
     * Tự động đi câu cá nếu ở gần nước và rảnh rỗi.
     */
    private void checkFishingOpportunity(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 2400 != 0) return; // Mỗi 2 phút
        if (executor.isExecuting()) return;
        
        var inv = steve.getMemory().getInventory();
        if (!inv.hasItem(net.minecraft.world.item.Items.FISHING_ROD, 1)) return;

        // Check water nearby
        if (hasWaterNearby(steve, 16)) {
            steve.sendChatMessage("Đang rảnh, ra bờ sông câu cá kiếm bữa cơm.");
            executor.enqueue(new com.steve.ai.action.Task("fish", java.util.Map.of("quantity", 3)));
        }
    }

    private boolean hasWaterNearby(SteveEntity steve, int r) {
        BlockPos center = steve.blockPosition();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (steve.level().getBlockState(center.offset(x, 0, z)).getBlock() == net.minecraft.world.level.block.Blocks.WATER) return true;
            }
        }
        return false;
    }

    /**
     * Tự động vô hiệu hóa lồng quái (Spawner) bằng đuốc.
     */
    private void checkSpawnerSecurity(SteveEntity steve) {
        if (steve.tickCount % 20 != 0) return;
        
        BlockPos center = steve.blockPosition();
        int r = 5;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos p = center.offset(x, y, z);
                    if (steve.level().getBlockState(p).getBlock() == net.minecraft.world.level.block.Blocks.SPAWNER) {
                        // Thấy spawner! Đặt đuốc xung quanh.
                        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                            BlockPos torchPos = p.relative(dir);
                            if (steve.level().getBlockState(torchPos).isAir()) {
                                var inv = steve.getMemory().getInventory();
                                if (inv.hasItem(net.minecraft.world.item.Items.TORCH, 1)) {
                                    steve.sendChatMessage("Phát hiện lồng quái! Phải thắp đuốc cho an toàn.");
                                    steve.level().setBlock(torchPos, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), 3);
                                    inv.removeItem(net.minecraft.world.item.Items.TORCH, 1);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Tự động thám hiểm và loot rương nếu thấy rương lạ.
     */
    private void checkScoutingNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 400 != 0) return;
        if (executor.isExecuting()) return;

        BlockPos center = steve.blockPosition();
        int r = 12;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -3; y <= 3; y++) {
                    BlockPos p = center.offset(x, y, z);
                    net.minecraft.world.level.block.Block block = steve.level().getBlockState(p).getBlock();
                    if (block == net.minecraft.world.level.block.Blocks.CHEST || block == net.minecraft.world.level.block.Blocks.BARREL) {
                        // Thấy rương/thùng! Loot thôi.
                        steve.sendChatMessage("Phát hiện vật tích, để tao 'thanh tra' cái rương này phát.");
                        executor.enqueue(new com.steve.ai.action.Task("chest", java.util.Map.of("mode", "loot")));
                        return;
                    }
                    if (block == net.minecraft.world.level.block.Blocks.SUSPICIOUS_SAND || block == net.minecraft.world.level.block.Blocks.SUSPICIOUS_GRAVEL) {
                        // Thấy cát/sỏi nghi vấn! Archeology thôi.
                        steve.sendChatMessage("Nhìn khối cát/sỏi này lạ quá, chắc có đồ cổ bên trong.");
                        executor.enqueue(new com.steve.ai.action.Task("archeology", java.util.Map.of("pos", p)));
                        return;
                    }
                }
            }
        }
    }

    /**
     * Tự động giao thương nếu ở trong làng.
     */
    private void checkVillageTrading(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 1200 != 0) return;
        if (executor.isExecuting()) return;

        // Check if near villagers
        var villagers = steve.level().getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, 
            steve.getBoundingBox().inflate(16));
        if (!villagers.isEmpty()) {
            steve.sendChatMessage("Đang ở trong làng, để tao ghé qua mấy ông dân làng giao dịch tí.");
            executor.enqueue(new com.steve.ai.action.Task("trade", java.util.Map.of("mode", "smart_auto")));
        }
    }

    /**
     * Thỉnh thoảng Steve sẽ lẩm bẩm về thế giới.
     */
    private void checkDialoguePersonality(SteveEntity steve) {
        if (steve.tickCount % 1200 == 0 && random.nextFloat() < 0.3f) {
            String msg = DialogueEngine.getRandomObservation(steve);
            if (msg != null) steve.sendChatMessage(msg);
        }
    }

    private final java.util.Random random = new java.util.Random();

    /**
     * Tự động quăng Ender Pearl để thoát thân nếu máu quá thấp và bị vây quanh.
     */
    private void checkTacticalRetreat(SteveEntity steve) {
        if (steve.tickCount % 10 != 0) return;
        if (steve.getHealth() >= 6.0f) return;

        var inv = steve.getMemory().getInventory();
        net.minecraft.world.item.ItemStack pearl = inv.findFirstItem(net.minecraft.world.item.Items.ENDER_PEARL);
        
        if (!pearl.isEmpty()) {
            // Kiểm tra xem có nguy hiểm sát sườn không (3+ mob trong 5 block)
            var nearbyMobs = steve.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, 
                steve.getBoundingBox().inflate(5.0));
            
            if (nearbyMobs.size() >= 3) {
                steve.sendChatMessage("Căng quá! Té nhanh thôi!");
                // Throw pearl - ném về phía ngược lại với mob trung bình
                net.minecraft.world.phys.Vec3 mobCenter = net.minecraft.world.phys.Vec3.ZERO;
                for (var mob : nearbyMobs) mobCenter = mobCenter.add(mob.position());
                mobCenter = mobCenter.scale(1.0 / nearbyMobs.size());
                
                net.minecraft.world.phys.Vec3 retreatDir = steve.position().subtract(mobCenter).normalize().scale(20);
                net.minecraft.world.entity.projectile.ThrownEnderpearl thrownPearl = 
                    new net.minecraft.world.entity.projectile.ThrownEnderpearl(steve.level(), steve);
                thrownPearl.shoot(retreatDir.x, 5.0, retreatDir.z, 1.5f, 1.0f);
                steve.level().addFreshEntity(thrownPearl);
                
                inv.removeItem(net.minecraft.world.item.Items.ENDER_PEARL, 1);
                SteveMod.LOGGER.info("Steve '{}' used tactical Ender Pearl retreat", steve.getSteveName());
            }
        }
    }

    /**
     * Tự động pha chế thuốc nếu có đủ nguyên liệu.
     */
    private void checkBrewingNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 2400 != 0) return;
        if (executor.isExecuting()) return;

        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.NETHER_WART, 1) && inv.hasItem(net.minecraft.world.item.Items.BLAZE_POWDER, 1)) {
            if (inv.hasItem(net.minecraft.world.item.Items.SUGAR, 1)) {
                executor.enqueue(new com.steve.ai.action.Task("brew", java.util.Map.of("potion", "speed")));
            } else if (inv.hasItem(net.minecraft.world.item.Items.MAGMA_CREAM, 1)) {
                executor.enqueue(new com.steve.ai.action.Task("brew", java.util.Map.of("potion", "fire_resistance")));
            }
        }
    }

    /**
     * Tự động phù phép đồ nếu level cao.
     */
    private void checkEnchantingNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 4800 != 0) return;
        if (executor.isExecuting()) return;

        if (steve.getExperienceLevel() >= 30) {
            var inv = steve.getMemory().getInventory();
            if (inv.hasItem(net.minecraft.world.item.Items.LAPIS_LAZULI, 3)) {
                steve.sendChatMessage("Level cao rồi, đi phù phép cho đồ mạnh lên nào.");
                executor.enqueue(new com.steve.ai.action.Task("enchant", java.util.Map.of()));
            }
        }
    }

    /**
     * Tự động nung quặng nếu có than và quặng thô.
     */
    private void checkSmeltingNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 1200 != 0) return;
        if (executor.isExecuting()) return;

        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.COAL, 1) || inv.hasItem(net.minecraft.world.item.Items.CHARCOAL, 1)) {
            // ── Ores ────────────────────────────────────────────────────────
            if (inv.hasItem(net.minecraft.world.item.Items.RAW_IRON, 4)) {
                executor.enqueue(new com.steve.ai.action.Task("smelt", java.util.Map.of("item", "raw_iron", "quantity", 4)));
            } else if (inv.hasItem(net.minecraft.world.item.Items.RAW_GOLD, 4)) {
                executor.enqueue(new com.steve.ai.action.Task("smelt", java.util.Map.of("item", "raw_gold", "quantity", 4)));
            } 
            // ── Food (Culinary Autonomy) ────────────────────────────────────
            else if (inv.hasItem(net.minecraft.world.item.Items.BEEF, 1)) {
                executor.enqueue(new com.steve.ai.action.Task("smelt", java.util.Map.of("item", "beef", "quantity", 1)));
            } else if (inv.hasItem(net.minecraft.world.item.Items.PORKCHOP, 1)) {
                executor.enqueue(new com.steve.ai.action.Task("smelt", java.util.Map.of("item", "porkchop", "quantity", 1)));
            } else if (inv.hasItem(net.minecraft.world.item.Items.CHICKEN, 1)) {
                executor.enqueue(new com.steve.ai.action.Task("smelt", java.util.Map.of("item", "chicken", "quantity", 1)));
            } else if (inv.hasItem(net.minecraft.world.item.Items.MUTTON, 1)) {
                executor.enqueue(new com.steve.ai.action.Task("smelt", java.util.Map.of("item", "mutton", "quantity", 1)));
            } else if (inv.hasItem(net.minecraft.world.item.Items.RABBIT, 1)) {
                executor.enqueue(new com.steve.ai.action.Task("smelt", java.util.Map.of("item", "rabbit", "quantity", 1)));
            }
            // ── Others ──────────────────────────────────────────────────────
            else if (inv.hasItem(net.minecraft.world.item.Items.SAND, 8)) {
                executor.enqueue(new com.steve.ai.action.Task("smelt", java.util.Map.of("item", "sand", "quantity", 8)));
            }
        }
    }

    /**
     * Tự động xây cổng Nether nếu đã đủ 10 obsidian.
     */
    private void checkDimensionNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 2400 != 0) return;
        if (executor.isExecuting()) return;
        if (steve.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return;

        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.OBSIDIAN, 10) && inv.hasItem(net.minecraft.world.item.Items.FLINT_AND_STEEL, 1)) {
            steve.sendChatMessage("Đã đủ Obsidian và Bật lửa, để tao xây cổng đi đổi gió nào.");
            executor.enqueue(new com.steve.ai.action.Task("dimension", java.util.Map.of("mode", "build")));
        }
    }

    /**
     * Tự động nâng cấp đồ Diamond lên Netherite nếu có phôi.
     */
    private void checkSmithingNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 4800 != 0) return;
        if (executor.isExecuting()) return;

        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.NETHERITE_INGOT, 1)) {
            steve.sendChatMessage("Có thỏi Netherite rồi! Đi tìm Bàn Rèn nâng cấp đồ thôi.");
            executor.enqueue(new com.steve.ai.action.Task("smithing", java.util.Map.of()));
        }
    }

    /**
     * Tự động nâng cấp bậc công cụ (Stone -> Iron -> Diamond).
     */
    private void checkToolUpgrade(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 600 != 0) return; // Check mỗi 30s
        if (executor.isExecuting()) return;

        var inv = steve.getMemory().getInventory();
        
        // 1. Upgrade to Diamond Pickaxe
        if (inv.hasItem(net.minecraft.world.item.Items.DIAMOND, 3) && !inv.hasItem(net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1)) {
            steve.sendChatMessage("Đã đủ kim cương! Phải nâng cấp lên Cúp Kim Cương ngay cho nóng.");
            executor.enqueue(new com.steve.ai.action.Task("craft", java.util.Map.of("item", "diamond_pickaxe", "quantity", 1)));
            return;
        }

        // 2. Upgrade to Iron Pickaxe
        if (inv.hasItem(net.minecraft.world.item.Items.IRON_INGOT, 3) && !inv.hasItem(net.minecraft.world.item.Items.IRON_PICKAXE, 1) && !inv.hasItem(net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1)) {
            steve.sendChatMessage("Có sắt rồi, chế cái Cúp Sắt đào cho nhanh nào.");
            executor.enqueue(new com.steve.ai.action.Task("craft", java.util.Map.of("item", "iron_pickaxe", "quantity", 1)));
            return;
        }

        // 3. Upgrade to Iron Sword
        if (inv.hasItem(net.minecraft.world.item.Items.IRON_INGOT, 2) && !inv.hasItem(net.minecraft.world.item.Items.IRON_SWORD, 1) && !inv.hasItem(net.minecraft.world.item.Items.DIAMOND_SWORD, 1)) {
            executor.enqueue(new com.steve.ai.action.Task("craft", java.util.Map.of("item", "iron_sword", "quantity", 1)));
            return;
        }

        // 4. Basic Progression: Upgrade to Stone Pickaxe
        if (inv.hasItem(net.minecraft.world.item.Items.COBBLESTONE, 3) && !inv.hasItem(net.minecraft.world.item.Items.STONE_PICKAXE, 1) 
                && !inv.hasItem(net.minecraft.world.item.Items.IRON_PICKAXE, 1) && !inv.hasItem(net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1)) {
            steve.sendChatMessage("Đã có đá cuội! Phải làm cái Cúp Đá để đào Sắt thôi.");
            executor.enqueue(new com.steve.ai.action.Task("craft", java.util.Map.of("item", "stone_pickaxe", "quantity", 1)));
        }
    }

    /**
     * Tự động đào Ancient Debris nếu đang ở Nether.
     */
    private void checkAncientDebrisMining(SteveEntity steve, ActionExecutor executor) {
        if (steve.level().dimension() != net.minecraft.world.level.Level.NETHER) return;
        if (steve.tickCount % 1200 != 0) return;
        if (executor.isExecuting()) return;

        // Nếu ở tầng thấp (Y < 20), tìm Ancient Debris
        if (steve.blockPosition().getY() < 20) {
            steve.sendChatMessage("Tầng này chắc là có Mảnh Cổ Vật, để tao kiếm ít về nâng cấp đồ.");
            executor.enqueue(new com.steve.ai.action.Task("mine_block", java.util.Map.of("block", "ancient_debris", "quantity", 4)));
        }
    }

    /**
     * Gợi ý chuẩn bị đấu Boss nếu thu thập đủ nguyên liệu.
     */
    private void checkEndGamePrep(SteveEntity steve) {
        if (steve.tickCount % 6000 != 0) return;
        
        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.WITHER_SKELETON_SKULL, 3) && inv.hasItem(net.minecraft.world.level.block.Blocks.SOUL_SAND.asItem(), 4)) {
            steve.sendChatMessage("Tao đã có đủ đồ để gọi Wither rồi. Chuẩn bị tinh thần đi, trận chiến sắp tới sẽ rất gắt đấy!");
        }
    }

    /**
     * Tự động lọc rác nếu túi đồ đầy.
     */
    private void checkInventoryManagement(SteveEntity steve) {
        if (steve.tickCount % 200 == 0) {
            SteveInventory inv = steve.getMemory().getInventory();
            // Lọc các item rác thực sự
            inv.removeItem(net.minecraft.world.item.Items.ROTTEN_FLESH, 32);
            inv.removeItem(net.minecraft.world.item.Items.POISONOUS_POTATO, 1);
            inv.removeItem(net.minecraft.world.item.Items.SPIDER_EYE, 16);
            
            if (inv.isFull()) {
                inv.cleanTrash(steve);
            }
        }
    }

    private int lastAnnounceTick = 0;
    /**
     * Steve thông báo tình trạng và mục tiêu hiện tại.
     */
    private void checkAnnouncements(SteveEntity steve) {
        if (steve.tickCount - lastAnnounceTick < 6000) return; // 5 phút một lần
        
        lastAnnounceTick = steve.tickCount;
        String goal = "đang thám hiểm thế giới";
        if (steve.getActionExecutor().isExecuting()) {
            goal = "đang thực hiện nhiệm vụ: " + steve.getActionExecutor().getCurrentActionDescription();
        }
        
        steve.sendChatMessage("Tình trạng: " + (int)steve.getHealth() + " HP, " + steve.getSteveHunger() + " Hunger. Tao " + goal + ".");
    }

    /**
     * Tự xây nguồn nước vô tận nếu cần nước.
     */
    private void checkWaterNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 2400 != 0) return;
        if (executor.isExecuting()) return;

        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.WATER_BUCKET, 2)) {
            // Kiểm tra xem có cần nước không (fishing rod hoặc farm gần đây)
            if (inv.hasItem(net.minecraft.world.item.Items.FISHING_ROD, 1)) {
                steve.sendChatMessage("Tạo nguồn nước vô tận để câu cá cho tiện nào.");
                BlockPos p = steve.blockPosition().north(3);
                // Đặt nước 2x2 (Simulated simple: just place 2 buckets in a row)
                steve.level().setBlock(p, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
                steve.level().setBlock(p.east(), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
                inv.removeItem(net.minecraft.world.item.Items.WATER_BUCKET, 2);
                inv.addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET, 2));
            }
        }
    }

    /**
     * Gợi ý tìm Stronghold nếu đã đủ Mắt Ender.
     */
    private void checkStrongholdNeeds(SteveEntity steve) {
        if (steve.tickCount % 12000 != 0) return; // 10 phút check một lần
        
        var inv = steve.getMemory().getInventory();
        if (inv.hasItem(net.minecraft.world.item.Items.ENDER_EYE, 12)) {
            steve.sendChatMessage("Tao đã có đủ hơn 12 Mắt Ender rồi. Chúng ta nên đi tìm Pháo đài dưới lòng đất (Stronghold) để chuẩn bị diệt Rồng thôi!");
        }
    }

    /**
     * Nếu hunger ≤ 4 (2 tim đói) và không có đồ ăn trong túi → cần kiếm ăn khẩn cấp.
     * Interrupt task hiện tại nếu cần.
     */
    private void checkCriticalHunger(SteveEntity steve, ActionExecutor executor) {
        if (hungerSearchCooldown > 0) return;
        
        int threshold = 4;
        String pType = steve.getPersonality().getType().name();
        if ("SERIOUS".equals(pType) || "CALM".equals(pType)) threshold = 6;
        else if ("BRAVE".equals(pType)) threshold = 3;

        if (steve.getSteveHunger() > threshold) return;  // Chưa đói khẩn cấp

        // Kiểm tra còn đồ ăn không
        boolean hasFood = hasAnyFood(steve);
        if (hasFood) return; // autoEatIfHungry() sẽ xử lý

        if (hungerSearchCooldown == 0) {
            steve.sendChatMessage("Đói lã rồi! Phải kiếm đồ ăn ngay!");
            // Enqueue gather food task (ưu tiên thấp — không interrupt task đang chạy)
            if (!executor.isExecuting()) {
                Task gatherFood = new Task("gather", Map.of("resource", "food", "quantity", 5));
                executor.enqueue(gatherFood);
                SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' queuing emergency food gather",
                    steve.getSteveName());
            }
            hungerSearchCooldown = 600; // 30 giây cooldown
        }
    }

    /** Kiểm tra Steve có bất kỳ đồ ăn nào không */
    private boolean hasAnyFood(SteveEntity steve) {
        var inv = steve.getMemory().getInventory();
        return inv.hasItem(net.minecraft.world.item.Items.COOKED_BEEF, 1)
            || inv.hasItem(net.minecraft.world.item.Items.COOKED_PORKCHOP, 1)
            || inv.hasItem(net.minecraft.world.item.Items.COOKED_CHICKEN, 1)
            || inv.hasItem(net.minecraft.world.item.Items.BREAD, 1)
            || inv.hasItem(net.minecraft.world.item.Items.COOKED_SALMON, 1)
            || inv.hasItem(net.minecraft.world.item.Items.APPLE, 1)
            || inv.hasItem(net.minecraft.world.item.Items.CARROT, 1)
            || inv.hasItem(net.minecraft.world.item.Items.POTATO, 1);
    }

    // ── Priority 4: Về nhà ban đêm ──────────────────────────────────────────

    /**
     * Khi trời tối + không có task + Steve có waypoint "home" và đang ở xa → về nhà.
     * Không interrupt task — chỉ enqueue khi idle.
     */
    private void checkGoHomeAtNight(SteveEntity steve, ActionExecutor executor) {
        if (goHomeCooldown > 0) return;
        if (executor.isExecuting()) return;

        long dayTime = steve.level().getDayTime() % 24000;
        
        int nightStart = 13000; // Default
        String pType = steve.getPersonality().getType().name();
        if ("BRAVE".equals(pType)) nightStart = 14500; // Stays out later
        else if ("SERIOUS".equals(pType)) nightStart = 12500; // Heads back early

        boolean isNight = dayTime >= nightStart && dayTime < 23500;
        if (!isNight) return;

        WaypointMemory waypoints = steve.getMemory().getWaypoints();
        if (!waypoints.has("home")) return;

        Optional<BlockPos> homePos = waypoints.get("home");
        if (homePos.isEmpty()) return;

        double distToHome = steve.blockPosition().distSqr(homePos.get());
        if (distToHome < 30 * 30) return; // Đã ở gần nhà (<30 block)

        // Về nhà!
        Task goHome = new Task("waypoint", Map.of("action", "goto", "label", "home"));
        executor.enqueue(goHome);
        steve.sendChatMessage("Tối rồi, về nhà thôi!");
        goHomeCooldown = 2400; // 2 phút cooldown
        SteveMod.LOGGER.info("SurvivalScheduler: Steve '{}' going home at night", steve.getSteveName());
    }

    // ── Priority 5: Cảnh báo tool sắp gãy ──────────────────────────────────

    /**
     * Khi main-hand tool dưới 10% durability → cảnh báo hoặc tự động tráo đổi.
     */
    private void checkToolDurability(SteveEntity steve) {
        if (toolWarnCooldown > 0) return;

        net.minecraft.world.item.ItemStack tool = steve.getMainHandItem();
        if (tool.isEmpty() || !tool.isDamageableItem()) return;

        if (SteveEquipmentTracker.needsRepair(tool)) {
            var inv = steve.getMemory().getInventory();
            // Thử tìm tool cùng loại còn mới hơn trong inventory
            net.minecraft.world.item.ItemStack spare = inv.findFirstItem(tool.getItem());
            
            if (!spare.isEmpty() && spare.getDamageValue() < tool.getDamageValue()) {
                steve.sendChatMessage("Cúp sắp gãy rồi, đổi cái mới cho chắc!");
                // Tráo đổi
                inv.addItem(tool.copy());
                steve.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, spare.copy());
                inv.removeItem(spare.getItem(), 1);
                toolWarnCooldown = 600; // 30s cooldown
            } else {
                int pct = Math.round(SteveEquipmentTracker.getDurabilityFraction(tool) * 100);
                steve.sendChatMessage("Cẩn thận! " + tool.getItem().getDescription().getString()
                    + " sắp gãy rồi (" + pct + "% độ bền còn lại)!");
                toolWarnCooldown = 1200; // 60 giây cooldown
            }
        }
    }

    /**
     * Tự động xây Kho tiếp tế (Supply Depot) nếu túi đồ đầy và ở xa nhà.
     */
    private void checkSupplyDepot(SteveEntity steve, ActionExecutor executor) {
        if (steve.tickCount % 2400 != 0) return;
        if (executor.isExecuting()) return;

        com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
        if (!inv.isFull()) return;

        BlockPos home = steve.getMemory().getWaypoints().get("home").orElse(null);
        double distToHome = (home != null) ? steve.blockPosition().distSqr(home) : Double.MAX_VALUE;

        if (distToHome > 250000) { // > 500 blocks
            steve.sendChatMessage("Đồ đạc lỉnh kỉnh quá, để tao dựng cái 'Kho tiếp tế' cất bớt đồ đi cho nhẹ người.");
            // Simulate building a chest and storing items
            BlockPos p = steve.blockPosition().relative(steve.getDirection());
            if (steve.level().getBlockState(p).isAir()) {
                if (inv.hasItem(net.minecraft.world.item.Items.CHEST, 1) || inv.hasItem(net.minecraft.world.item.Items.OAK_PLANKS, 8)) {
                    executor.enqueue(new com.steve.ai.action.Task("build", java.util.Map.of("structure", "supply_depot", "pos", p)));
                    // Ghi nhớ waypoint kho tiếp tế
                    steve.getMemory().getWaypoints().save("supply_depot_" + steve.tickCount, p);
                }
            }
        }
    }

    /**
     * Tự động giải độc hoặc xóa hiệu ứng xấu bằng Sữa.
     */
    private void checkStatusRemediation(SteveEntity steve) {
        if (steve.tickCount % 100 != 0) return;

        boolean needsCleansing = steve.hasEffect(net.minecraft.world.effect.MobEffects.POISON) 
                              || steve.hasEffect(net.minecraft.world.effect.MobEffects.WITHER)
                              || steve.hasEffect(net.minecraft.world.effect.MobEffects.BAD_OMEN);

        if (needsCleansing) {
            var inv = steve.getMemory().getInventory();
            net.minecraft.world.item.ItemStack milk = inv.findFirstItem(net.minecraft.world.item.Items.MILK_BUCKET);
            if (!milk.isEmpty()) {
                steve.sendChatMessage("Người ngợm khó chịu quá, làm ngụm sữa cho tỉnh táo!");
                steve.removeAllEffects(); // Minecraft logic for milk
                inv.removeItem(net.minecraft.world.item.Items.MILK_BUCKET, 1);
                inv.addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET));
            } else if (steve.hasEffect(net.minecraft.world.effect.MobEffects.BAD_OMEN)) {
                steve.sendChatMessage("Tao đang có hiệu ứng Điềm Xấu, đi vào làng là có Raid đấy. Cẩn thận!");
            }
        }
    }

    private void processIncomingGrpcCommands(SteveEntity steve, ActionExecutor executor) {
        GrpcAiClient grpcClient = GrpcAiClient.getInstance();
        while (grpcClient.hasCommands()) {
            AiCommand cmd = grpcClient.pollCommand();
            if (cmd != null) {
                try {
                    handleAiCommand(steve, executor, cmd);
                } catch (Exception e) {
                    SteveMod.LOGGER.error("Failed to handle gRPC AiCommand: " + cmd.getCommandType(), e);
                    this.lastCommandSuccess = false;
                    this.lastCommandError = e.getMessage();
                }
            }
        }
    }

    private void handleAiCommand(SteveEntity steve, ActionExecutor executor, AiCommand cmd) {
        String type = cmd.getCommandType();
        String payload = cmd.getPayloadJson();
        Map<String, Object> params = GSON.fromJson(payload, new TypeToken<Map<String, Object>>(){}.getType());

        this.lastCommandId = cmd.getCommandId();
        this.lastCommandType = type;
        this.lastCommandSuccess = true;
        this.lastCommandError = "";
        this.lastActionStartTime = System.currentTimeMillis();

        SteveMod.LOGGER.info("[Voyager-Bridge] Mapping command {} with params {}", type, payload);

        Task javaTask = null;
        switch (type) {
            case "MOVE_TO_BLOCK":
                javaTask = new Task("pathfind", params);
                break;
            case "MINE_BLOCK":
                javaTask = new Task("mine", params);
                break;
            case "PLACE_BLOCK":
                javaTask = new Task("place", params);
                break;
            case "CRAFT":
                javaTask = new Task("craft", params);
                break;
            case "SMELT":
                javaTask = new Task("smelt", params);
                break;
            case "FARM":
                javaTask = new Task("farm", params);
                break;
            case "ATTACK":
                javaTask = new Task("attack", params);
                break;
            case "GOTO_WAYPOINT":
                javaTask = new Task("waypoint", Map.of("action", "goto", "label", params.getOrDefault("label", "home")));
                break;
            default:
                SteveMod.LOGGER.warn("[Voyager-Bridge] Unknown command type: " + type);
                this.lastCommandSuccess = false;
                this.lastCommandError = "Unknown command type";
                return;
        }

        if (javaTask != null) {
            executor.stopCurrentAction();
            executor.enqueue(javaTask);
        }
    }
    /**
     * Tự động đi ngủ nếu trời tối và đang ở nơi an toàn.
     */
    private void checkSleepNeeds(SteveEntity steve, ActionExecutor executor) {
        if (steve.level().isDay()) return;
        
        // Chỉ ngủ nếu đang ở trong nhà hoặc đã xây shelter
        boolean atHome = steve.getMemory().getWaypoints().get("home")
            .map(p -> steve.blockPosition().distSqr(p) < 400).orElse(false);
            
        if (atHome || isInShelter) {
            // Kiểm tra xem có giường trong túi đồ hoặc quanh đây không
            var inv = steve.getMemory().getInventory();
            boolean hasBed = inv.hasItem(net.minecraft.world.item.Items.WHITE_BED, 1) ||
                           inv.hasItem(net.minecraft.world.item.Items.RED_BED, 1) ||
                           inv.hasItem(net.minecraft.world.item.Items.BLUE_BED, 1);
            
            if (hasBed) {
                if (!executor.isExecuting()) {
                    steve.sendChatMessage("Đến giờ đi ngủ rồi, nạp lại năng lượng thôi!");
                    executor.enqueue(new com.steve.ai.action.Task("sleep", java.util.Map.of()));
                }
            }
        }
    }
    /**
     * Tự động đi khai thác khoáng sản nếu thiếu tài nguyên cơ bản.
     */
    private void checkMiningNeeds(SteveEntity steve, ActionExecutor executor) {
        if (executor.isExecuting()) return;
        
        var inv = steve.getMemory().getInventory();
        int ironCount = inv.countItem(net.minecraft.world.item.Items.IRON_INGOT) + 
                        inv.countItem(net.minecraft.world.item.Items.IRON_ORE) + 
                        inv.countItem(net.minecraft.world.item.Items.DEEPSLATE_IRON_ORE);
        int diamondCount = inv.countItem(net.minecraft.world.item.Items.DIAMOND) + 
                           inv.countItem(net.minecraft.world.item.Items.DIAMOND_ORE) + 
                           inv.countItem(net.minecraft.world.item.Items.DEEPSLATE_DIAMOND_ORE);

        boolean hasIronPick = inv.hasItem(net.minecraft.world.item.Items.IRON_PICKAXE, 1) || 
                             inv.hasItem(net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1) ||
                             inv.hasItem(net.minecraft.world.item.Items.NETHERITE_PICKAXE, 1);

        if (hasIronPick && (ironCount < 10 || diamondCount < 3)) {
            steve.sendChatMessage("Sắp hết sắt và kim cương rồi, phải đi đào hầm thôi!");
            executor.enqueue(new com.steve.ai.action.Task("smart_strip_mine", java.util.Map.of(
                "targetY", -58,
                "discardJunk", "true"
            )));
        }
    }

    /**
     * Tự động về nhà cất đồ nếu túi đồ quá đầy rác.
     */
    private void checkStorageNeeds(SteveEntity steve, ActionExecutor executor) {
        if (executor.isExecuting()) return;
        
        var inv = steve.getMemory().getInventory();
        if (inv.size() > 30) {
            // Kiểm tra xem có đang ở gần nhà không
            net.minecraft.core.BlockPos home = steve.getMemory().getWaypoints().get("home").orElse(null);
            if (home != null && steve.blockPosition().distSqr(home) < 2500) { // Trong vòng 50 block
                steve.sendChatMessage("Túi đồ nặng quá, về cất bớt đồ vào rương đây!");
                executor.enqueue(new com.steve.ai.action.Task("chest", java.util.Map.of("mode", "store")));
            }
        }
    }

    /**
     * Tự động thắp sáng nếu độ sáng thấp tại căn cứ.
     */
    private void checkIllumination(SteveEntity steve, ActionExecutor executor) {
        if (executor.isExecuting()) return;
        
        // Chỉ thắp sáng nếu ở gần nhà (Home)
        boolean atHome = steve.getMemory().getWaypoints().get("home")
            .map(p -> steve.blockPosition().distSqr(p) < 1600).orElse(false);
            
        if (atHome) {
            int brightness = steve.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, steve.blockPosition());
            if (brightness < 7) {
                var inv = steve.getMemory().getInventory();
                if (inv.hasItem(net.minecraft.world.item.Items.TORCH, 1)) {
                    steve.sendChatMessage("Tối quá, phải thắp sáng khu vực này thôi!");
                    executor.enqueue(new com.steve.ai.action.Task("place", java.util.Map.of(
                        "block", "torch",
                        "position", "current"
                    )));
                }
            }
        }
    }
}
