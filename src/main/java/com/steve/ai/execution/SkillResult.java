package com.steve.ai.execution;

import com.steve.ai.action.Task;
import java.util.List;

/**
 * Kết quả thực thi một skill — dùng cho Critic đánh giá và SkillLibrary ghi stats.
 */
public class SkillResult {
    private final boolean success;
    private final String message;
    private final List<Task> actions; // Tasks đã được enqueue
    private final long executionTimeMs;
    private final String errorDetail;  // Stack trace hoặc lý do fail chi tiết

    private SkillResult(Builder b) {
        this.success        = b.success;
        this.message        = b.message;
        this.actions        = b.actions != null ? b.actions : List.of();
        this.executionTimeMs = b.executionTimeMs;
        this.errorDetail    = b.errorDetail;
    }

    public boolean isSuccess()          { return success; }
    public String getMessage()          { return message; }
    public List<Task> getActions()      { return actions; }
    public long getExecutionTimeMs()    { return executionTimeMs; }
    public String getErrorDetail()      { return errorDetail; }

    public static SkillResult success(String message) {
        return new Builder().success(true).message(message).build();
    }

    public static SkillResult success(String message, List<Task> actions, long ms) {
        return new Builder().success(true).message(message).actions(actions).executionTimeMs(ms).build();
    }

    public static SkillResult failure(String message) {
        return new Builder().success(false).message(message).build();
    }

    public static SkillResult failure(String message, String errorDetail) {
        return new Builder().success(false).message(message).errorDetail(errorDetail).build();
    }

    @Override
    public String toString() {
        return "SkillResult{success=" + success + ", message='" + message + "', ms=" + executionTimeMs + "}";
    }

    public static class Builder {
        private boolean success;
        private String message = "";
        private List<Task> actions;
        private long executionTimeMs = 0;
        private String errorDetail = "";

        public Builder success(boolean v)          { this.success = v; return this; }
        public Builder message(String v)           { this.message = v; return this; }
        public Builder actions(List<Task> v)       { this.actions = v; return this; }
        public Builder executionTimeMs(long v)     { this.executionTimeMs = v; return this; }
        public Builder errorDetail(String v)       { this.errorDetail = v; return this; }
        public SkillResult build()                 { return new SkillResult(this); }
    }
}
