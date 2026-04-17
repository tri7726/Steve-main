package com.steve.ai.personality;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.level.Level;
import java.util.Random;

/**
 * DialogueEngine: Cung cấp các câu thoại linh hoạt dựa trên ngữ cảnh thế giới.
 * Giúp Steve cảm thấy "người" hơn.
 */
@SuppressWarnings("null")
public class DialogueEngine {
    private static final Random random = new Random();

    private static final String[] DAYTIME_MSGS = {
        "Chào buổi sáng! Một ngày tuyệt vời để đi thám hiểm.",
        "Trời hôm nay đẹp nhở?",
        "Hy vọng hôm nay không gặp con Creeper nào.",
        "Nắng ấm thật đấy.",
        "Đi dạo một vòng xem có gì hay không nào."
    };

    private static final String[] NIGHT_MSGS = {
        "Trời tối rồi, bắt đầu thấy rợn người đấy.",
        "Cẩn thận kẻo quái vật xuất hiện.",
        "Ghé về nhà hoặc xây tạm chỗ trú thôi.",
        "Đêm ở đây tối quá, không thắp đuốc là xong đời.",
        "Ai đó vừa nghe tiếng Skeleton phải không?"
    };

    private static final String[] RAIN_MSGS = {
        "Mưa rồi... ướt hết cả đồ.",
        "Trời buồn như ly rượu cạn, mưa trôi hết công sức của tao.",
        "Mưa thế này thì chỉ muốn ở nhà ngủ thôi.",
        "Thôi xong, tí nữa chắc có sấm sét đấy."
    };

    private static final String[] DANGER_MSGS = {
        "Nhiều quái quá, chạy thôi anh em!",
        "Chỗ này không ổn tí nào, chuồn lẹ!",
        "Á à, định úp sọt tao à? Mơ đi con.",
        "Tình hình căng thẳng đây, phải cẩn thận.",
        "Chuẩn bị chiến đấu thôi!"
    };

    /**
     * Trả về một câu thoại ngẫu nhiên dựa trên trạng thái thế giới.
     */
    public static String getRandomObservation(SteveEntity steve) {
        Level level = steve.level();
        
        // Ưu tiên theo độ khẩn cấp
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, 
                steve.getBoundingBox().inflate(10.0)).isEmpty()) {
            return DANGER_MSGS[random.nextInt(DANGER_MSGS.length)];
        }

        if (level.isRaining()) {
            return RAIN_MSGS[random.nextInt(RAIN_MSGS.length)];
        }

        if (level.isNight()) {
            return NIGHT_MSGS[random.nextInt(NIGHT_MSGS.length)];
        }

        return DAYTIME_MSGS[random.nextInt(DAYTIME_MSGS.length)];
    }

    /**
     * Một số câu bình luận về trạng thái bản thân.
     */
    public static String getStatusComment(SteveEntity steve) {
        if (steve.getHealth() < 10) return "Máu giấy quá, phải tìm chỗ hồi phục thôi.";
        if (steve.getSteveHunger() < 10) return "Cái bụng bắt đầu biểu tình rồi.";
        if (steve.getMemory().getInventory().isFull()) return "Túi đồ đầy rồi, phải lọc bớt rác thôi.";
        return null;
    }
}
