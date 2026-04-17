package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * TradeAction: finds a nearby Villager and trades items.
 * mode "buy"  → Steve pays items to get the desired item from villager.
 * mode "list" → Reports all available trades from nearby villager.
 */
@SuppressWarnings("null")
public class TradeAction extends BaseAction {
    private String mode;
    private String itemName;
    private int quantity;
    private Villager targetVillager;

    public TradeAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        mode     = task.getStringParameter("mode");
        itemName = task.getStringParameter("item");
        quantity = task.getIntParameter("quantity", 1);

        if (mode == null) mode = "list";

        // Find nearest villager
        targetVillager = findNearestVillager();

        if (targetVillager == null) {
            result = ActionResult.failure("Không tìm thấy dân làng nào gần đây!");
            steve.sendChatMessage("Không thấy dân làng nào xung quanh.");
            return;
        }

        // Walk to villager
        steve.getNavigation().moveTo(targetVillager, 1.2);
        steve.sendChatMessage("Thấy dân làng "
                + targetVillager.getVillagerData().getProfession().name()
                + ", đi tới trao đổi...");

        switch (mode) {
            case "buy"  -> executeTrade();
            case "sell"  -> executeTrade();
            case "smart_auto" -> executeSmartAutoTrade();
            default      -> listTrades();
        }
    }

    @Override
    protected void onTick() {
        // Action completes in onStart, nothing to tick
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Trade " + mode + (itemName != null ? " " + itemName : "");
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    // ── Implementation ─────────────────────────────────────────────────────────

    private void executeTrade() {
        MerchantOffers offers = targetVillager.getOffers();

        if (offers.isEmpty()) {
            result = ActionResult.failure("Dân làng này không có giao dịch nào!");
            steve.sendChatMessage("Dân làng này hông bán gì hết!");
            return;
        }

        String target = itemName != null ? itemName.toLowerCase().replace(" ", "_") : "";

        // Find matching offer
        MerchantOffer matchingOffer = null;
        for (MerchantOffer offer : offers) {
            String resultName = offer.getResult().getItem().getDescriptionId().toLowerCase();
            if (resultName.contains(target)) {
                matchingOffer = offer;
                break;
            }
        }

        if (matchingOffer == null) {
            result = ActionResult.failure("Không tìm thấy giao dịch cho: " + itemName);
            steve.sendChatMessage("Dân làng không bán " + itemName);
            return;
        }

        if (matchingOffer.isOutOfStock()) {
            result = ActionResult.failure("Hết hàng: " + itemName);
            steve.sendChatMessage("Hết hàng rồi!");
            return;
        }

        // ── Kiểm tra và trừ chi phí từ SteveInventory ────────────────────────
        SteveInventory inv = steve.getMemory().getInventory();
        net.minecraft.world.item.Item costItemA = matchingOffer.getCostA().getItem();
        int costCountA = matchingOffer.getCostA().getCount();

        if (!inv.hasItem(costItemA, costCountA)) {
            String costDesc2 = costCountA + "x " + matchingOffer.getCostA().getItem().getDescription().getString();
            result = ActionResult.failure("Không đủ " + costDesc2 + " để mua " + itemName);
            steve.sendChatMessage("Túi thiếu " + costDesc2 + " để đổi!");
            return;
        }
        inv.removeItem(costItemA, costCountA);

        // Trừ chi phí B nếu có (VD: một số trade cần 2 loại item)
        if (!matchingOffer.getCostB().isEmpty()) {
            net.minecraft.world.item.Item costItemB = matchingOffer.getCostB().getItem();
            int costCountB = matchingOffer.getCostB().getCount();
            if (!inv.hasItem(costItemB, costCountB)) {
                // Hoàn lại costA vì không đủ costB
                inv.addItem(new net.minecraft.world.item.ItemStack(costItemA, costCountA));
                String costDescB = costCountB + "x " + costItemB.getDescription().getString();
                result = ActionResult.failure("Không đủ " + costDescB + " để mua " + itemName);
                steve.sendChatMessage("Túi thiếu " + costDescB + " để đổi!");
                return;
            }
            inv.removeItem(costItemB, costCountB);
        }

        // Simulate the trade — give result to nearest player
        ItemStack tradeResult = matchingOffer.getResult().copy();
        tradeResult.setCount(Math.min(quantity, tradeResult.getMaxStackSize()));

        net.minecraft.world.entity.player.Player nearestPlayer =
                steve.level().getNearestPlayer(steve, 10.0);
        if (nearestPlayer != null) {
            if (!nearestPlayer.getInventory().add(tradeResult)) {
                steve.spawnAtLocation(tradeResult);
            }
        } else {
            steve.spawnAtLocation(tradeResult);
        }

        // Animate
        steve.swing(InteractionHand.MAIN_HAND, true);

        // Use the offer (updates villager XP/availability)
        matchingOffer.increaseUses();

        // Trigger happy villager particles
        targetVillager.setUnhappyCounter(0);

        String costDesc = matchingOffer.getCostA().getCount() + "x "
                + matchingOffer.getCostA().getItem().getDescription().getString();

        result = ActionResult.success("Đổi được " + tradeResult.getCount() + "x "
                + tradeResult.getItem().getDescription().getString()
                + " (giá: " + costDesc + ")");
        steve.sendChatMessage("Đổi được " + tradeResult.getItem().getDescription().getString()
                + "! Giá: " + costDesc);

        // Save to memory
        steve.getMemory().addAction("Traded with villager: got "
                + tradeResult.getItem().getDescription().getString()
                + " for " + costDesc);

        SteveMod.LOGGER.info("Steve '{}' traded for {}",
                steve.getSteveName(), tradeResult.getItem().getDescription().getString());
    }

    private void listTrades() {
        MerchantOffers offers = targetVillager.getOffers();
        List<String> tradeList = new ArrayList<>();

        for (MerchantOffer offer : offers) {
            String cost = offer.getCostA().getCount() + "x "
                    + offer.getCostA().getItem().getDescription().getString();
            String result = offer.getResult().getCount() + "x "
                    + offer.getResult().getItem().getDescription().getString();
            String stock = offer.isOutOfStock() ? " [HẾT HÀNG]" : "";
            tradeList.add(cost + " → " + result + stock);
        }

        String summary = tradeList.isEmpty() ? "Không có giao dịch nào"
                : String.join(" | ", tradeList);

        steve.sendChatMessage("Giao dịch có sẵn: " + summary);

        // Save trades to memory for future reference
        steve.getMemory().addAction("Villager trades at "
                + targetVillager.blockPosition() + ": " + summary);

        this.result = ActionResult.success("Listed " + tradeList.size() + " trades");
    }

    private void executeSmartAutoTrade() {
        MerchantOffers offers = targetVillager.getOffers();
        SteveInventory inv = steve.getMemory().getInventory();

        boolean actionTaken = false;

        // 1. Giai đoạn KIẾM TIỀN: Bán đồ (Lúa mì, Khoai tây, Gậy...) lấy Emeralds
        for (MerchantOffer offer : offers) {
            if (offer.isOutOfStock()) continue;
            
            ItemStack cost = offer.getCostA();
            ItemStack result = offer.getResult();

            if (result.getItem() == net.minecraft.world.item.Items.EMERALD) {
                // Đây là trade kiếm tiền. Thử xem mình có đồ bán không.
                int available = inv.countItem(cost.getItem());
                if (available >= cost.getCount()) {
                    inv.removeItem(cost.getItem(), cost.getCount());
                    inv.addItem(result.copy());
                    offer.increaseUses();
                    steve.sendChatMessage("Kiếm được Emerald từ việc bán " + cost.getItem().getDescription().getString() + "!");
                    actionTaken = true;
                }
            }
        }

        // 2. Giai đoạn TIÊU TIỀN: Dùng Emerald mua đồ xịn (Mending, Diamond gear)
        for (MerchantOffer offer : offers) {
            if (offer.isOutOfStock()) continue;

            ItemStack cost = offer.getCostA();
            ItemStack resultStack = offer.getResult();
            net.minecraft.world.item.Item resultItem = resultStack.getItem();

            if (cost.getItem() == net.minecraft.world.item.Items.EMERALD) {
                boolean isValuable = resultItem == net.minecraft.world.item.Items.ENCHANTED_BOOK 
                    || resultItem == net.minecraft.world.item.Items.DIAMOND_PICKAXE
                    || resultItem == net.minecraft.world.item.Items.DIAMOND_CHESTPLATE;

                if (isValuable && inv.hasItem(net.minecraft.world.item.Items.EMERALD, cost.getCount())) {
                    inv.removeItem(net.minecraft.world.item.Items.EMERALD, cost.getCount());
                    inv.addItem(resultStack.copy());
                    offer.increaseUses();
                    steve.sendChatMessage("Đã tậu thêm món đồ giá trị: " + resultItem.getDescription().getString() + "!");
                    actionTaken = true;
                }
            }
        }

        if (actionTaken) {
            steve.swing(InteractionHand.MAIN_HAND, true);
            this.result = ActionResult.success("Smart auto-trade completed some transactions");
        } else {
            this.result = ActionResult.success("No suitable smart trades found");
        }
    }

    private Villager findNearestVillager() {
        AABB searchBox = steve.getBoundingBox().inflate(32.0);
        List<Entity> entities = steve.level().getEntities(steve, searchBox);

        Villager nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (entity instanceof Villager villager && villager.isAlive()) {
                double dist = steve.distanceTo(villager);
                if (dist < nearestDist) {
                    nearest = villager;
                    nearestDist = dist;
                }
            }
        }
        return nearest;
    }
}
