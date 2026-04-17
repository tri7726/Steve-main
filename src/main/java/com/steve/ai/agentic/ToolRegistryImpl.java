package com.steve.ai.agentic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link ToolRegistry}.
 *
 * <p>Uses a {@link LinkedHashMap} to preserve registration order.
 * The formatted description block is lazily built and cached; the cache
 * is invalidated whenever a new tool is registered.</p>
 */
public class ToolRegistryImpl implements ToolRegistry {

    private final LinkedHashMap<String, ToolDefinition> tools = new LinkedHashMap<>();
    private volatile String cachedBlock = null;

    // ── ToolRegistry ──────────────────────────────────────────────────────────

    @Override
    public synchronized void register(ToolDefinition tool) {
        tools.put(tool.name(), tool);
        cachedBlock = null; // invalidate cache
    }

    @Override
    public List<ToolDefinition> getAllTools() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    @Override
    public String buildToolDescriptionBlock() {
        String cached = cachedBlock;
        if (cached != null) return cached;

        synchronized (this) {
            if (cachedBlock != null) return cachedBlock;
            cachedBlock = buildBlock();
            return cachedBlock;
        }
    }

    @Override
    public boolean hasTool(String actionType) {
        return tools.containsKey(actionType);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildBlock() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ToolDefinition tool : tools.values()) {
            if (!first) sb.append("\n\n");
            first = false;

            sb.append(tool.name()).append(": ").append(tool.description());

            if (!tool.parameters().isEmpty()) {
                sb.append("\n  Params: ");
                boolean firstParam = true;
                for (Map.Entry<String, String> entry : tool.parameters().entrySet()) {
                    if (!firstParam) sb.append(", ");
                    firstParam = false;
                    sb.append(entry.getKey()).append("=").append(entry.getValue());
                }
            }

            if (!tool.examples().isEmpty()) {
                sb.append("\n  Examples: ");
                sb.append(String.join(" | ", tool.examples()));
            }
        }
        return sb.toString();
    }

    // ── Static factory ────────────────────────────────────────────────────────

    /**
     * Creates a {@link ToolRegistryImpl} pre-loaded with all 16 default action types.
     *
     * @return fully populated registry
     */
    public static ToolRegistryImpl createDefault() {
        ToolRegistryImpl registry = new ToolRegistryImpl();

        registry.register(new ToolDefinition(
            "mine",
            "Đào block trong thế giới",
            Map.of("block", "tên block cần đào", "quantity", "số lượng"),
            List.of("mine oak_log 32", "mine iron_ore 8")
        ));

        registry.register(new ToolDefinition(
            "smelt",
            "Nung chảy item trong lò",
            Map.of("item", "tên item cần nung", "quantity", "số lượng"),
            List.of("smelt iron_ore 8")
        ));

        registry.register(new ToolDefinition(
            "craft",
            "Chế tạo item",
            Map.of("item", "tên item cần craft", "quantity", "số lượng"),
            List.of("craft oak_planks 32", "craft crafting_table 1")
        ));

        registry.register(new ToolDefinition(
            "build",
            "Xây dựng cấu trúc",
            Map.of("structure", "loại cấu trúc", "location", "vị trí (optional)"),
            List.of("build house", "build wall")
        ));

        registry.register(new ToolDefinition(
            "attack",
            "Tấn công mob hoặc entity",
            Map.of("target", "tên mob/entity cần tấn công"),
            List.of("attack zombie", "attack skeleton")
        ));

        registry.register(new ToolDefinition(
            "follow",
            "Đi theo player",
            Map.of("player", "tên player (optional)"),
            List.of("follow", "follow Steve")
        ));

        registry.register(new ToolDefinition(
            "pathfind",
            "Di chuyển đến vị trí",
            Map.of("destination", "tên địa điểm hoặc tọa độ"),
            List.of("pathfind village", "pathfind 100 64 200")
        ));

        registry.register(new ToolDefinition(
            "farm",
            "Trồng và thu hoạch nông sản",
            Map.of("crop", "loại cây trồng", "action", "plant hoặc harvest"),
            List.of("farm wheat harvest", "farm carrot plant")
        ));

        registry.register(new ToolDefinition(
            "chest",
            "Tương tác với rương",
            Map.of("action", "open/store/take", "item", "tên item (optional)"),
            List.of("chest open", "chest store wood")
        ));

        registry.register(new ToolDefinition(
            "trade",
            "Giao dịch với villager",
            Map.of("item", "tên item muốn mua/bán"),
            List.of("trade emerald", "trade bread")
        ));

        registry.register(new ToolDefinition(
            "place",
            "Đặt block",
            Map.of("block", "tên block", "location", "vị trí đặt"),
            List.of("place torch", "place chest")
        ));

        registry.register(new ToolDefinition(
            "sleep",
            "Ngủ trong giường",
            Map.of(),
            List.of("sleep")
        ));

        registry.register(new ToolDefinition(
            "fish",
            "Câu cá",
            Map.of("duration", "thời gian câu (optional)"),
            List.of("fish", "fish 60")
        ));

        registry.register(new ToolDefinition(
            "brew",
            "Pha chế potion",
            Map.of("potion", "tên potion cần pha"),
            List.of("brew strength_potion")
        ));

        registry.register(new ToolDefinition(
            "waypoint",
            "Lưu hoặc di chuyển đến waypoint",
            Map.of("action", "save/goto", "label", "tên waypoint"),
            List.of("waypoint save home", "waypoint goto base")
        ));

        registry.register(new ToolDefinition(
            "gather",
            "Thu thập tài nguyên tự nhiên",
            Map.of("resource", "loại tài nguyên"),
            List.of("gather wood", "gather stone")
        ));

        registry.register(new ToolDefinition(
            "give",
            "Đưa vật phẩm cho người chơi khác (thả đồ)",
            Map.of("player", "tên người chơi (optional)", "item", "tên vật phẩm", "quantity", "số lượng"),
            List.of("give Steve diamond 5", "give bread 1")
        ));

        registry.register(new ToolDefinition(
            "feed",
            "Cho động vật ăn để nhân giống hoặc hồi máu",
            Map.of("animal", "loại động vật (cow, sheep, pig...)", "item", "tên thức ăn (optional)"),
            List.of("feed cow wheat", "feed pig")
        ));

        return registry;
    }
}
