package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

import java.util.EnumSet;
import java.util.HashMap;

/**
 * ChatAction - lets Steve send a message in the GUI panel while doing other tasks.
 * Occupies only the COMMUNICATION slot so it can run alongside locomotion/interaction.
 */
@SuppressWarnings("null")
public class ChatAction extends BaseAction {
    private final String message;

    public ChatAction(SteveEntity steve, String message) {
        super(steve, new Task("chat", new HashMap<>()));
        this.message = message;
    }

    public ChatAction(SteveEntity steve, Task task) {
        super(steve, task);
        this.message = task.getStringParameter("message");
    }

    @Override
    protected void onStart() {
        // Server-side: broadcast to all players
        if (!steve.level().isClientSide) {
            steve.sendChatMessage(message);
        } else {
            com.steve.ai.client.SteveGUI.addSteveMessage(steve.getSteveName(), message);
        }
        SteveMod.LOGGER.info("Steve '{}' says: {}", steve.getSteveName(), message);
        result = ActionResult.success("Chat sent");
    }

    @Override
    protected void onTick() {
        // Instant action, no ticking needed
    }

    @Override
    protected void onCancel() {
        // Nothing to cancel
    }

    @Override
    public String getDescription() {
        return "Say: " + message;
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.COMMUNICATION);
    }
}
