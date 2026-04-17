package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.memory.SteveMemory;
import com.steve.ai.memory.SteveInventory;
import com.steve.ai.personality.*;
import com.steve.ai.personality.EmotionalContext;
import com.steve.ai.llm.AgentObservation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

@SuppressWarnings("null") // Minecraft API (EntityDataAccessor, Attribute, BlockPos, DamageSource, Vec3, AABB) guaranteed non-null at runtime
public class SteveEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> STEVE_NAME = 
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);

    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private int tickCounter = 0;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;
    private int selfDefenseCooldown = 0;

    // ── Simulated hunger (0-20, giống player) ───────────────────────────────────
    private int steveHunger = 20;           // Bắt đầu no
    private float steveSaturation = 5.0f;   // Saturation ban đầu
    private int hungerDrainTimer = 0;       // Timer để draining hunger
    private int experienceLevel = 0;        // NEW: Simulated experience for enchanting

    // Personality components
    private PersonalityManager personalityManager;
    private PersonalityProfile currentPersonality;
    private RelationshipTrackerImpl relationshipTracker;
    private SurvivalMonitorImpl survivalMonitor;
    private SurvivalScheduler survivalScheduler;       // NEW: tập trung survival behaviors
    private ProactiveSuggestionEngineImpl proactiveSuggestionEngine;
    private ComplexCommandPlannerImpl complexCommandPlanner;

    // ── Gameplay enhancement modules ─────────────────────────────────────────
    private final com.steve.ai.behavior.EnvironmentReactor environmentReactor = new com.steve.ai.behavior.EnvironmentReactor();
    private final com.steve.ai.behavior.CompanionBehavior companionBehavior = new com.steve.ai.behavior.CompanionBehavior();
    private final com.steve.ai.minigame.MiniGameManager miniGameManager = new com.steve.ai.minigame.MiniGameManager();
    private final com.steve.ai.behavior.QualityOfLifeManager qolManager = new com.steve.ai.behavior.QualityOfLifeManager();

    public SteveEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        // Initialize personality components
        this.personalityManager = new PersonalityManager();
        this.survivalMonitor = new SurvivalMonitorImpl();
        this.survivalScheduler = new SurvivalScheduler();
        this.proactiveSuggestionEngine = new ProactiveSuggestionEngineImpl();
        // RelationshipTracker và ComplexCommandPlanner được khởi tạo sau khi có personality
        this.setCustomNameVisible(true);
        
        this.isInvulnerable = true;
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 128.0D); // Đã tăng từ 48 lên 128 block
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(net.minecraft.world.level.Level level) {
        return new SteveNavigation(this, level); // Custom navigation với stuck-detection + waypoint chaining
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STEVE_NAME, "Steve");
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide) {
            tickCounter++;
            actionExecutor.tick();

            // ── Hunger drain: giảm hunger theo hoạt động ─────────────────────
            hungerDrainTimer++;
            int drainInterval = this.isSprinting() ? 150 : 300; // Sprint mau đói hơn
            if (hungerDrainTimer >= drainInterval) {
                hungerDrainTimer = 0;
                if (steveSaturation > 0) {
                    steveSaturation = Math.max(0, steveSaturation - 1f);
                } else if (steveHunger > 0) {
                    steveHunger--;
                }
            }

            // ── Auto-eat (mỗi 40 ticks) ─────────
            if (tickCounter % 40 == 0) {
                autoEatIfHungry();
            }
            
            // ── Survival behaviors (chạy mỗi tick để time-slice, dàn trải CPU) ─────────
            survivalScheduler.tick(this, actionExecutor);

            // ── Life-saving: MLG, Auto-Totem (mỗi 2 ticks) ──────────────────
            if (tickCounter % 2 == 0) {
                survivalScheduler.fastTick(this);
            }

            // ── Self-defense: tự chiến đấu khi bị tấn công (mỗi 10 ticks) ───
            if (tickCounter % 10 == 0) {
                autoDefendIfAttacked();
                pickupNearbyItems(2.5); // Tự động nhặt đồ rơi xung quanh (radius 2.5)
            }

            // ── Hazard avoidance: thoát khỏi lava/fire ngay lập tức ──────────
            if (tickCounter % 5 == 0) {
                autoEscapeHazard();
            }
            
            // Survival monitoring mỗi 200 ticks (10 giây) — tránh spam
            if (tickCounter % 200 == 0 && currentPersonality != null) {
                survivalMonitor.setCurrentTick(tickCounter);
                // Track exploration
                memory.getExplorationMemory().markExplored(this.blockPosition(), 32);
                AgentObservation obs = AgentObservation.capture(this, "tick");
                SurvivalState state = survivalMonitor.evaluate(obs);
                
                if (survivalMonitor.shouldInterruptCurrentTask(state)) {
                    survivalMonitor.getRecommendedAction(state).ifPresent(action -> {
                        String msg = currentPersonality.formatMessage(
                            action.chatMessage(),
                            getNearestPlayerRelationshipLevel()
                        );
                        sendChatMessageRaw(msg);
                        if (!action.tasks().isEmpty()) {
                            actionExecutor.clearQueue();
                            action.tasks().forEach(actionExecutor::enqueue);
                        }
                    });
                }
            }
            
            // Proactive suggestions mỗi 400 ticks (20 giây) — tránh spam
            if (tickCounter % 400 == 0 && currentPersonality != null && relationshipTracker != null) {
                proactiveSuggestionEngine.setCurrentTick(tickCounter);
                AgentObservation obs = AgentObservation.capture(this, "tick");
                String nearestPlayer = getNearestPlayerName();
                RelationshipLevel relLevel = relationshipTracker.getLevel(nearestPlayer);
                
                proactiveSuggestionEngine.checkTriggers(obs, relLevel).ifPresent(suggestion -> {
                    String msg = currentPersonality.formatMessage(
                        suggestion.chatMessage(), relLevel
                    );
                    sendChatMessageRaw(msg);
                    if (!suggestion.autoTasks().isEmpty()) {
                        suggestion.autoTasks().forEach(actionExecutor::enqueue);
                    }
                });
            }

            // ── Gameplay enhancement modules ─────────────────────────────────
            // Nhóm 1: Environment reactor (mỗi 20 ticks)
            if (tickCounter % 20 == 0) {
                environmentReactor.tick(this, actionExecutor);
            }
            // Nhóm 2: Companion behavior (mỗi 100 ticks)
            if (tickCounter % 100 == 0 && currentPersonality != null) {
                RelationshipLevel relLevel = (relationshipTracker != null)
                    ? relationshipTracker.getLevel(getNearestPlayerName())
                    : RelationshipLevel.STRANGER;
                companionBehavior.tick(this, actionExecutor, currentPersonality, relLevel);
            }
            // Nhóm 3: Mini-game tick (mỗi 20 ticks)
            if (tickCounter % 20 == 0) {
                miniGameManager.tick(this, actionExecutor);
            }
            // Nhóm 4: QoL manager (mỗi 40 ticks)
            if (tickCounter % 40 == 0) {
                qolManager.tick(this, actionExecutor);
            }
        }
    }

    public void setSteveName(String name) {
        this.steveName = name;
        this.entityData.set(STEVE_NAME, name);
        this.setCustomName(Component.literal(name));
        // Assign personality based on name
        this.currentPersonality = personalityManager.getOrCreate(name);
        // Initialize relationship tracker với personality và chat sender
        this.relationshipTracker = new RelationshipTrackerImpl(
            this.currentPersonality,
            msg -> this.sendChatMessageRaw(msg)
        );
        // Khởi tạo ComplexCommandPlanner sau khi có personality
        // Dùng lazy init để tránh crash khi spawn — TaskPlanner chỉ load khi cần
        if (this.complexCommandPlanner == null) {
            try {
                this.complexCommandPlanner = new ComplexCommandPlannerImpl(
                    this.actionExecutor.getTaskPlannerLazy()
                );
            } catch (Exception e) {
                // Không crash khi spawn — ComplexCommandPlanner là optional
                SteveMod.LOGGER.warn("Could not init ComplexCommandPlanner for '{}': {}", name, e.getMessage());
            }
        }
    }

    public String getSteveName() {
        return this.steveName;
    }

    public SteveMemory getMemory() {
        return this.memory;
    }

    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    // ── Gameplay module getters ───────────────────────────────────────────────
    public com.steve.ai.minigame.MiniGameManager getMiniGameManager() { return miniGameManager; }
    public com.steve.ai.behavior.QualityOfLifeManager getQolManager() { return qolManager; }
    public com.steve.ai.behavior.CompanionBehavior getCompanionBehavior() { return companionBehavior; }

    public int getExperienceLevel() {
        return this.experienceLevel;
    }

    public void addExperience(int amount) {
        this.experienceLevel += amount;
        SteveMod.LOGGER.debug("Steve '{}' gained {} levels, now {}", steveName, amount, experienceLevel);
    }

    public PersonalityProfile getPersonality() {
        return this.currentPersonality;
    }

@Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("SteveName", this.steveName);

        // Lưu hunger
        tag.putInt("SteveHunger", this.steveHunger);
        tag.putFloat("SteveSaturation", this.steveSaturation);
        tag.putInt("SteveXP", this.experienceLevel);

        CompoundTag memoryTag = new CompoundTag();
        this.memory.saveToNBT(memoryTag);
        tag.put("Memory", memoryTag);
        if (personalityManager != null) {
            CompoundTag personalityTag = new CompoundTag();
            personalityManager.saveToNBT(personalityTag);
            tag.put("Personality", personalityTag);
        }
        if (relationshipTracker != null) {
            CompoundTag relTag = new CompoundTag();
            relationshipTracker.saveToNBT(relTag);
            tag.put("Relationship", relTag);
        }
    }

@Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SteveName")) {
            this.setSteveName(tag.getString("SteveName"));
        }

        // Load hunger
        if (tag.contains("SteveHunger")) this.steveHunger = tag.getInt("SteveHunger");
        if (tag.contains("SteveSaturation")) this.steveSaturation = tag.getFloat("SteveSaturation");
        if (tag.contains("SteveXP")) this.experienceLevel = tag.getInt("SteveXP");

        if (tag.contains("Memory")) {
            this.memory.loadFromNBT(tag.getCompound("Memory"));
        }
        if (tag.contains("Personality") && personalityManager != null) {
            personalityManager.loadFromNBT(tag.getCompound("Personality"));
        }
        if (tag.contains("Relationship") && relationshipTracker != null) {
            relationshipTracker.loadFromNBT(tag.getCompound("Relationship"));
        }
    }



    public EmotionalContext detectEmotionalContext() {
        float hp = this.getHealth();
        if (hp < 6f) return EmotionalContext.SCARED;
        if (hp <= 10f) return EmotionalContext.TIRED;
        // Night + no torch nearby → SCARED
        if (!this.level().isDay()) {
            // Check if there's a torch within 5 blocks
            net.minecraft.core.BlockPos pos = this.blockPosition();
            boolean hasTorch = false;
            for (int dx = -5; dx <= 5 && !hasTorch; dx++) {
                for (int dz = -5; dz <= 5 && !hasTorch; dz++) {
                    net.minecraft.world.level.block.state.BlockState bs =
                        this.level().getBlockState(pos.offset(dx, 0, dz));
                    if (bs.getBlock() == net.minecraft.world.level.block.Blocks.TORCH
                        || bs.getBlock() == net.minecraft.world.level.block.Blocks.WALL_TORCH) {
                        hasTorch = true;
                    }
                }
            }
            if (!hasTorch) return EmotionalContext.SCARED;
        }
        // Check if last action was successful (look at recent actions)
        java.util.List<String> recent = this.memory.getRecentActions(1);
        if (!recent.isEmpty() && recent.get(0).toLowerCase().contains("success")) {
            return EmotionalContext.HAPPY;
        }
        return EmotionalContext.NEUTRAL;
    }

    public void sendChatMessage(String message) {
        if (this.level().isClientSide) return;
        String sanitized = MessageSanitizer.sanitize(message);
        // Format theo personality nếu có
        String formatted = (currentPersonality != null)
            ? currentPersonality.formatEmotionalMessage(sanitized, getNearestPlayerRelationshipLevel(), detectEmotionalContext())
            : sanitized;
        Component chatComponent = Component.literal("<" + this.steveName + "> " + formatted);
        this.level().players().forEach(player -> player.sendSystemMessage(chatComponent));
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.world.damagesource.DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
        // Sync túi đồ: khi Steve chết, rớt toàn bộ item ra ngoài world
        com.steve.ai.memory.SteveInventory inv = this.getMemory().getInventory();
        if (inv != null) {
            for (net.minecraft.world.item.ItemStack stack : inv.getItems()) {
                if (!stack.isEmpty()) {
                    this.spawnAtLocation(stack);
                }
            }
            inv.clear(); // Xóa đồ trong túi
        }
    }

    public String getNearestPlayerName() {
        return this.level().players().stream()
            .min((a, b) -> Double.compare(a.distanceToSqr(this), b.distanceToSqr(this)))
            .map(p -> p.getName().getString())
            .orElse("unknown");
    }

    public RelationshipLevel getNearestPlayerRelationshipLevel() {
        if (relationshipTracker == null) return RelationshipLevel.STRANGER;
        return relationshipTracker.getLevel(getNearestPlayerName());
    }

    public PersonalityProfile getCurrentPersonality() {
        return currentPersonality;
    }

    public RelationshipTrackerImpl getRelationshipTracker() {
        return relationshipTracker;
    }

    public ComplexCommandPlannerImpl getComplexCommandPlanner() {
        return complexCommandPlanner;
    }

    /** Gửi tin nhắn raw (không format lại) */
    public void sendChatMessageRaw(String message) {
        if (this.level().isClientSide) return;
        String sanitized = MessageSanitizer.sanitize(message);
        Component chatComponent = Component.literal("<" + this.steveName + "> " + sanitized);
        this.level().players().forEach(player -> player.sendSystemMessage(chatComponent));
    }

    public void setFlying(boolean flying) {
        this.isFlying = flying;
        this.setNoGravity(flying);
        this.setInvulnerableBuilding(flying);
    }

/**
     * Tự ăn khi đói (hunger < 14) hoặc máu thấp (HP < 15).
     * Steve là mob nên dùng steveHunger để giả lập hunger của người chơi.
     * Ăn sẽ: phục hồi hunger + saturation + hồi một chút máu.
     */
    private void autoEatIfHungry() {
        boolean isHungry   = steveHunger < 14;               // Dưới 7 tim đói
        boolean isLowHp    = this.getHealth() < 15.0f;       // Dưới 7.5 tim máu
        if (!isHungry && !isLowHp) return;                   // No-op nếu no và khỏe

        SteveInventory inv = this.memory.getInventory();
        // Đồ ăn theo thứ tự ưu tiên (nhiều nutrition nhất trước)
        net.minecraft.world.item.Item[] foods = {
            net.minecraft.world.item.Items.COOKED_BEEF,
            net.minecraft.world.item.Items.COOKED_PORKCHOP,
            net.minecraft.world.item.Items.COOKED_CHICKEN,
            net.minecraft.world.item.Items.BREAD,
            net.minecraft.world.item.Items.COOKED_SALMON,
            net.minecraft.world.item.Items.APPLE,
            net.minecraft.world.item.Items.CARROT,
            net.minecraft.world.item.Items.POTATO,
        };

        for (net.minecraft.world.item.Item food : foods) {
            if (inv.hasItem(food, 1)) {
                inv.removeItem(food, 1);
                // Phục hồi hunger
                int nutritionGain = getFoodNutrition(food);
                float satGain     = getFoodSaturation(food);
                steveHunger       = Math.min(20, steveHunger + nutritionGain);
                steveSaturation   = Math.min(20, steveSaturation + satGain);
                // Hồi một ít máu nếu đang hunger >= 18 (vanilla: regen khi no)
                if (steveHunger >= 18) {
                    float heal = getFoodHealAmount(food);
                    this.setHealth(Math.min(this.getHealth() + heal, this.getMaxHealth()));
                }
                this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                if (isHungry) {
                    sendChatMessage("Đói quá! Ăn " + food.getDescription().getString() + " cái đã!");
                }
                SteveMod.LOGGER.debug("Steve '{}' ate {}, hunger now {}/20", steveName, food, steveHunger);
                return;
            }
        }
        // Không có đồ ăn khi đói quá → cảnh báo
        if (steveHunger <= 4 && tickCounter % 400 == 0) {
            sendChatMessage("Tao đói quá mà không có đồ ăn!");
        }
    }

private float getFoodHealAmount(net.minecraft.world.item.Item food) {
        if (food == net.minecraft.world.item.Items.COOKED_BEEF
                || food == net.minecraft.world.item.Items.COOKED_PORKCHOP) return 4f;
        if (food == net.minecraft.world.item.Items.COOKED_CHICKEN
                || food == net.minecraft.world.item.Items.BREAD) return 2f;
        if (food == net.minecraft.world.item.Items.COOKED_SALMON) return 3f;
        return 1f; // apple, carrot, potato
    }

    /** Số nutrition (hunger point) mỗi loại đồ ăn phục hồi */
    private int getFoodNutrition(net.minecraft.world.item.Item food) {
        if (food == net.minecraft.world.item.Items.COOKED_BEEF
                || food == net.minecraft.world.item.Items.COOKED_PORKCHOP) return 8;
        if (food == net.minecraft.world.item.Items.COOKED_CHICKEN) return 6;
        if (food == net.minecraft.world.item.Items.BREAD) return 5;
        if (food == net.minecraft.world.item.Items.COOKED_SALMON) return 6;
        if (food == net.minecraft.world.item.Items.APPLE
                || food == net.minecraft.world.item.Items.CARROT) return 4;
        return 3; // potato và các loại khác
    }

    /** Saturation phục hồi theo loại đồ ăn */
    private float getFoodSaturation(net.minecraft.world.item.Item food) {
        if (food == net.minecraft.world.item.Items.COOKED_BEEF
                || food == net.minecraft.world.item.Items.COOKED_PORKCHOP) return 12.8f;
        if (food == net.minecraft.world.item.Items.COOKED_CHICKEN) return 7.2f;
        if (food == net.minecraft.world.item.Items.BREAD) return 6.0f;
        if (food == net.minecraft.world.item.Items.COOKED_SALMON) return 9.6f;
        return 2.4f; // apple, carrot, potato
    }

    /** Getter và setter cho hunger (dùng bởi SurvivalScheduler, AgentObservation) */
    public int getSteveHunger()                       { return steveHunger; }
    public void setSteveHunger(int h)                 { steveHunger = Math.max(0, Math.min(20, h)); }
    public float getSteveSaturation()                 { return steveSaturation; }
    public void setSteveSaturation(float s)           { steveSaturation = Math.max(0, Math.min(20, s)); }

    /**
     * Tự bảo vệ khi bị mob tấn công trong vòng 8 block.
     * Chỉ kích hoạt khi không đang thực hiện task khác.
     */
    /**
     * Thoát khỏi lava/fire/cactus/magma ngay lập tức bằng cách tìm vị trí an toàn gần nhất.
     */
    private void autoEscapeHazard() {
        net.minecraft.world.level.block.Block standingOn =
            this.level().getBlockState(this.blockPosition().below()).getBlock();
        net.minecraft.world.level.block.Block inBlock =
            this.level().getBlockState(this.blockPosition()).getBlock();

        boolean inHazard = inBlock == net.minecraft.world.level.block.Blocks.LAVA
            || inBlock == net.minecraft.world.level.block.Blocks.FIRE
            || standingOn == net.minecraft.world.level.block.Blocks.MAGMA_BLOCK
            || standingOn == net.minecraft.world.level.block.Blocks.CACTUS
            || this.isOnFire();

        if (!inHazard) return;

        // Tìm vị trí an toàn trong bán kính 5 block
        net.minecraft.core.BlockPos safePos = findSafeEscapePos();
        if (safePos != null) {
            this.getNavigation().moveTo(safePos.getX(), safePos.getY(), safePos.getZ(), 2.5);
            sendChatMessage("Nguy hiem! Chay di!");
            SteveMod.LOGGER.warn("Steve '{}' escaping hazard at {}", steveName, this.blockPosition());
        }
    }

    private net.minecraft.core.BlockPos findSafeEscapePos() {
        net.minecraft.core.BlockPos center = this.blockPosition();
        java.util.Set<net.minecraft.world.level.block.Block> hazards = java.util.Set.of(
            net.minecraft.world.level.block.Blocks.LAVA,
            net.minecraft.world.level.block.Blocks.FIRE,
            net.minecraft.world.level.block.Blocks.MAGMA_BLOCK,
            net.minecraft.world.level.block.Blocks.CACTUS
        );

        for (int r = 1; r <= 5; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    net.minecraft.core.BlockPos candidate = center.offset(dx, 0, dz);
                    net.minecraft.world.level.block.state.BlockState below =
                        this.level().getBlockState(candidate.below());
                    net.minecraft.world.level.block.state.BlockState at =
                        this.level().getBlockState(candidate);
                    net.minecraft.world.level.block.state.BlockState above =
                        this.level().getBlockState(candidate.above());

                    if (below.isSolidRender(this.level(), candidate.below())
                            && !hazards.contains(below.getBlock())
                            && at.isAir()
                            && above.isAir()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Tự bảo vệ khi bị mob tấn công trong vòng 8 block.
     * Chỉ kích hoạt khi không đang thực hiện task khác.
     */
    private void autoDefendIfAttacked() {
        selfDefenseCooldown = Math.max(0, selfDefenseCooldown - 10);
        if (selfDefenseCooldown > 0) return;
        if (actionExecutor.isExecuting()) return; // Đang bận task khác

        net.minecraft.world.phys.AABB box = this.getBoundingBox().inflate(8.0);
        java.util.List<net.minecraft.world.entity.Entity> nearby =
            this.level().getEntities(this, box);

        for (net.minecraft.world.entity.Entity e : nearby) {
            if (e instanceof net.minecraft.world.entity.monster.Monster monster
                    && monster.isAlive()
                    && monster.getTarget() != null
                    && (monster.getTarget() == this
                        || monster.getTarget() instanceof Player)) {

                // Mob đang nhắm vào Steve hoặc player gần đó → tự chiến đấu
                com.steve.ai.action.Task combatTask = new com.steve.ai.action.Task(
                    "attack",
                    java.util.Map.of("target", "hostile")
                );
                actionExecutor.enqueue(combatTask);
                selfDefenseCooldown = 200; // Cooldown 10 giây trước khi check lại
                sendChatMessage("Co ke tan cong! Tao tu bao ve!");
                SteveMod.LOGGER.info("Steve '{}' auto-defending against {}", steveName,
                    monster.getType().toShortString());
                return;
            }
        }
    }

    /**
     * Hút tất cả ItemEntity trong bán kính radius block vào SteveInventory ảo.
     * Gọi sau khi destroyBlock để nhặt drop.
     */
    public void pickupNearbyItems(double radius) {
        if (this.level().isClientSide) return;
        net.minecraft.world.phys.AABB box = this.getBoundingBox().inflate(radius);
        java.util.List<net.minecraft.world.entity.item.ItemEntity> dropped =
            this.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box);
        SteveInventory inv = this.memory.getInventory();
        for (net.minecraft.world.entity.item.ItemEntity itemEntity : dropped) {
            // Chỉ nhặt nếu item đang sống, chưa bị xóa và KHÔNG có delay nhặt (tránh cướp đồ vừa drop)
            if (itemEntity.isAlive() && !itemEntity.isRemoved() && itemEntity.getItem().getCount() > 0) {
                
                // Kiểm tra pickup delay của item (như player)
                // Minecraft dùng field 'pickupDelay' nhưng qua obfuscation/accessor của Forge
                // Trong Forge 1.20.1: itemEntity.getPickUpDelay() hoặc check field trực tiếp nếu accessible
                // Ở đây ta dùng logic đơn giản vì Steve là AI 'hút' đồ.
                
                net.minecraft.world.item.ItemStack stack = itemEntity.getItem().copy();
                int originalCount = stack.getCount();
                inv.addItem(stack);
                
                int taken = originalCount - stack.getCount();
                if (taken > 0) {
                    // Cập nhật lại stack trong world hoặc discard nếu đã lấy hết
                    if (stack.isEmpty()) {
                        itemEntity.discard();
                    } else {
                        itemEntity.setItem(stack);
                    }
                    
                    SteveMod.LOGGER.debug("Steve '{}' picked up {}x {}",
                        steveName, taken, stack.getItem().getDescription().getString());
                }
            }
        }
    }

    public boolean isFlying() {
        return this.isFlying;
    }

    /**
     * Set invulnerability for building (immune to ALL damage: fire, lava, suffocation, fall, etc.)
     */
    public void setInvulnerableBuilding(boolean invulnerable) {
        this.isInvulnerable = invulnerable;
        this.setInvulnerable(invulnerable); // Minecraft's built-in invulnerability
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        // Khi đang invulnerable (building/flying mode) → chặn toàn bộ damage
        if (this.isInvulnerable) return false;
        return super.hurt(source, amount);
    }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        return this.isInvulnerable;
    }

    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (this.isFlying && !this.level().isClientSide) {
            double motionY = this.getDeltaMovement().y;
            
            if (this.getNavigation().isInProgress()) {
                super.travel(travelVector);
                
                // But add ability to move vertically freely
                if (Math.abs(motionY) < 0.1) {
                    // Small upward force to prevent falling
                    this.setDeltaMovement(this.getDeltaMovement().add(0, 0.05, 0));
                }
            } else {
                super.travel(travelVector);
            }
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier, net.minecraft.world.damagesource.DamageSource source) {
        // No fall damage when flying
        if (this.isFlying) {
            return false;
        }
        return super.causeFallDamage(distance, damageMultiplier, source);
    }
}

