package com.steve.ai.personality;

import net.minecraft.nbt.CompoundTag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("null")
public class PersonalityManager {

    private static final Logger LOGGER = LogManager.getLogger(PersonalityManager.class);

    private static final String[] JOKER_NAMES = {"bob", "max", "rex", "jack", "tom"};
    private static final String[] SERIOUS_NAMES = {"alice", "eve", "iris", "diana", "grace"};
    private static final String[] CALM_NAMES = {"sam", "leo", "mia", "luna", "kai"};

    private final Map<String, PersonalityProfile> cache = new HashMap<>();

    public static PersonalityType assignPersonality(String steveName) {
        String nameLower = steveName.toLowerCase();

        for (String name : JOKER_NAMES) {
            if (nameLower.contains(name)) {
                return PersonalityType.JOKER;
            }
        }

        for (String name : SERIOUS_NAMES) {
            if (nameLower.contains(name)) {
                return PersonalityType.SERIOUS;
            }
        }

        for (String name : CALM_NAMES) {
            if (nameLower.contains(name)) {
                return PersonalityType.CALM;
            }
        }

        return PersonalityType.BRAVE;
    }

    public PersonalityProfile getOrCreate(String steveName) {
        return cache.computeIfAbsent(steveName, name -> {
            PersonalityType type = assignPersonality(name);
            return switch (type) {
                case JOKER -> new JokerPersonality(name);
                case SERIOUS -> new SeriousPersonality(name);
                case CALM -> new CalmPersonality(name);
                case BRAVE -> new BravePersonality(name);
            };
        });
    }

    public void saveToNBT(CompoundTag tag) {
        tag.putInt("personality_count", cache.size());
        int i = 0;
        for (Map.Entry<String, PersonalityProfile> entry : cache.entrySet()) {
            tag.putString("personality_name_" + i, entry.getKey());
            tag.putString("personality_type_" + i, entry.getValue().getType().name());
            i++;
        }
    }

    public void loadFromNBT(CompoundTag tag) {
        try {
            int count = tag.getInt("personality_count");
            for (int i = 0; i < count; i++) {
                String name = tag.getString("personality_name_" + i);
                String typeName = tag.getString("personality_type_" + i);
                PersonalityType type = PersonalityType.valueOf(typeName);
                PersonalityProfile profile = switch (type) {
                    case JOKER -> new JokerPersonality(name);
                    case SERIOUS -> new SeriousPersonality(name);
                    case CALM -> new CalmPersonality(name);
                    case BRAVE -> new BravePersonality(name);
                };
                cache.put(name, profile);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load personality data from NBT, resetting to defaults", e);
            cache.clear();
        }
    }
}
