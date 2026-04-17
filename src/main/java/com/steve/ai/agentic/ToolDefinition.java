package com.steve.ai.agentic;

import java.util.List;
import java.util.Map;

/**
 * Defines a tool (action) that the LLM agent can invoke.
 *
 * @param name        action type identifier (e.g. "mine")
 * @param description human-readable description of what the tool does
 * @param parameters  map of parameter name → description
 * @param examples    example invocation strings
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, String> parameters,
    List<String> examples
) {}
