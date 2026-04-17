package com.steve.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResponseParser {
    
    public static ParsedResponse parseAIResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        try {
            String jsonString = extractJSON(response);
            
            JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();
            
            String reasoning = json.has("reasoning") ? json.get("reasoning").getAsString() : "";
            String plan = json.has("plan") ? json.get("plan").getAsString() : "";
            List<Task> tasks = new ArrayList<>();
            
            if (json.has("tasks") && json.get("tasks").isJsonArray()) {
                JsonArray tasksArray = json.getAsJsonArray("tasks");
                
                for (JsonElement taskElement : tasksArray) {
                    if (taskElement.isJsonObject()) {
                        JsonObject taskObj = taskElement.getAsJsonObject();
                        Task task = parseTask(taskObj);
                        if (task != null) {
                            tasks.add(task);
                        }
                    }
                }
            }
            
            if (!reasoning.isEmpty()) {            }
            
            return new ParsedResponse(reasoning, plan, tasks);
            
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to parse AI response: {}", response, e);
            return null;
        }
    }

    private static String extractJSON(String response) {
        String cleaned = response.trim();
        
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        
        cleaned = cleaned.trim();
        
        // Fix common JSON formatting issues
        cleaned = cleaned.replaceAll("\\n\\s*", " ");
        
        // Fix missing commas between array/object elements (common AI mistake)
        cleaned = cleaned.replaceAll("}\\s+\\{", "},{");
        cleaned = cleaned.replaceAll("}\\s+\\[", "},[");
        cleaned = cleaned.replaceAll("]\\s+\\{", "],{");
        cleaned = cleaned.replaceAll("]\\s+\\[", "],[");
        
        return cleaned;
    }

    private static Task parseTask(JsonObject taskObj) {
        if (!taskObj.has("action")) {
            return null;
        }
        
        String action = taskObj.get("action").getAsString();
        Map<String, Object> parameters = new HashMap<>();
        
        if (taskObj.has("parameters") && taskObj.get("parameters").isJsonObject()) {
            JsonObject paramsObj = taskObj.getAsJsonObject("parameters");
            
            for (String key : paramsObj.keySet()) {
                JsonElement value = paramsObj.get(key);
                
                if (value.isJsonPrimitive()) {
                    if (value.getAsJsonPrimitive().isNumber()) {
                        parameters.put(key, value.getAsNumber());
                    } else if (value.getAsJsonPrimitive().isBoolean()) {
                        parameters.put(key, value.getAsBoolean());
                    } else {
                        parameters.put(key, value.getAsString());
                    }
                } else if (value.isJsonArray()) {
                    List<Object> list = new ArrayList<>();
                    for (JsonElement element : value.getAsJsonArray()) {
                        if (element.isJsonPrimitive()) {
                            if (element.getAsJsonPrimitive().isNumber()) {
                                list.add(element.getAsNumber());
                            } else {
                                list.add(element.getAsString());
                            }
                        }
                    }
                    parameters.put(key, list);
                }
            }
        }
        // --- Alias Mapping (Auto Override "Từ Lóng") ---
        if ("mine".equalsIgnoreCase(action) && parameters.containsKey("block")) {
            Object blockObj = parameters.get("block");
            if (blockObj instanceof String) {
                String block = ((String) blockObj).toLowerCase().replace(" ", "_");
                if (block.isEmpty() || block.equals("null")) block = "stone"; // Fallback to stone
                if (block.equals("wood") || block.equals("log") || block.equals("logs")) block = "oak_log";
                if (block.equals("planks")) block = "oak_planks";
                if (block.equals("diamond")) block = "diamond_ore";
                if (block.equals("iron")) block = "iron_ore";
                if (block.equals("gold")) block = "gold_ore";
                if (block.equals("coal")) block = "coal_ore";
                if (block.equals("food") || block.equals("apple")) block = "apple";
                parameters.put("block", block);
            }
        } else if (("craft".equalsIgnoreCase(action) || "smelt".equalsIgnoreCase(action)) && parameters.containsKey("item")) {
            Object itemObj = parameters.get("item");
            if (itemObj instanceof String) {
                String item = ((String) itemObj).toLowerCase().replace(" ", "_");
                if (item.isEmpty() || item.equals("null")) item = "wooden_planks"; // Fallback
                if (item.equals("wood") || item.equals("log") || item.equals("logs")) item = "oak_log";
                if (item.equals("planks")) item = "oak_planks";
                if (item.equals("pickaxe")) item = "wooden_pickaxe";
                if (item.equals("sword")) item = "wooden_sword";
                if (item.equals("food") || item.equals("apple")) item = "apple";
                parameters.put("item", item);
            }
        }
        // -----------------------------------------------
        
        return new Task(action, parameters);
    }

    public static class ParsedResponse {
        private final String reasoning;
        private final String plan;
        private final List<Task> tasks;

        public ParsedResponse(String reasoning, String plan, List<Task> tasks) {
            this.reasoning = reasoning;
            this.plan = plan;
            this.tasks = tasks;
        }

        public String getReasoning() {
            return reasoning;
        }

        public String getPlan() {
            return plan;
        }

        public List<Task> getTasks() {
            return tasks;
        }
    }
}

