package com.steve.ai.agentic;

import java.util.List;

/**
 * Registry for all tools (actions) available to the LLM agent.
 * Provides tool descriptions for injection into system prompts.
 */
public interface ToolRegistry {

    /**
     * Register a tool definition. Clears the cached description block.
     *
     * @param tool the tool to register
     */
    void register(ToolDefinition tool);

    /**
     * Returns all registered tools in registration order.
     *
     * @return immutable list of tool definitions
     */
    List<ToolDefinition> getAllTools();

    /**
     * Builds (and caches) the formatted tool description block for LLM prompts.
     *
     * @return formatted string listing all tools with params and examples
     */
    String buildToolDescriptionBlock();

    /**
     * Checks whether a tool with the given action type is registered.
     *
     * @param actionType the action type name (e.g. "mine")
     * @return true if registered
     */
    boolean hasTool(String actionType);
}
