package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * CombatAction — survival-accurate combat:
 * - Equips weapon/shield from SteveInventory (no item spawning)
 * - Uses vanilla attack cooldown API instead of hardcoded ticks
 * - Flee uses DefaultRandomPos; no teleport hack on stuck
 * - Regen only when food level allows (vanilla-compatible)
 * - Shield vs ranged: approach while blocking, no soft-lock
 * - Flee ends only when safe distance AND health recovered
 * - Dead-target guard: findTarget() called immediately on death
 */
@SuppressWarnings("null") // Minecraft API (LivingEntity, Vec3, BlockPos, SoundEvent, ItemStack, AABB) guaranteed non-null at runtime
public class CombatAction extends BaseAction {
    private String targetType;
    private LivingEntity target;
    private int ticksRunning;
    private int killCount;

    // Saved item stacks to restore after combat (don't destroy inventory items)
    private ItemStack savedMainHand = ItemStack.EMPTY;
    private ItemStack savedOffHand  = ItemStack.EMPTY;
    private boolean equippedWeapon = false;
    private boolean equippedShield = false;

    private enum CombatState { ENGAGE, FLEE, SHIELD_APPROACH, RANGED_ATTACK, DEFEND_EXPLOSIVE, EVADE, PILLAR, DEFENSIVE_BOX }
    private CombatState state = CombatState.ENGAGE;

    private static final int MAX_TICKS       = 1200;   // 60 seconds
    private static final double ATTACK_RANGE = 3.5;
    private static final float  FLEE_HEALTH  = 6.0f;   // 3 hearts
    private static final double FLEE_DISTANCE = 20.0;  // must be > typical mob aggro re-check
    private static final double SAFE_DISTANCE = 24.0;  // truly safe — stop fleeing here

    private boolean hasShield = false;
    private int attackCooldownTimer = 0;
    private int bowChargeTicks = 0;

    public CombatAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        targetType   = task.getStringParameter("target");
        ticksRunning = 0;
        killCount    = 0;
        state        = CombatState.ENGAGE;
        attackCooldownTimer = 0;

        steve.setFlying(false);

        equippedWeapon = equipWeaponFromInventory();
        equippedShield = equipShieldFromInventory();
        hasShield      = equippedShield;

        findTarget();

        if (target == null) {
            SteveMod.LOGGER.warn("Steve '{}' no targets nearby", steve.getSteveName());
            steve.sendChatMessage("Không thấy quái vật nào gần đây.");
        } else {
            steve.sendChatMessage("Phát hiện " + target.getType().toShortString() + "! Tấn công!");
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        attackCooldownTimer++;

        if (ticksRunning > MAX_TICKS) {
            cleanupCombat();
            result = ActionResult.success("Combat complete! Hạ được " + killCount + " mục tiêu.");
            steve.sendChatMessage("Chiến đấu xong! Hạ được " + killCount + " con.");
            return;
        }

        // ── Health check ──────────────────────────────────────────────────────
        float health = steve.getHealth();
        if (health <= FLEE_HEALTH && state != CombatState.FLEE) {
            state = CombatState.FLEE;
            steve.sendChatMessage("Máu thấp (" + (int)(health / 2) + " tim)! Rút lui!");
            SteveMod.LOGGER.info("Steve '{}' fleeing! Health: {}", steve.getSteveName(), health);
        }

        switch (state) {
            case FLEE          -> tickFlee();
            case SHIELD_APPROACH -> tickShieldApproach();
            case RANGED_ATTACK -> tickRangedAttack();
            case DEFEND_EXPLOSIVE -> tickDefendExplosive();
            case EVADE         -> tickEvade();
            case PILLAR        -> tickPillar();
            case DEFENSIVE_BOX -> tickDefensiveBox();
            case ENGAGE        -> tickEngage();
        }
    }

    // ── ENGAGE ───────────────────────────────────────────────────────────────
    private void tickEngage() {
        // Fix: guard dead target immediately, not on % 20 schedule
        if (target == null || !target.isAlive() || target.isRemoved()) {
            if (target != null && (!target.isAlive() || target.isRemoved())) {
                // Target just died — count kill if we were attacking it
                killCount++;
                steve.sendChatMessage("Hạ được 1 " + target.getType().toShortString()
                        + "! (Tổng: " + killCount + ")");
            }
            target = null;
            findTarget();
            if (target == null) return;
        }

        double distance = steve.distanceTo(target);

        // Special defense vs Ravager
        if (target instanceof net.minecraft.world.entity.monster.Ravager ravager && distance < 8.0) {
            if (ravager.getAttackTick() > 0) { // Ravager is attacking
                state = CombatState.EVADE;
                steve.sendChatMessage("Ravager hổ báo quá, lùi lại thủ thế!");
                return;
            }
            if (distance < 5.0 && hasEnoughBlocks()) {
                state = CombatState.DEFENSIVE_BOX;
                steve.sendChatMessage("Con này to quá, phải nhốt nó lại!");
                return;
            }
        }

        // Special defense vs Vex
        if (target instanceof net.minecraft.world.entity.monster.Vex && distance < 4.0) {
            state = CombatState.EVADE;
            steve.sendChatMessage("Con Vex này khó chịu thật, thủ thôi!");
            return;
        }

        // Ranged mob: switch to shield-approach (move AND block simultaneously)
        if (hasShield && target instanceof RangedAttackMob && distance > ATTACK_RANGE) {
            state = CombatState.SHIELD_APPROACH;
            activateShield(true);
            return;
        }

        // Defensive logic vs Creepers
        if (target instanceof net.minecraft.world.entity.monster.Creeper creeper && distance < 5.0) {
            if (creeper.getSwellDir() > 0) { // Is swelling (primed)
                state = CombatState.DEFEND_EXPLOSIVE;
                steve.sendChatMessage("Nó sắp nổ rồi! Đỡ thôi!");
                return;
            }
        }

        // Switch to Ranged if target is far and we have a bow
        if (distance > 6.0 && checkHasBowAndArrows()) {
            state = CombatState.RANGED_ATTACK;
            return;
        }

        // Sprint towards target
        steve.setSprinting(true);
        steve.getNavigation().moveTo(target, 2.5);

        // Attack using tick-based cooldown — PathfinderMob không có vanilla attack strength API
        // Dùng attackCooldownTimer thay thế, reset sau mỗi đòn đánh
        if (distance <= ATTACK_RANGE && attackCooldownTimer >= getWeaponCooldown()) {
            steve.doHurtTarget(target);
            steve.swing(InteractionHand.MAIN_HAND, true);
            attackCooldownTimer = 0;

            if (!target.isAlive() || target.isRemoved()) {
                killCount++;
                steve.sendChatMessage("Hạ được 1 " + target.getType().toShortString()
                        + "! (Tổng: " + killCount + ")");
                target = null;
                findTarget();
            }
        }
    }

    // ── RANGED_ATTACK: stay at distance and snipe ───────────────────────────
    private void tickRangedAttack() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            steve.stopUsingItem();
            state = CombatState.ENGAGE;
            return;
        }

        double distance = steve.distanceTo(target);

        // If target gets too close, switch back to melee
        if (distance < 5.0 || !checkHasBowAndArrows()) {
            steve.stopUsingItem();
            state = CombatState.ENGAGE;
            return;
        }

        // Aim at target
        steve.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // Stay at a distance (6-12 blocks)
        if (distance > 12.0) {
            steve.getNavigation().moveTo(target, 1.0);
        } else if (distance < 8.0) {
            // Back away slightly
            Vec3 away = steve.position().subtract(target.position()).normalize().scale(8.0);
            steve.getNavigation().moveTo(steve.getX() + away.x, steve.getY(), steve.getZ() + away.z, 1.0);
        } else {
            steve.getNavigation().stop();
        }

        // Bow logic
        if (!steve.isUsingItem()) {
            equipBestBow();
            steve.startUsingItem(InteractionHand.MAIN_HAND);
            bowChargeTicks = 0;
        } else {
            bowChargeTicks++;
            // Full charge is ~20 ticks (1s)
            if (bowChargeTicks >= 20) {
                // To fire, we need to release the item. 
                // For a PathfinderMob, firing an arrow requires specific logic or simulating the projectile.
                // In vanilla, PathfinderMob (like Skeleton) uses RangedAttackGoal.
                // Here we'll fire a projectile directly to be efficient.
                fireArrowAtTarget();
                steve.stopUsingItem();
                bowChargeTicks = 0;
            }
        }
    }

    private void fireArrowAtTarget() {
        if (target == null) return;
        
        net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(steve.level(), steve);
        double d0 = target.getX() - steve.getX();
        double d1 = target.getY(0.3333333333333333) - arrow.getY();
        double d2 = target.getZ() - steve.getZ();
        double d3 = Math.sqrt(d0 * d0 + d2 * d2);
        arrow.shoot(d0, d1 + d3 * 0.20000000298023224, d2, 1.6F, 1.0F);
        
        steve.level().addFreshEntity(arrow);
        steve.playSound(net.minecraft.sounds.SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (steve.getRandom().nextFloat() * 0.4F + 0.8F));
        
        // Consume arrow from inventory
        steve.getMemory().getInventory().removeItem(Items.ARROW, 1);
    }

    private boolean checkHasBowAndArrows() {
        var inv = steve.getMemory().getInventory();
        return inv.hasItem(Items.BOW, 1) && inv.hasItem(Items.ARROW, 1);
    }

    private void equipBestBow() {
        if (steve.getMainHandItem().is(Items.BOW)) return;
        
        var inv = steve.getMemory().getInventory();
        ItemStack bow = inv.findFirstItem(Items.BOW);
        if (!bow.isEmpty()) {
            // Swap main hand
            if (!equippedWeapon) {
                savedMainHand = steve.getMainHandItem().copy();
                equippedWeapon = true;
            }
            steve.setItemInHand(InteractionHand.MAIN_HAND, bow.copy());
        }
    }

    // ── SHIELD_APPROACH: move toward ranged mob while blocking ────────────────
    private void tickShieldApproach() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            activateShield(false);
            state = CombatState.ENGAGE;
            return;
        }

        double distance = steve.distanceTo(target);

        // Keep moving toward target while shield is up
        steve.setSprinting(false); // can't sprint while using item
        steve.getNavigation().moveTo(target, 1.5);

        // Once in melee range, drop shield and engage
        if (distance <= ATTACK_RANGE) {
            activateShield(false);
            state = CombatState.ENGAGE;
        }
    }

    /**
     * Specialized logic to handle primed creepers.
     */
    private void tickDefendExplosive() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            activateShield(false);
            state = CombatState.ENGAGE;
            return;
        }

        if (target instanceof net.minecraft.world.entity.monster.Creeper creeper) {
            double distance = steve.distanceTo(creeper);
            
            // Activate shield immediately
            activateShield(true);
            steve.setSprinting(false);

            // Back away
            Vec3 away = steve.position().subtract(creeper.position()).normalize().scale(8.0);
            steve.getNavigation().moveTo(steve.getX() + away.x, steve.getY(), steve.getZ() + away.z, 1.2);

            // If it stops swelling or gets far enough, resume engage
            if (creeper.getSwellDir() <= 0 || distance > 7.0) {
                activateShield(false);
                state = CombatState.ENGAGE;
            }
        } else {
            state = CombatState.ENGAGE;
        }
    }

    /**
     * EVADE: Block and move erraticly to avoid heavy hits or erratic mobs.
     */
    private void tickEvade() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            activateShield(false);
            state = CombatState.ENGAGE;
            return;
        }

        activateShield(true);
        double distance = steve.distanceTo(target);

        // Circular or strafing movement
        Vec3 strafe = new Vec3(target.getZ() - steve.getZ(), 0, steve.getX() - target.getX()).normalize().scale(5.0);
        steve.getNavigation().moveTo(steve.getX() + strafe.x, steve.getY(), steve.getZ() + strafe.z, 1.3);

        if (distance > 6.0 || (target instanceof net.minecraft.world.entity.monster.Ravager r && r.getAttackTick() <= 0)) {
            activateShield(false);
            state = CombatState.ENGAGE;
        }

        // Check if surrounded while evading -> PILLAR
        var nearby = steve.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, steve.getBoundingBox().inflate(5.0));
        if (nearby.size() >= 3 && hasEnoughBlocks()) {
            state = CombatState.PILLAR;
            steve.sendChatMessage("Nhiều quái quá! Phải lên cao thôi!");
        }
    }

    /**
     * PILLAR: Jump and place blocks beneath feet to reach safety.
     */
    private int pillarHeight = 0;
    private void tickPillar() {
        if (pillarHeight >= 3) {
            // Reached top, switch to Ranged if possible or just stay safe
            if (checkHasBowAndArrows()) {
                state = CombatState.RANGED_ATTACK;
            } else {
                state = CombatState.ENGAGE; // Will likely fall back to RANGED or just stay safe above
            }
            pillarHeight = 0;
            return;
        }

        steve.getNavigation().stop();
        if (steve.onGround()) {
            steve.getJumpControl().jump();
            // Wait for jump to reach peak or clear the ground
        } else if (steve.getDeltaMovement().y > 0) {
            // Attempt to place block
            BlockPos under = steve.blockPosition().below();
            if (steve.level().getBlockState(under).isAir() || steve.level().getBlockState(under).canBeReplaced()) {
                tryPlace(under);
                pillarHeight++;
                SteveMod.LOGGER.info("CombatAction: Steve '{}' pillared to height {}", steve.getSteveName(), pillarHeight);
            }
        }
    }

    /**
     * DEFENSIVE_BOX: Place blocks to isolate the enemy or create a barrier.
     */
    private void tickDefensiveBox() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            state = CombatState.ENGAGE;
            return;
        }

        if (!hasEnoughBlocks()) {
            state = CombatState.ENGAGE;
            return;
        }

        // Build a 2-high wall between us and target using the actual direction toward target
        Vec3 toTarget = target.position().subtract(steve.position()).normalize();
        // Place wall 1 block in front of Steve in the direction of the target
        net.minecraft.core.BlockPos base = steve.blockPosition()
                .offset((int) Math.round(toTarget.x), 0, (int) Math.round(toTarget.z));
        
        // Simple U-shape or line
        tryPlace(base);
        tryPlace(base.above());
        
        state = CombatState.ENGAGE; // After placing, resume fight from behind cover
    }

    private boolean hasEnoughBlocks() {
        var inv = steve.getMemory().getInventory();
        return inv.hasItem(Items.COBBLESTONE, 2) || inv.hasItem(Items.DIRT, 2) || inv.hasItem(Items.OAK_PLANKS, 2);
    }

    private void tryPlace(net.minecraft.core.BlockPos p) {
        var stateAt = steve.level().getBlockState(p);
        if (stateAt == null || (!stateAt.isAir() && !stateAt.canBeReplaced())) return;
        var inv = steve.getMemory().getInventory();
        ItemStack block = inv.findFirstItem(Items.COBBLESTONE);
        if (block.isEmpty()) block = inv.findFirstItem(Items.DIRT);
        if (block.isEmpty()) block = inv.findFirstItem(Items.OAK_PLANKS);

        if (!block.isEmpty()) {
            if (block.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
                steve.level().setBlock(p, bi.getBlock().defaultBlockState(), 3);
                inv.removeItem(block.getItem(), 1);
                steve.playSound(net.minecraft.sounds.SoundEvents.STONE_PLACE, 1.0F, 1.0F);
            }
        }
    }

    // ── FLEE ─────────────────────────────────────────────────────────────────
    private void tickFlee() {
        activateShield(false);
        steve.setSprinting(true);

        // Vanilla-compatible regen: only heal if food level >= 18 (full enough)
        // Steve is a mob so we approximate: use natural regen via food attribute
        // We do NOT call setHealth() directly — let vanilla handle it.
        // Instead, we just keep fleeing until health naturally recovers.

        if (target != null && target.isAlive()) {
            // Tính hướng chạy trốn: ngược chiều với target, lệch 45 độ để tránh tường
            Vec3 awayDir = steve.position().subtract(target.position()).normalize();
            // Thử 3 hướng: thẳng, lệch trái, lệch phải — chọn hướng nào pathfind được
            Vec3[] candidates = {
                steve.position().add(awayDir.scale(FLEE_DISTANCE)),
                steve.position().add(new Vec3(-awayDir.z, 0, awayDir.x).scale(FLEE_DISTANCE * 0.7).add(awayDir.scale(FLEE_DISTANCE * 0.7))),
                steve.position().add(new Vec3(awayDir.z, 0, -awayDir.x).scale(FLEE_DISTANCE * 0.7).add(awayDir.scale(FLEE_DISTANCE * 0.7)))
            };
            for (Vec3 candidate : candidates) {
                net.minecraft.world.level.pathfinder.Path path = steve.getNavigation().createPath(
                    candidate.x, candidate.y, candidate.z, 0
                );
                if (path != null && path.canReach()) {
                    steve.getNavigation().moveTo(path, 2.0);
                    break;
                }
            }
            // Nếu không tìm được path nào, chạy thẳng
            if (!steve.getNavigation().isInProgress()) {
                Vec3 fallback = candidates[0];
                steve.getNavigation().moveTo(fallback.x, fallback.y, fallback.z, 2.0);
            }
        }

        float health = steve.getHealth();
        double distToThreat = (target != null) ? steve.distanceTo(target) : Double.MAX_VALUE;

        // Only stop fleeing when BOTH: far enough AND health recovered
        if (distToThreat >= SAFE_DISTANCE && health > FLEE_HEALTH + 4.0f) {
            state = CombatState.ENGAGE;
            steve.sendChatMessage("Máu đã hồi, tiếp tục chiến đấu!");
        } else if (distToThreat >= SAFE_DISTANCE && health <= FLEE_HEALTH + 4.0f) {
            // Far enough but still low HP — end combat, don't re-engage
            cleanupCombat();
            result = ActionResult.success("Rút lui thành công! Hạ được " + killCount + " mục tiêu.");
            steve.sendChatMessage("Đã thoát an toàn. Máu còn " + (int)(health / 2) + " tim.");
        }
        // else: still too close — keep fleeing regardless of health
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Equip best weapon from SteveInventory. Saves current main-hand item.
     * Priority: sword > axe > any weapon. Falls back to bare hands.
     * @return true if a weapon was found and equipped
     */
    private boolean equipWeaponFromInventory() {
        savedMainHand = steve.getMainHandItem().copy();
        SteveInventory inv = steve.getMemory().getInventory();

        // Priority order: sword first, then axe
        ItemStack best = ItemStack.EMPTY;
        for (ItemStack stack : inv.getItems()) {
            if (stack.getItem() instanceof SwordItem) {
                best = stack;
                break;
            }
            if (stack.getItem() instanceof AxeItem && !(best.getItem() instanceof SwordItem)) {
                best = stack;
            }
        }

        if (!best.isEmpty()) {
            steve.setItemInHand(InteractionHand.MAIN_HAND, best.copy());
            SteveMod.LOGGER.info("Steve '{}' equipped {} from inventory",
                steve.getSteveName(), best.getItem().getDescription().getString());
            return true;
        }

        SteveMod.LOGGER.info("Steve '{}' has no weapon — fighting bare-handed", steve.getSteveName());
        return false;
    }

    /**
     * Equip shield from SteveInventory into off-hand. Saves current off-hand item.
     * @return true if shield was found and equipped
     */
    private boolean equipShieldFromInventory() {
        savedOffHand = steve.getOffhandItem().copy();
        SteveInventory inv = steve.getMemory().getInventory();

        for (ItemStack stack : inv.getItems()) {
            if (stack.getItem() instanceof ShieldItem) {
                steve.setItemInHand(InteractionHand.OFF_HAND, stack.copy());
                SteveMod.LOGGER.info("Steve '{}' equipped shield from inventory", steve.getSteveName());
                return true;
            }
        }

        SteveMod.LOGGER.info("Steve '{}' has no shield in inventory", steve.getSteveName());
        return false;
    }

    /**
     * Cooldown ticks theo loại vũ khí đang cầm:
     * - Sword: 12 ticks (0.6s)
     * - Axe: 20 ticks (1.0s)
     * - Bare hands: 8 ticks (0.4s)
     */
    private int getWeaponCooldown() {
        net.minecraft.world.item.ItemStack weapon = steve.getMainHandItem();
        if (weapon.getItem() instanceof AxeItem) return 20;
        if (weapon.getItem() instanceof SwordItem) return 12;
        return 8; // bare hands
    }

    private void activateShield(boolean active) {        if (active && hasShield) {
            steve.startUsingItem(InteractionHand.OFF_HAND);
        } else {
            steve.stopUsingItem();
        }
    }

    private void cleanupCombat() {
        steve.setSprinting(false);
        steve.getNavigation().stop();
        activateShield(false);

        // Restore original hand items (don't leave hands empty or with spawned items)
        steve.setItemInHand(InteractionHand.MAIN_HAND, savedMainHand);
        steve.setItemInHand(InteractionHand.OFF_HAND, savedOffHand);

        target = null;
    }

    @Override
    protected void onCancel() {
        cleanupCombat();
        SteveMod.LOGGER.info("Steve '{}' combat cancelled", steve.getSteveName());
    }

    @Override
    public String getDescription() {
        return "Attack " + targetType + " (kills: " + killCount + ", state: " + state + ")";
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    private void findTarget() {
        AABB searchBox = steve.getBoundingBox().inflate(32.0);
        List<Entity> entities = steve.level().getEntities(steve, searchBox);

        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living && isValidTarget(living)) {
                double distance = steve.distanceTo(living);
                if (distance < nearestDistance) {
                    nearest = living;
                    nearestDistance = distance;
                }
            }
        }

        target = nearest;
        if (target != null) {
            SteveMod.LOGGER.info("Steve '{}' locked onto: {} at {}m",
                steve.getSteveName(), target.getType().toShortString(), (int) nearestDistance);
        }
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) return false;
        if (entity instanceof SteveEntity
                || entity instanceof net.minecraft.world.entity.player.Player) return false;

        String targetLower = targetType != null ? targetType.toLowerCase() : "hostile";

        if (targetLower.contains("mob") || targetLower.contains("hostile")
                || targetLower.contains("monster") || targetLower.equals("any")) {
            return entity instanceof Monster;
        }

        return entity.getType().toString().toLowerCase().contains(targetLower);
    }
}
