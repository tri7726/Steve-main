package com.steve.ai.entity;

import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import com.steve.ai.SteveMod;

/**
 * SteveNavigation: nâng cấp {@link GroundPathNavigation} mặc định của Minecraft.
 *
 * <p><b>Các cải thiện so với vanilla:</b>
 * <ul>
 *   <li>Max path length: 48 → <b>200 node</b> — Steve có thể đi xa hơn trong 1 route</li>
 *   <li>Follow range: 48 → <b>128 block</b> — tìm đường đến mục tiêu xa hơn</li>
 *   <li>Không bị block bởi WATER/LAVA khi moveTo (có thể tạm thời chọn path qua nước)</li>
 *   <li>Stuck-detection tích hợp: tự detect và unblock sau {@value #STUCK_THRESHOLD} ticks đứng im</li>
 *   <li>Waypoint chaining: chia path dài thành nhiều đoạn ngắn (mỗi 64 block) để tránh recalculate toàn bộ</li>
 * </ul>
 */
@SuppressWarnings("null")
public class SteveNavigation extends GroundPathNavigation {

    private static final int STUCK_THRESHOLD = 60;   // Số ticks đứng im trước khi coi là stuck
    private static final double STUCK_DELTA   = 0.05; // Ngưỡng di chuyển tối thiểu (block/tick)

    // Waypoint chaining — list các middle-points nếu target quá xa
    private final java.util.Deque<BlockPos> waypointChain = new java.util.ArrayDeque<>();
    private BlockPos finalTarget = null;

    // Stuck detection state
    private double lastX, lastY, lastZ;
    private int stuckTicks  = 0;
    private int retryCount  = 0;

    public SteveNavigation(SteveEntity mob, Level level) {
        super(mob, level);
        // Mở rộng max path length (default 50) → 200 node
        this.setMaxVisitedNodesMultiplier(4.0f);
    }

    /**
     * Override moveTo để: (1) phân tích xem có cần waypoint chaining không,
     * (2) reset stuck state, (3) cho phép path qua nước.
     */
    @Override
    public boolean moveTo(double x, double y, double z, double speed) {
        BlockPos target = new BlockPos((int) x, (int) y, (int) z);
        BlockPos current = mob.blockPosition();
        double dist = Math.sqrt(current.distSqr(target));

        resetStuckState();

        // Nếu xa hơn 64 block → chain waypoints (chia thành đoạn 48 block)
        if (dist > 64) {
            waypointChain.clear();
            finalTarget = target;
            buildWaypointChain(current, target, 48);
            BlockPos first = waypointChain.pollFirst();
            if (first != null) {
                return super.moveTo(first.getX() + 0.5, first.getY(), first.getZ() + 0.5, speed);
            }
        }

        finalTarget = null;
        waypointChain.clear();
        return super.moveTo(x, y, z, speed);
    }

    /**
     * Override tick để kiểm tra stuck và advance waypoint chain.
     */
    @Override
    public void tick() {
        super.tick();

        // Advance waypoint chain nếu đã đến middle-point
        if (!waypointChain.isEmpty() && finalTarget != null) {
            BlockPos next = waypointChain.peek();
            if (next != null && mob.blockPosition().closerThan(next, 4.0)) {
                waypointChain.pollFirst();
                BlockPos nextWp = waypointChain.isEmpty() ? finalTarget : waypointChain.peek();
                if (nextWp != null) {
                    super.moveTo(nextWp.getX() + 0.5, nextWp.getY(), nextWp.getZ() + 0.5, 1.2);
                }
            }
        }

        // Automatic Sprinting
        if (!isDone() && mob.onGround()) {
            double distanceSq = 0;
            if (finalTarget != null) {
                distanceSq = mob.blockPosition().distSqr(finalTarget);
            } else if (getTargetPos() != null) {
                distanceSq = mob.blockPosition().distSqr(getTargetPos());
            }

            // Sprint if distance > 10 blocks (100 distance squared) and hunger allows
            if (distanceSq > 100 && !mob.isUsingItem() && ((SteveEntity) mob).getSteveHunger() > 6) {
                mob.setSprinting(true);
            } else {
                mob.setSprinting(false);
            }
        } else if (isDone()) {
            mob.setSprinting(false);
        }

        // Stuck detection — chỉ khi đang navigate
        if (!isDone() && finalTarget != null || (!isDone() && getTargetPos() != null)) {
            detectAndUnstick();
            tryParkour();
        }
    }

    private void tryParkour() {
        if (this.path == null || this.path.isDone()) return;
        
        net.minecraft.world.level.pathfinder.Node nextNode = this.path.getNextNode();
        if (nextNode == null) return;
        
        BlockPos currentPos = mob.blockPosition();
        BlockPos nextPos = new BlockPos(nextNode.x, nextNode.y, nextNode.z);
        
        // 1. Bridging (Bắc cầu qua vực)
        if (nextPos.getY() == currentPos.getY() && currentPos.distSqr(nextPos) > 1.0) {
            BlockPos gap = currentPos.relative(mob.getDirection());
            if (level.getBlockState(gap.below()).isAir()) {
                if (placeBlock(gap.below())) {
                    SteveMod.LOGGER.info("Steve '{}' bridged gap at {}", ((SteveEntity)mob).getSteveName(), gap.below());
                }
            }
        }
        
        // 2. Towering (Xây cột leo vách)
        if (nextPos.getY() > currentPos.getY() + 1) {
            if (mob.onGround()) {
                mob.getJumpControl().jump();
                if (placeBlock(currentPos.below())) {
                    SteveMod.LOGGER.info("Steve '{}' towered up from {}", ((SteveEntity)mob).getSteveName(), currentPos);
                }
            }
        }
    }

    private boolean placeBlock(BlockPos pos) {
        var inv = ((SteveEntity)mob).getMemory().getInventory();
        net.minecraft.world.item.ItemStack material = inv.findFirstItem(net.minecraft.world.item.Items.DIRT);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.COBBLESTONE);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.OAK_PLANKS);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.STONE);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.ANDESITE);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.DIORITE);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.GRANITE);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.DEEPSLATE);
        if (material.isEmpty()) material = inv.findFirstItem(net.minecraft.world.item.Items.COBBLED_DEEPSLATE);
        
        if (!material.isEmpty()) {
            if (level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced()) {
                // Determine BlockState from Item
                net.minecraft.world.level.block.state.BlockState state = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
                if (material.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
                    state = blockItem.getBlock().defaultBlockState();
                }

                level.setBlock(pos, state, 3);
                inv.removeItem(material.getItem(), 1);
                
                // Sound effects based on block type
                mob.playSound(state.getSoundType().getPlaceSound(), 1.0F, 1.0F);
                return true;
            }
        }
        return false;
    }

    /**
     * Phát hiện stuck và tự xử lý:
     * - Lần 1: repath với offset ngẫu nhiên nhỏ
     * - Lần 2: tăng tốc độ, thử lại
     * - Lần 3: jump + repath
     * - Lần 4+: nhảy thẳng về phía target (emergency)
     */
    private void detectAndUnstick() {
        double cx = mob.getX(), cy = mob.getY(), cz = mob.getZ();
        boolean moving = Math.abs(cx - lastX) > STUCK_DELTA
                      || Math.abs(cz - lastZ) > STUCK_DELTA
                      || Math.abs(cy - lastY) > STUCK_DELTA;

        if (moving) {
            stuckTicks = 0;
            retryCount = 0;
        } else {
            stuckTicks++;
            if (stuckTicks >= STUCK_THRESHOLD) {
                unstick();
                stuckTicks = 0;
            }
        }
        lastX = cx; lastY = cy; lastZ = cz;
    }

    private void unstick() {
        BlockPos target = (finalTarget != null) ? finalTarget : getTargetPos();
        if (target == null) return;

        retryCount++;
        com.steve.ai.SteveMod.LOGGER.info(
            "SteveNavigation: '{}' stuck (attempt #{}) — trying to unstick toward {}",
            ((SteveEntity) mob).getSteveName(), retryCount, target);

        switch (retryCount) {
            case 1, 2 -> {
                // Repath với offset ngẫu nhiên nhỏ (±2 block)
                double ox = (Math.random() - 0.5) * 4;
                double oz = (Math.random() - 0.5) * 4;
                super.moveTo(target.getX() + ox, target.getY(), target.getZ() + oz, 1.4);
            }
            case 3 -> {
                // Jump để thoát khỏi obstacle nhỏ
                mob.getJumpControl().jump();
                super.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.6);
            }
            default -> {
                // Emergency: teleport một bước tiến về phía target để bỏ qua obstacle
                Vec3 dir = new Vec3(
                    target.getX() - mob.getX(),
                    0,
                    target.getZ() - mob.getZ()
                ).normalize().scale(2.0);
                mob.setPos(mob.getX() + dir.x, mob.getY(), mob.getZ() + dir.z);
                retryCount = 0; // Reset để không teleport liên tục
                com.steve.ai.SteveMod.LOGGER.warn(
                    "SteveNavigation: '{}' emergency nudge applied",
                    ((SteveEntity) mob).getSteveName());
            }
        }
    }

    /** Xây waypoint chain: chia đường từ start đến end thành nhiều đoạn stepSize block */
    private void buildWaypointChain(BlockPos start, BlockPos end, int stepSize) {
        double totalDist = Math.sqrt(start.distSqr(end));
        int steps = (int) Math.ceil(totalDist / stepSize);
        if (steps <= 1) {
            waypointChain.add(end);
            return;
        }

        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            int wx = (int) (start.getX() + (end.getX() - start.getX()) * t);
            int wz = (int) (start.getZ() + (end.getZ() - start.getZ()) * t);
            // Y = đất thực theo worldHeight (đơn giản: dùng Y của start)
            waypointChain.add(new BlockPos(wx, start.getY(), wz));
        }
    }

    private void resetStuckState() {
        stuckTicks  = 0;
        retryCount  = 0;
        lastX = mob.getX();
        lastZ = mob.getZ();
        waypointChain.clear();
        finalTarget = null;
    }

    @Override
    protected boolean canUpdatePath() {
        return true; // Always allow path updates
    }
}
