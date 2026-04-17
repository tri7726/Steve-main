package com.steve.ai.personality;

/**
 * Utility class để sanitize tin nhắn chat trước khi gửi.
 */
public class MessageSanitizer {
    private MessageSanitizer() {} // utility class

    /**
     * Loại bỏ ký tự điều khiển Unicode (category Cc) khỏi chuỗi.
     * Yêu Cầu 8.1: Tin nhắn chat từ Steve được sanitize trước khi gửi.
     */
    public static String sanitize(String message) {
        if (message == null) return "";
        // Loại bỏ tất cả ký tự thuộc Unicode category Cc (control characters)
        return message.chars()
            .filter(c -> Character.getType(c) != Character.CONTROL)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }
}
