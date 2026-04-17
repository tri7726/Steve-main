package com.steve.ai.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class SteveConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> AI_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_MODEL;
    // GROQ
    public static final ForgeConfigSpec.ConfigValue<String> GROQ_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> GROQ_API_KEYS;
    public static final ForgeConfigSpec.ConfigValue<String> GROQ_MODEL;
    public static final ForgeConfigSpec.IntValue GROQ_MAX_RETRIES;
    
    public static final ForgeConfigSpec.IntValue MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    public static final ForgeConfigSpec.IntValue ACTION_TICK_DELAY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CHAT_RESPONSES;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STEVES;
    public static final ForgeConfigSpec.ConfigValue<String> AUTONOMY_LEVEL;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("AI API Configuration").push("ai");
        
        AI_PROVIDER = builder
            .comment("AI provider to use: 'groq' (FASTEST, FREE), 'openai', or 'gemini'")
            .define("provider", "groq");
        
        builder.pop();

        builder.comment("OpenAI/Gemini API Configuration (same key field used for both)").push("openai");
        
        OPENAI_API_KEY = builder
            .comment("Your OpenAI API key (required)")
            .define("apiKey", "");
        
        OPENAI_MODEL = builder
            .comment("OpenAI model to use (gpt-4, gpt-4-turbo-preview, gpt-3.5-turbo)")
            .define("model", "gpt-4-turbo-preview");
        builder.pop();
        
        builder.comment("Groq API Configuration").push("groq");
        
        GROQ_API_KEY = builder
            .comment("Primary Groq API key (used when groqApiKeys list is empty)")
            .define("apiKey", "");

        GROQ_API_KEYS = builder
            .comment("List of Groq API keys for round-robin rotation and rate-limit fallback.",
                     "Example: [\"gsk_key1\", \"gsk_key2\", \"gsk_key3\"]",
                     "If non-empty, this list takes priority over apiKey above.")
            .defineList("apiKeys", java.util.List.of(), e -> e instanceof String);
            
        GROQ_MODEL = builder
            .comment("Groq model to use (e.g. llama-3.1-8b-instant, llama3-70b-8192)")
            .define("model", "llama-3.1-8b-instant");

        GROQ_MAX_RETRIES = builder
            .comment("Max retry attempts when keys hit rate limits (should equal number of keys)")
            .defineInRange("maxRetries", 3, 1, 10);
            
        builder.pop();

        builder.comment("General LLM Configuration").push("llm_settings");
        
        MAX_TOKENS = builder
            .comment("Maximum tokens per API request")
            .defineInRange("maxTokens", 500, 100, 65536);
        
        TEMPERATURE = builder
            .comment("Temperature for AI responses (0.0-2.0, lower is more deterministic)")
            .defineInRange("temperature", 0.7, 0.0, 2.0);
        
        builder.pop();

        builder.comment("Steve Behavior Configuration").push("behavior");
        
        ACTION_TICK_DELAY = builder
            .comment("Ticks between action checks (20 ticks = 1 second)")
            .defineInRange("actionTickDelay", 20, 1, 100);
        
        ENABLE_CHAT_RESPONSES = builder
            .comment("Allow Steves to respond in chat")
            .define("enableChatResponses", true);
        
        MAX_ACTIVE_STEVES = builder
            .comment("Maximum number of Steves that can be active simultaneously")
            .defineInRange("maxActiveSteves", 10, 1, 50);

        AUTONOMY_LEVEL = builder
            .comment("Steve's autonomy level: PASSIVE, REACTIVE (default), PROACTIVE, or AUTONOMOUS.",
                     "PASSIVE: only acts on direct commands.",
                     "REACTIVE: auto-defend, auto-eat, hazard escape.",
                     "PROACTIVE: REACTIVE + go home at night, tool warnings, suggestions.",
                     "AUTONOMOUS: PROACTIVE + self-plan survival when idle.")
            .define("autonomyLevel", "REACTIVE");
        
        builder.pop();

        SPEC = builder.build();
    }
}

