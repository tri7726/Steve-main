package com.steve.ai.config;

/**
 * AutonomyLevel: mức độ tự chủ của Steve.
 *
 * <p>Có 4 mức, được đọc từ SteveConfig.AUTONOMY_LEVEL:
 * <ul>
 *   <li><b>PASSIVE</b>: Chỉ làm khi được ra lệnh. Không tự defend, không tự ăn.</li>
 *   <li><b>REACTIVE</b> (default): Tự phản ứng với nguy hiểm — defend, escape hazard, ăn khi đói.</li>
 *   <li><b>PROACTIVE</b>: REACTIVE + tự về nhà ban đêm, cảnh báo tool, gợi ý với player.</li>
 *   <li><b>AUTONOMOUS</b>: PROACTIVE + tự lên kế hoạch survival khi idle — kiếm ăn, kiếm tài nguyên.</li>
 * </ul>
 */
public enum AutonomyLevel {

    /** Chỉ phản ứng khi được ra lệnh trực tiếp. */
    PASSIVE,

    /** (Default) Tự defend, escape hazard, ăn khi đói. */
    REACTIVE,

    /** REACTIVE + về nhà ban đêm + cảnh báo tool + gợi ý. */
    PROACTIVE,

    /** PROACTIVE + tự sinh tồn hoàn toàn khi idle. */
    AUTONOMOUS;

    /**
     * Parse từ String (case-insensitive), fallback về REACTIVE nếu không hợp lệ.
     */
    public static AutonomyLevel fromString(String s) {
        if (s == null) return REACTIVE;
        try {
            return valueOf(s.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return REACTIVE;
        }
    }

    /** Kiểm tra level này có đủ tự chủ để thực hiện các survival routine chủ động không */
    public boolean isAtLeast(AutonomyLevel required) {
        return this.ordinal() >= required.ordinal();
    }
}
