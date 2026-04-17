package com.steve.ai.llm;

import com.steve.ai.personality.PlayerMood;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * VietnameseCommandNormalizer: chuẩn hóa lệnh tiếng Việt tự nhiên
 * thành format LLM dễ hiểu hơn.
 *
 * Không thay thế LLM — chỉ bổ sung context để LLM parse chính xác hơn.
 * Ví dụ: "đi đào sắt đi" → "mine iron ore"
 */
public class VietnameseCommandNormalizer {

    // Map từ khóa tiếng Việt → action hint tiếng Anh
    private static final Map<Pattern, String> PATTERNS = new LinkedHashMap<>();

    static {
        // ── Mining ────────────────────────────────────────────────────────────
        PATTERNS.put(p("đào|đào bới|khai thác|lấy|farm|grind"), "mine");
        PATTERNS.put(p("sắt|quặng sắt|fe"), "iron");
        PATTERNS.put(p("kim cương|diamond|dia"), "diamond");
        PATTERNS.put(p("than|coal"), "coal");
        PATTERNS.put(p("vàng|gold"), "gold");
        PATTERNS.put(p("đồng|copper"), "copper");
        PATTERNS.put(p("đá|stone|đá thường"), "stone");
        PATTERNS.put(p("cát|sand"), "sand");
        PATTERNS.put(p("đất|dirt"), "dirt");
        PATTERNS.put(p("đá cuội|cobble"), "cobblestone");
        PATTERNS.put(p("ngọc lục bảo|emerald"), "emerald");
        PATTERNS.put(p("lapis|đá xanh"), "lapis");
        PATTERNS.put(p("redstone|đá đỏ"), "redstone");

        // ── Wood / Nature ─────────────────────────────────────────────────────
        PATTERNS.put(p("gỗ|chặt cây|chặt gỗ|farm gỗ"), "mine oak_log");
        PATTERNS.put(p("lá cây|lá"), "mine oak_leaves");

        // ── Combat (slang VN gaming) ──────────────────────────────────────────
        PATTERNS.put(p("giết|đánh|tiêu diệt|hạ|tấn công|chém|nã|xử|phang|đập|kill"), "attack");
        PATTERNS.put(p("zombie|quái|mob|con quái|xác sống"), "hostile");
        PATTERNS.put(p("creeper|bom xanh|cái bom"), "creeper");
        PATTERNS.put(p("skeleton|xương|cung tên"), "skeleton");
        PATTERNS.put(p("spider|nhện"), "spider");
        PATTERNS.put(p("enderman|người que|ender"), "enderman");
        PATTERNS.put(p("con bò|bò"), "cow");
        PATTERNS.put(p("con heo|heo|lợn"), "pig");
        PATTERNS.put(p("con gà|gà"), "chicken");
        PATTERNS.put(p("con cừu|cừu"), "sheep");
        PATTERNS.put(p("tất cả|toàn bộ|hết"), "all");

        // ── Building ──────────────────────────────────────────────────────────
        PATTERNS.put(p("xây|xây dựng|làm|tạo|build"), "build");
        PATTERNS.put(p("nhà|căn nhà|house"), "house");
        PATTERNS.put(p("lâu đài|castle"), "castle");
        PATTERNS.put(p("tháp|tower"), "tower");
        PATTERNS.put(p("chuồng|barn"), "barn");
        PATTERNS.put(p("sàn|platform|nền"), "platform");
        PATTERNS.put(p("tường|wall"), "wall");

        // ── Crafting / Smelting ───────────────────────────────────────────────
        PATTERNS.put(p("craft|chế tạo|làm đồ|làm cái"), "craft");
        PATTERNS.put(p("cuốc gỗ|wooden pickaxe"), "wooden_pickaxe");
        PATTERNS.put(p("cuốc đá|stone pickaxe"), "stone_pickaxe");
        PATTERNS.put(p("cuốc sắt|iron pickaxe"), "iron_pickaxe");
        PATTERNS.put(p("kiếm gỗ|wooden sword"), "wooden_sword");
        PATTERNS.put(p("kiếm sắt|iron sword"), "iron_sword");
        PATTERNS.put(p("bàn craft|crafting table|bàn chế tạo"), "crafting_table");
        PATTERNS.put(p("lò|furnace|nung"), "smelt");
        PATTERNS.put(p("đuốc|torch"), "torch");

        // ── Farming ───────────────────────────────────────────────────────────
        PATTERNS.put(p("thu hoạch|gặt|hái"), "harvest");
        PATTERNS.put(p("trồng|gieo"), "plant");
        PATTERNS.put(p("lúa mì|wheat"), "wheat");
        PATTERNS.put(p("cà rốt|carrot"), "carrot");
        PATTERNS.put(p("khoai tây|potato"), "potato");
        PATTERNS.put(p("củ cải|beetroot"), "beetroot");
        PATTERNS.put(p("dưa hấu|melon"), "melon");
        PATTERNS.put(p("bí ngô|pumpkin"), "pumpkin");

        // ── Chest / Storage ───────────────────────────────────────────────────
        PATTERNS.put(p("cất đồ|bỏ vào rương|cất vào"), "store chest");
        PATTERNS.put(p("lấy từ rương|mở rương|lấy đồ"), "retrieve chest");

        // ── Sleep ─────────────────────────────────────────────────────────────
        PATTERNS.put(p("ngủ|đi ngủ|nghỉ|zzz"), "sleep");

        // ── Follow / Stay ─────────────────────────────────────────────────────
        PATTERNS.put(p("theo|đi theo|bám theo|follow|đi cùng tao"), "follow me");
        PATTERNS.put(p("đứng yên|đứng im|stop theo|thôi follow"), "stay");

        // ── Fishing ───────────────────────────────────────────────────────────
        PATTERNS.put(p("câu cá|câu|đi câu|fish"), "fish");

        // ── Trade ─────────────────────────────────────────────────────────────
        PATTERNS.put(p("mua|bán|trade|giao dịch|làng"), "trade village");

        // ── Waypoint ──────────────────────────────────────────────────────────
        PATTERNS.put(p("nhớ vị trí|đánh dấu|lưu chỗ này|lưu vị trí"), "save waypoint here");
        PATTERNS.put(p("về nhà|quay về nhà|home"), "go to waypoint home");
        PATTERNS.put(p("đến mỏ|đến hang"), "go to waypoint mine");
        PATTERNS.put(p("đến làng|tìm làng"), "go to waypoint village");

        // ── Stop / Cancel ─────────────────────────────────────────────────────
        PATTERNS.put(p("dừng lại|dừng|thôi|stop|cancel|hủy"), "stop");

        // ── Meta / Emotional (slang) ──────────────────────────────────────────
        PATTERNS.put(p("nhanh lên|nhanh nào|đi mau|mau lên"), "hurry up");
        PATTERNS.put(p("cẩn thận|coi chừng|né"), "be careful");
        PATTERNS.put(p("giúp tao|giúp tôi|cứu tao|sos"), "help me");

        // ── Relationship markers — strip (không add hint) ─────────────────────
        PATTERNS.put(p("bạn ơi|steve ơi|mày ơi|ê mày|ê"), "");
        PATTERNS.put(p("đi nào|đi thôi|đi mày"), "");
        PATTERNS.put(p("ok chưa|xong chưa|rồi không"), "");
    }

    /**
     * Chuẩn hóa lệnh: thêm English hint vào cuối nếu phát hiện tiếng Việt.
     * Giữ nguyên lệnh gốc để LLM vẫn hiểu context đầy đủ.
     */
    public static String normalize(String command) {
        if (command == null || command.isBlank()) return command;

        // Nếu lệnh đã là tiếng Anh thuần, không cần xử lý
        if (isMainlyEnglish(command)) return command;

        StringBuilder hints = new StringBuilder();
        String lower = command.toLowerCase();

        for (Map.Entry<Pattern, String> entry : PATTERNS.entrySet()) {
            if (entry.getKey().matcher(lower).find()) {
                String hint = entry.getValue();
                if (!hint.isBlank()) {
                    // Tránh thêm hint trùng lặp
                    String trimmedHint = hint.trim();
                    if (hints.indexOf(trimmedHint) < 0) {
                        hints.append(trimmedHint).append(" ");
                    }
                }
            }
        }

        if (hints.length() == 0) return command; // Không match gì, giữ nguyên

        String result = command + " [hint: " + hints.toString().trim() + "]";

        // Append mood hint nếu đáng kể
        PlayerMood mood = detectMood(command);
        if (mood != PlayerMood.CASUAL && mood != PlayerMood.NEUTRAL) {
            result = result + " [mood:" + mood.name() + "]";
        }

        return result;
    }

    /**
     * Phát hiện tâm trạng người chơi dựa trên nội dung lệnh.
     */
    public static PlayerMood detectMood(String command) {
        if (command == null) return PlayerMood.CASUAL;
        String lower = command.toLowerCase();

        // URGENT — các từ cấp bách
        if (lower.contains("cứu") || lower.contains("sos") || lower.contains("nhanh")
                || lower.contains("mau") || lower.contains("urgent") || lower.contains("cấp")) {
            return PlayerMood.URGENT;
        }

        // EXCITED — nhiều ! hoặc từ phấn khích
        long exclamationCount = command.chars().filter(c -> c == '!').count();
        if (exclamationCount >= 2 || lower.contains("đỉnh") || lower.contains("ngon")
                || lower.contains("pro") || lower.contains("đẹp quá") || lower.contains("tuyệt")) {
            return PlayerMood.EXCITED;
        }

        // HAPPY — khen ngợi
        if (lower.contains("cảm ơn") || lower.contains("tốt lắm") || lower.contains("giỏi")
                || lower.contains("ngoan") || lower.contains("ok ngon") || lower.contains("xịn")) {
            return PlayerMood.HAPPY;
        }

        // FRUSTRATED — chửi thề nhẹ hoặc thất vọng
        if (lower.contains("sao không") || lower.contains("ngu") || lower.contains("dốt")
                || lower.contains("lại rồi") || lower.contains("làm gì vậy")) {
            return PlayerMood.CASUAL;
        }

        return PlayerMood.CASUAL;
    }

    /**
     * Kiểm tra xem lệnh có phải chủ yếu tiếng Anh không.
     */
    private static boolean isMainlyEnglish(String text) {
        long ascii = text.chars().filter(c -> c < 128).count();
        return ascii > text.length() * 0.85;
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
