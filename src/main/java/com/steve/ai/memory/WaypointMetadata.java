package com.steve.ai.memory;

import net.minecraft.core.BlockPos;

public record WaypointMetadata(
    String label,
    BlockPos pos,
    String dimension,
    String biome,
    String description,
    long savedAt
) {}
