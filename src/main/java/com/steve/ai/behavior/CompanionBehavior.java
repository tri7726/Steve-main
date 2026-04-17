package com.steve.ai.behavior;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import com.steve.ai.personality.PersonalityProfile;
import com.steve.ai.personality.RelationshipLevel;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@SuppressWarnings("null")
public class CompanionBehavior {

    private static final double GREET_RADIUS      = 10.0;
    private static final int    GIFT_COOLDOWN_BASE = 600;
    private static final int    IDLE_CHAT_INTERVAL = 1200;

    private final Set<String> greetedPlayers = new HashSet<>();
    private int giftCooldown     = 0;
    private int idleChatCooldown = 0;
    private final Random rng     = new Random();

    public void tick(SteveEntity steve, ActionExecutor executor,
                     PersonalityProfile personality, RelationshipLevel relationshipLevel) {
        if (steve.level().isClientSide) return;

        giftCooldown     = Math.max(0, giftCooldown - 100);
        idleChatCooldown = Math.max(0, idleChatCooldown - 100);

        greetNearbyPlayers(steve, personality, relationshipLevel);

        if (giftCooldown == 0) {
            checkAndGiftPlayer(steve, executor, personality, relationshipLevel);
        }

        if (idleChatCooldown == 0) {
            doIdleChat(steve, personality, relationshipLevel);
        }
    }

    private void greetNearbyPlayers(SteveEntity steve, PersonalityProfile personality,
                                     RelationshipLevel level) {
        for (Player p : steve.level().players()) {
            if (p.distanceTo(steve) > GREET_RADIUS) continue;
            String name = p.getName().getString();
            if (greetedPlayers.contains(name)) continue;
            greetedPlayers.add(name);
            steve.sendChatMessage(personality.getGreeting(level) + " " + name + "!");
        }
    }

    private void checkAndGiftPlayer(SteveEntity steve, ActionExecutor executor,
                                     PersonalityProfile personality, RelationshipLevel level) {
        SteveInventory inv = steve.getMemory().getInventory();

        boolean hasDiamond = inv.countItem(net.minecraft.world.item.Items.DIAMOND) > 0;
        boolean hasEmerald = inv.countItem(net.minecraft.world.item.Items.EMERALD) > 0;
        boolean hasGold    = inv.countItem(net.minecraft.world.item.Items.GOLD_INGOT) >= 4;

        if (!hasDiamond && !hasEmerald && !hasGold) {
            giftCooldown = GIFT_COOLDOWN_BASE;
            return;
        }

        Player nearest = findNearestPlayer(steve, 8.0);
        if (nearest == null) {
            giftCooldown = 300;
            return;
        }

        String item;
        int qty;
        if (hasDiamond)      { item = "diamond";   qty = 1; }
        else if (hasEmerald) { item = "emerald";    qty = 1; }
        else                 { item = "gold_ingot"; qty = 2; }

        steve.sendChatMessage(personality.formatMessage(
            "Tim duoc " + item + " roi! Tang " + nearest.getName().getString() + " ne!", level
        ));
        executor.enqueue(new Task("give", Map.of(
            "player",   nearest.getName().getString(),
            "item",     item,
            "quantity", qty
        )));
        giftCooldown = GIFT_COOLDOWN_BASE * 4;
    }

    private void doIdleChat(SteveEntity steve, PersonalityProfile personality, RelationshipLevel level) {
        if (findNearestPlayer(steve, 20.0) == null) {
            idleChatCooldown = 200;
            return;
        }
        if (rng.nextFloat() > personality.getProactiveChance()) {
            idleChatCooldown = IDLE_CHAT_INTERVAL;
            return;
        }
        String[] lines = getIdleLines(personality);
        steve.sendChatMessage(personality.formatMessage(lines[rng.nextInt(lines.length)], level));
        idleChatCooldown = IDLE_CHAT_INTERVAL;
    }

    private String[] getIdleLines(PersonalityProfile personality) {
        return switch (personality.getType()) {
            case JOKER   -> new String[]{"Tai sao creeper khong co ban gai? Vi no toan no tung moi thu!", "Dao mai chua thay diamond, chac diamond dang tron minh", "Skeleton ban minh truot het, chac hoc cung o dau vay"};
            case SERIOUS -> new String[]{"Can chuan bi them tai nguyen truoc khi dem xuong.", "Nen xay them tuong bao ve base.", "Nen enchant tool truoc khi di sau hon."};
            case CALM    -> new String[]{"Moi thu dang on, cu tu tu thoi.", "Nghe tieng nuoc chay gan day... thu vi day.", "Hom nay yen binh nhi."};
            default      -> new String[]{"San sang chien dau bat cu luc nao!", "Khong co gi co the can duoc minh!", "Cung nhau xay dung thu gi do tuyet voi di!"};
        };
    }

    private Player findNearestPlayer(SteveEntity steve, double maxRadius) {
        Player nearest = null;
        double nearestSq = maxRadius * maxRadius;
        for (Player p : steve.level().players()) {
            double d = p.distanceToSqr(steve);
            if (d < nearestSq) { nearestSq = d; nearest = p; }
        }
        return nearest;
    }

    public void resetGreetings() {
        greetedPlayers.clear();
    }
}

