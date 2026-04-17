package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.MemoryPriority;
import com.steve.ai.memory.VectorStore;
import com.steve.ai.memory.WaypointMemory;
import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.Optional;

/**
 * WaypointAction: lưu/đến/liệt kê vị trí quan trọng.
 * action "save"  → lưu vị trí hiện tại với label
 * action "goto"  → pathfind đến waypoint đã lưu
 * action "list"  → liệt kê tất cả waypoint
 * action "delete"→ xóa waypoint
 */
@SuppressWarnings("null")
public class WaypointAction extends BaseAction {
    private String waypointAction;
    private String label;
    private int ticksRunning;
    private BlockPos targetPos;
    private static final int MAX_TICKS = 1200;

    public WaypointAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        // LLM có thể dùng "action" hoặc "mode" làm key
        waypointAction = task.getStringParameter("action");
        if (waypointAction == null) waypointAction = task.getStringParameter("mode");
        if (waypointAction == null) waypointAction = "list";

        // Normalize aliases
        waypointAction = switch (waypointAction.toLowerCase()) {
            case "save", "set", "luu", "mark" -> "save";
            case "goto", "go", "go_to", "teleport", "den", "đến" -> "goto";
            case "delete", "remove", "xoa", "xóa" -> "delete";
            default -> "list";
        };

        label = task.getStringParameter("label");
        if (label == null) label = task.getStringParameter("name");
        ticksRunning = 0;

        WaypointMemory wm = steve.getMemory().getWaypoints();

        switch (waypointAction.toLowerCase()) {
            case "save" -> {
                if (label == null || label.isBlank()) {
                    result = ActionResult.failure("Can label de luu waypoint!");
                    return;
                }
                BlockPos pos = steve.blockPosition();
                String dimension = steve.level().dimension().location().getPath();
                String biome = steve.level().getBiome(pos)
                        .unwrapKey()
                        .map(k -> k.location().getPath())
                        .orElse("unknown");
                wm.save(label, pos, dimension, biome);

                VectorStore vectorStore = steve.getMemory().getVectorStore();
                vectorStore.addMemory(
                        "[WAYPOINT] " + label + " at [" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "] in " + dimension + ", " + biome,
                        MemoryPriority.HIGH
                );

                result = ActionResult.success("Da luu vi tri '" + label + "' tai " + pos);
                steve.sendChatMessage("Da nho vi tri '" + label + "' roi!");
            }
            case "goto" -> {
                if (label == null || label.isBlank()) {
                    result = ActionResult.failure("Can label de di den waypoint!");
                    return;
                }
                Optional<BlockPos> pos = wm.get(label);
                if (pos.isEmpty()) {
                    result = ActionResult.failure("Khong biet vi tri '" + label + "'!");
                    steve.sendChatMessage("Tao chua biet vi tri '" + label + "' la o dau!");
                    return;
                }
                targetPos = pos.get();
                steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.2);
                steve.sendChatMessage("Dang di den '" + label + "'...");
            }
            case "delete" -> {
                if (label != null) wm.remove(label);
                result = ActionResult.success("Da xoa waypoint '" + label + "'");
            }
            default -> { // list
                result = ActionResult.success("Vi tri da biet: " + wm.getSummary());
                steve.sendChatMessage("Vi tri da biet: " + wm.getSummary());
            }
        }
    }

    @Override
    protected void onTick() {
        if (result != null) return; // goto đang chạy
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Timeout khi di den " + label);
            return;
        }

        if (targetPos != null && steve.blockPosition().closerThan(targetPos, 3.0)) {
            result = ActionResult.success("Da den '" + label + "'!");
            steve.sendChatMessage("Da den '" + label + "' roi!");
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Waypoint " + waypointAction + (label != null ? " " + label : "");
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return "goto".equals(waypointAction)
            ? EnumSet.of(ActionSlot.LOCOMOTION)
            : EnumSet.noneOf(ActionSlot.class);
    }
}
