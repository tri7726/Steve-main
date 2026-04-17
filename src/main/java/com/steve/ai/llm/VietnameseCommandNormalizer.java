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
        // Mining
        PATTERNS.put(p("đào|đào bới|khai thác|lấy"), "mine");
        PATTERNS.put(p("sắt|quặng sắt"), "iron");
        PATTERNS.put(p("kim cương|diamond"), "diamond");
        PATTERNS.put(p("than|coal"), "coal");
        PATTERNS.put(p("vàng|gold"), "gold");
        PATTERNS.put(p("đồng|copper"), "copper");

        // Combat
        PATTERNS.put(p("giết|đánh|tiêu diệt|hạ|tấn công"), "attack");
        PATTERNS.put(p("zombie|quái|mob|con quái"), "hostile");
        PATTERNS.put(p("creeper"), "creeper");
        PATTERNS.put(p("skeleton|xương"), "skeleton");

        // Building
        PATTERNS.put(p("xây|xây dựng|làm|tạo"), "build");
        PATTERNS.put(p("nhà|căn nhà"), "house");
        PATTERNS.put(p("lâu đài|castle"), "castle");
        PATTERNS.put(p("tháp|tower"), "tower");

        // Farming
        PATTERNS.put(p("thu hoạch|gặt|hái"), "harvest");
        PATTERNS.put(p("trồng|gieo"), "plant");
        PATTERNS.put(p("lúa mì|wheat"), "wheat");
        PATTERNS.put(p("cà rốt|carrot"), "carrot");

        // Chest
        PATTERNS.put(p("cất đồ|bỏ vào rương"), "store chest");
        PATTERNS.put(p("lấy từ rương|mở rương"), "retrieve chest");

        // Sleep
        PATTERNS.put(p("ngủ|đi ngủ|nghỉ"), "sleep");

        // Follow
        PATTERNS.put(p("theo|đi theo|bám theo"), "follow me");

        // Fishing
        PATTERNS.put(p("câu cá|câu|đi câu"), "fish");

        // Waypoint — original
        PATTERNS.put(p("nhớ vị trí|đánh dấu"), "remember location");
        PATTERNS.put(p("quay về nhà"), "go home");
        PATTERNS.put(p("tìm làng"), "go to village");

        // Waypoint navigation (new)
        PATTERNS.put(p("về nhà đi|về nhà"), "go to waypoint home");
        PATTERNS.put(p("đến mỏ với tao|đến mỏ"), "go to waypoint mine");
        PATTERNS.put(p("lưu chỗ này|lưu vị trí này|lưu vị trí"), "save waypoint here");
        PATTERNS.put(p("đến làng"), "go to waypoint village");

        // Emotional commands (new)
        PATTERNS.put(p("nhanh lên|nhanh nào"), "hurry up");
        PATTERNS.put(p("dừng lại|dừng"), "stop");
        PATTERNS.put(p("cẩn thận"), "be careful");

        // Help / follow / stay (new)
        PATTERNS.put(p("giúp tao|giúp tôi"), "help me");
        PATTERNS.put(p("theo tao"), "follow me");
        PATTERNS.put(p("đứng yên"), "stay");

        // Relationship markers — strip prefix (new)
        // "bạn ơi" / "steve ơi" are stripped; "mày" kept as-is (informal address)
        PATTERNS.put(p("bạn ơi|steve ơi"), "");
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
                    hints.append(hint).append(" ");
                }
            }
        }

        if (hints.length() == 0) return command; // Không match gì, giữ nguyên

        String result = command + " [hint: " + hints.toString().trim() + "]";

        // Append mood hint if non-trivial mood detected
        PlayerMood mood = detectMood(command);
        if (mood != PlayerMood.CASUAL && mood != PlayerMood.NEUTRAL) {
            result = result + " [hint: mood=" + mood.name() + "]";
        }

        return result;
    }

    /**
     * Phát hiện tâm trạng người chơi dựa trên nội dung lệnh.
     */
    public static PlayerMood detectMood(String command) {
        if (command == null) return PlayerMood.CASUAL;

        long exclamationCount = command.chars().filter(c -> c == '!').count();
        if (exclamationCount >= 2) return PlayerMood.EXCITED;

        if (command.contains("giúp") || command.contains("cứu") || command.contains("nhanh")) {
            return PlayerMood.URGENT;
        }

        if (command.contains("cảm ơn") || command.contains("tốt lắm") || command.contains("giỏi")) {
            return PlayerMood.HAPPY;
        }

        return PlayerMood.CASUAL;
    }

    /**
     * Kiểm tra xem lệnh có phải chủ yếu tiếng Anh không.
     * Đơn giản: đếm ký tự ASCII vs non-ASCII.
     */
    private static boolean isMainlyEnglish(String text) {
        long ascii = text.chars().filter(c -> c < 128).count();
        return ascii > text.length() * 0.8;
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
