package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * GiveItemAction: Steve finds a target player, walks to them, and gives (drops) an item.
 * Parameters:
 * - player: Target player name (optional, defaults to nearest)
 * - item: Item name to give
 * - quantity: Number of items (default 1)
 */
@SuppressWarnings("null")
public class GiveItemAction extends BaseAction {
    private String playerName;
    private String itemName;
    private int quantity;
    private Player targetPlayer;
    private Item targetItem;
    private int ticksRunning;
    private static final int MAX_TICKS = 2400; // 2 minutes
    private static final double GIVE_DIST = 3.0;

    public GiveItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        playerName = task.getStringParameter("player");
        itemName = task.getStringParameter("item");
        quantity = task.getIntParameter("quantity", 1);
        ticksRunning = 0;

        targetItem = parseItem(itemName);
        if (targetItem == null || targetItem == Items.AIR) {
            result = ActionResult.failure("Vật phẩm không hợp lệ: " + itemName);
            return;
        }

        findPlayer();
        if (targetPlayer == null) {
            result = ActionResult.failure("Không tìm thấy người chơi nào để đưa đồ.");
            return;
        }

        steve.sendChatMessage("Đang mang " + quantity + "x " + targetItem.getDescription().getString() + " cho " + targetPlayer.getName().getString() + "!");
        SteveMod.LOGGER.info("Steve '{}' giving {}x {} to {}", steve.getSteveName(), quantity, itemName, targetPlayer.getName().getString());
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Hết thời gian đưa đồ");
            return;
        }

        if (targetPlayer == null || !targetPlayer.isAlive() || targetPlayer.isRemoved()) {
            findPlayer();
            if (targetPlayer == null) {
                result = ActionResult.failure("Mất dấu người chơi mục tiêu");
                return;
            }
        }

        double dist = steve.distanceTo(targetPlayer);
        if (dist > GIVE_DIST) {
            steve.getNavigation().moveTo(targetPlayer, 1.2);
        } else {
            steve.getNavigation().stop();
            executeGive();
        }
        
        // Look at player
        steve.getLookControl().setLookAt(targetPlayer, 30f, 30f);
    }

    private void executeGive() {
        SteveInventory inv = steve.getMemory().getInventory();
        int available = 0;
        // Count how many we actually have
        for (ItemStack stack : inv.getItems()) {
            if (stack.is(targetItem)) {
                available += stack.getCount();
            }
        }

        if (available <= 0) {
            result = ActionResult.failure("Tao không có " + targetItem.getDescription().getString() + " trong túi!");
            steve.sendChatMessage("Trong túi tao không có " + targetItem.getDescription().getString() + "!");
            return;
        }

        int toGive = Math.min(quantity, available);
        inv.removeItem(targetItem, toGive);

        // Spawn item entity
        ItemStack giftStack = new ItemStack(targetItem, toGive);
        
        // Calculate direction to player to throw the item
        Vec3 direction = targetPlayer.position().subtract(steve.position()).normalize();
        
        ItemEntity itemEntity = new ItemEntity(steve.level(), 
            steve.getX(), steve.getEyeY() - 0.3, steve.getZ(), giftStack);
        
        // Give it some velocity towards the player
        itemEntity.setDeltaMovement(direction.scale(0.3).add(0, 0.2, 0));
        itemEntity.setPickUpDelay(20); // Delay so Steve doesn't immediately pick it back up
        
        steve.level().addFreshEntity(itemEntity);
        steve.swing(InteractionHand.MAIN_HAND, true);

        String msg = "Đây nè, tặng bạn " + toGive + "x " + targetItem.getDescription().getString() + "!";
        steve.sendChatMessage(msg);
        
        result = ActionResult.success("Đã đưa " + toGive + "x " + targetItem.getDescription().getString() + " cho " + targetPlayer.getName().getString());
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        String pName = targetPlayer != null ? targetPlayer.getName().getString() : (playerName != null ? playerName : "người chơi");
        String iName = targetItem != null ? targetItem.getDescription().getString() : itemName;
        return "Give " + quantity + "x " + iName + " to " + pName;
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    private void findPlayer() {
        if (playerName != null && !playerName.isBlank()) {
            for (Player p : steve.level().players()) {
                if (p.getName().getString().equalsIgnoreCase(playerName)) {
                    targetPlayer = p;
                    return;
                }
            }
        }
        // Fallback to nearest
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player p : steve.level().players()) {
            if (!p.isAlive() || p.isRemoved() || p.isSpectator()) continue;
            double d = steve.distanceTo(p);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = p;
            }
        }
        targetPlayer = nearest;
    }

    private Item parseItem(String name) {
        if (name == null || name.isBlank()) return null;
        name = name.toLowerCase().trim().replace(" ", "_");

        Map<String, String> aliases = new HashMap<>();
        aliases.put("thit_bo", "cooked_beef");
        aliases.put("banh_mi", "bread");
        aliases.put("go", "oak_log");
        aliases.put("sat", "iron_ingot");
        aliases.put("kim_cuong", "diamond");
        aliases.put("than", "coal");
        aliases.put("lua_mi", "wheat");
        aliases.put("ca_rot", "carrot");
        aliases.put("khoai_tay", "potato");

        if (aliases.containsKey(name)) name = aliases.get(name);
        if (name.contains(":")) name = name.split(":")[1];
        name = name.replaceAll("[^a-z0-9/_.-]", "");

        try {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:" + name));
            return (item != null && item != Items.AIR) ? item : null;
        } catch (Exception e) {
            return null;
        }
    }
}
