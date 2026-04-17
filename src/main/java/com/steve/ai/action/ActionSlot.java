package com.steve.ai.action;

/**
 * Defines the execution slots for Multi-tasking.
 * An action may require one or more slots to run.
 */
public enum ActionSlot {
    /** Movement, pathfinding, or following (legs) */
    LOCOMOTION,
    
    /** Mining, placing, attacking, crafting (arms) */
    INTERACTION,
    
    /** Chatting, acknowledging, sending messages (mouth/head) */
    COMMUNICATION,
    
    /** Internal processing, waiting, or thinking (brain) */
    LOGIC
}
