package com.steve.ai.behavior;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * Quality of Life features.
 *
 * Nâng cấp:
 * - Auto-farm hỗ trợ nhiều loại cây (wheat, carrot, potato, beetroot)
 * - Guard patrol movement: Steve di chuyển tuần tra thay vì đứng yên
 * - Tool alert tự enqueue craft tool mới khi durability < 5%
 * - Auto-sort thông minh hơn: chỉ store khi có chest gần đó
 *
 * Gọi tick() mỗi 40 ticks từ SteveEntity.
 */
@SuppressWarnings("null")
public class QualityOfLifeManager {

    private static final float TOOL_WARN_THRESHOLD  = 0.10f;
    private static final float TOOL_CRAFT_THRESHOLD = 0.05f; // Tự craft khi < 5%
    private static final int   GUARD_PATROL_RADIUS  = 20;

    // Danh sách cây trồng hỗ trợ auto-farm
    private static final String[] FARM_CROPS = {"wheat", "carrot", "potato", "beetroot"};

    private boolean autoFarmEnabled   = false;
    private boolean nightGuardEnabled = false;
    private boolean autoSortEnabled   = true;

    private int toolWarnCooldown    = 0;
    private int farmLoopCooldown    = 0;
    private int guardPatrolCooldown = 0;
    private int sortCooldown        = 0;
    private int patrolIndex         = 0;   // index patrol waypoint hiện tại
    private boolean wasNight        = false;
    private boolean guardAlertSent  = false;
    private int currentCropIndex    = 0;   // xoay vòng qua các loại cây

    public void tick(SteveEntity steve, ActionExecutor executor) {
        if (steve.level().isClientSide) return;

        toolWarnCooldown    = Math.max(0, toolWarnCooldown - 40);
        farmLoopCooldown    = Math.max(0, farmLoopCooldown - 40);
        guardPatrolCooldown = Math.max(0, guardPatrolCooldown - 40);
        sortCooldown        = Math.max(0, sortCooldown - 40);

        long time    = steve.level().getDayTime() % 24000;
        boolean isNight = time >= 13000;

        checkToolDurability(steve, executor);

        if (autoSortEnabled && sortCooldown == 0) {
            checkAndSortInventory(steve, executor);
        }

        if (autoFarmEnabled && farmLoopCooldown == 0) {
            triggerFarmLoop(steve, executor);
        }

        if (nightGuardEnabled) {
            if (isNight && !wasNight) {
                steve.sendChatMessage("🛡 Bắt đầu canh gác ban đêm!");
                guardPatrolCooldown = 0;
                guardAlertSent = false;
                patrolIndex = 0;
            } else if (!isNight && wasNight) {
                steve.sendChatMessage("☀ Trời sáng rồi, thôi canh gác!");
                guardAlertSent = false;
            }
            if (isNight && guardPatrolCooldown == 0) {
                patrolGuard(steve, executor);
            }
        }

        wasNight = isNight;
    }

    // ── Tool Alert + Auto-craft ───────────────────────────────────────────────

    private void checkToolDurability(SteveEntity steve, ActionExecutor executor) {
        if (toolWarnCooldown > 0) return;

        ItemStack mainHand = steve.getMainHandItem();
        if (mainHand.isEmpty() || !mainHand.isDamageableItem()) return;

        float durability = 1.0f - (float) mainHand.getDamageValue() / mainHand.getMaxDamage();

        if (durability < TOOL_CRAFT_THRESHOLD) {
            // Cực thấp → tự craft tool mới
            String toolType = inferToolType(mainHand);
            if (toolType != null) {
                steve.sendChatMessage("🔨 " + mainHand.getItem().getDescription().getString()
                        + " sắp hỏng! Đang craft cái mới...");
                executor.enqueue(new Task("craft", Map.of("item", toolType, "quantity", 1)));
                toolWarnCooldown = 600; // 30 giây
            }
        } else if (durability < TOOL_WARN_THRESHOLD) {
            String toolName = mainHand.getItem().getDescription().getString();
            steve.sendChatMessage("⚠ " + toolName + " sắp hỏng! (" + (int)(durability * 100) + "% còn lại)");
            toolWarnCooldown = 400;
        }
    }

    /** Suy ra loại tool để craft dựa trên item hiện tại. */
    private String inferToolType(ItemStack stack) {
        var item = stack.getItem();
        if (item instanceof net.minecraft.world.item.PickaxeItem) return "iron_pickaxe";
        if (item instanceof net.minecraft.world.item.AxeItem)     return "iron_axe";
        if (item instanceof net.minecraft.world.item.ShovelItem)  return "iron_shovel";
        if (item instanceof net.minecraft.world.item.SwordItem)   return "iron_sword";
        if (item instanceof net.minecraft.world.item.HoeItem)     return "iron_hoe";
        return null;
    }

    // ── Auto-sort ─────────────────────────────────────────────────────────────

    private void checkAndSortInventory(SteveEntity steve, ActionExecutor executor) {
        SteveInventory inv = steve.getMemory().getInventory();
        if (inv.size() < 43) return; // < 80% đầy → bỏ qua

        // Kiểm tra có chest gần không (trong 16 block) trước khi enqueue
        boolean chestNearby = isChestNearby(steve, 16);
        if (!chestNearby) {
            sortCooldown = 20 * 60; // thử lại sau 1 phút
            return;
        }

        steve.sendChatMessage("📦 Inventory gần đầy, đang cất đồ vào rương...");
        executor.enqueue(new Task("chest", Map.of("action", "store")));
        sortCooldown = 20 * 60 * 2; // 2 phút
    }

    private boolean isChestNearby(SteveEntity steve, int radius) {
        net.minecraft.core.BlockPos pos = steve.blockPosition();
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                for (int dy = -3; dy <= 3; dy++) {
                    net.minecraft.world.level.block.Block b =
                        steve.level().getBlockState(pos.offset(dx, dy, dz)).getBlock();
                    if (b == net.minecraft.world.level.block.Blocks.CHEST
                     || b == net.minecraft.world.level.block.Blocks.TRAPPED_CHEST) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ── Auto-farm (multi-crop) ────────────────────────────────────────────────

    private void triggerFarmLoop(SteveEntity steve, ActionExecutor executor) {
        // Xoay vòng qua các loại cây
        String crop = FARM_CROPS[currentCropIndex % FARM_CROPS.length];
        currentCropIndex++;

        executor.enqueue(new Task("farm", Map.of("crop", crop, "action", "harvest")));
        executor.enqueue(new Task("farm", Map.of("crop", crop, "action", "plant")));
        farmLoopCooldown = 20 * 60 * 5; // 5 phút
        steve.sendChatMessage("🌾 Auto-farm: thu hoạch và trồng lại " + crop + "!");
    }

    // ── Night Guard (patrol) ──────────────────────────────────────────────────

    private void patrolGuard(SteveEntity steve, ActionExecutor executor) {
        AABB box = steve.getBoundingBox().inflate(GUARD_PATROL_RADIUS);
        List<net.minecraft.world.entity.Entity> entities = steve.level().getEntities(steve, box);
        boolean hasHostile = entities.stream().anyMatch(e -> e instanceof Monster);

        if (hasHostile) {
            if (!guardAlertSent) {
                steve.sendChatMessage("⚔ Phát hiện mob! Tiêu diệt!");
                guardAlertSent = true;
            }
            executor.enqueue(new Task("attack", Map.of("target", "hostile")));
            guardPatrolCooldown = 100;
        } else {
            guardAlertSent = false;
            // Patrol movement: di chuyển đến các điểm xung quanh base
            doPatrolMovement(steve, executor);
            guardPatrolCooldown = 300; // 15 giây giữa các bước patrol
        }
    }

    private void doPatrolMovement(SteveEntity steve, ActionExecutor executor) {
        net.minecraft.core.BlockPos base = steve.blockPosition();
        // 4 điểm patrol theo 4 hướng
        int[][] offsets = {{10, 0}, {0, 10}, {-10, 0}, {0, -10}};
        int[] offset = offsets[patrolIndex % 4];
        patrolIndex++;

        net.minecraft.core.BlockPos target = base.offset(offset[0], 0, offset[1]);
        executor.enqueue(new Task("pathfind", Map.of(
            "destination", target.getX() + " " + target.getY() + " " + target.getZ()
        )));
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setAutoFarm(boolean enabled, SteveEntity steve) {
        autoFarmEnabled = enabled;
        steve.sendChatMessage(enabled ? "🌾 Auto-farm đã bật! (wheat, carrot, potato, beetroot)" : "🌾 Auto-farm đã tắt.");
        if (enabled) farmLoopCooldown = 0;
    }

    public void setNightGuard(boolean enabled, SteveEntity steve) {
        nightGuardEnabled = enabled;
        guardAlertSent = false;
        patrolIndex = 0;
        steve.sendChatMessage(enabled ? "🛡 Night guard đã bật!" : "🛡 Night guard đã tắt.");
    }

    public void setAutoSort(boolean enabled, SteveEntity steve) {
        autoSortEnabled = enabled;
        steve.sendChatMessage(enabled ? "📦 Auto-sort đã bật!" : "📦 Auto-sort đã tắt.");
    }

    public boolean isAutoFarmEnabled()   { return autoFarmEnabled; }
    public boolean isNightGuardEnabled() { return nightGuardEnabled; }
}
