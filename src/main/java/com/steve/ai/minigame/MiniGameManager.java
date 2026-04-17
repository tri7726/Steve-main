package com.steve.ai.minigame;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;

/**
 * Mini-games: Mining Race, Survival Challenge, Treasure Hunt.
 *
 * Nâng cấp:
 * - Mining Race: countdown 3-2-1 thực sự (tick-based), score board cuối game
 * - Survival Challenge: track score (items collected, mobs killed, days survived)
 * - Treasure Hunt: multi-direction search (4 hướng), không dừng sau 1 chest
 * - stopGame trả về summary thay vì chỉ thông báo dừng
 */
@SuppressWarnings("null")
public class MiniGameManager {

    public enum GameType { NONE, MINING_RACE, SURVIVAL_CHALLENGE, TREASURE_HUNT }

    private static final int RACE_DURATION    = 20 * 60 * 3;
    private static final int SURVIVE_DURATION = 20 * 60 * 20;
    private static final int HUNT_DURATION    = 20 * 60 * 10;

    private GameType activeGame    = GameType.NONE;
    private int gameTicks          = 0;
    private int gameDuration       = 0;
    private String challengerName  = null;
    private int steveMineCount     = 0;
    private boolean shelterQueued  = false;

    // Countdown state cho Mining Race
    private int countdownTicks = 0;
    private boolean raceStarted = false;

    // Survival score tracking
    private int survivalMobsKilled   = 0;
    private int survivalItemsGathered = 0;
    private int prevInventorySize    = 0;

    // Treasure Hunt: multi-direction
    private int huntDirectionIndex = 0;
    private static final int[][] HUNT_OFFSETS = {{80,0},{0,80},{-80,0},{0,-80}};

    // ── Public API ────────────────────────────────────────────────────────────

    public void startGame(GameType type, Player player, SteveEntity steve, ActionExecutor executor) {
        if (activeGame != GameType.NONE) {
            steve.sendChatMessage("Đang chơi " + activeGame.name() + " rồi! Gõ 'minigame stop' để dừng.");
            return;
        }
        activeGame             = type;
        gameTicks              = 0;
        steveMineCount         = 0;
        shelterQueued          = false;
        raceStarted            = false;
        countdownTicks         = 0;
        survivalMobsKilled     = 0;
        survivalItemsGathered  = 0;
        prevInventorySize      = steve.getMemory().getInventory().size(); // reset đúng lúc start
        huntDirectionIndex     = 0;

        switch (type) {
            case MINING_RACE        -> startMiningRace(player, steve);
            case SURVIVAL_CHALLENGE -> startSurvivalChallenge(steve, executor);
            case TREASURE_HUNT      -> startTreasureHunt(steve, executor);
            default -> {}
        }
    }

    public void stopGame(SteveEntity steve) {
        if (activeGame == GameType.NONE) return;
        printGameSummary(steve);
        activeGame    = GameType.NONE;
        gameTicks     = 0;
        shelterQueued = false;
        raceStarted   = false;
    }

    /** Gọi mỗi 20 ticks (1 giây). */
    public void tick(SteveEntity steve, ActionExecutor executor) {
        if (activeGame == GameType.NONE) return;

        // Countdown trước khi race bắt đầu
        if (activeGame == GameType.MINING_RACE && !raceStarted) {
            tickCountdown(steve, executor);
            return;
        }

        gameTicks += 20;
        switch (activeGame) {
            case MINING_RACE        -> tickMiningRace(steve, executor);
            case SURVIVAL_CHALLENGE -> tickSurvivalChallenge(steve, executor);
            case TREASURE_HUNT      -> tickTreasureHunt(steve, executor);
            default -> {}
        }
    }

    public boolean isActive()       { return activeGame != GameType.NONE; }
    public GameType getActiveGame() { return activeGame; }

    // ── Mining Race ───────────────────────────────────────────────────────────

    private void startMiningRace(Player player, SteveEntity steve) {
        gameDuration   = RACE_DURATION;
        challengerName = player != null ? player.getName().getString() : "Player";
        countdownTicks = 0;
        steve.sendChatMessage("⛏ Mining Race! Ai đào được nhiều stone nhất trong 3 phút thắng!");
        steve.sendChatMessage("Chuẩn bị...");
    }

    /** Countdown 3-2-1 tick-based (mỗi 20 ticks = 1 giây). */
    private void tickCountdown(SteveEntity steve, ActionExecutor executor) {
        countdownTicks += 20;
        if (countdownTicks == 20)  { steve.sendChatMessage("3..."); return; }
        if (countdownTicks == 40)  { steve.sendChatMessage("2..."); return; }
        if (countdownTicks == 60)  { steve.sendChatMessage("1..."); return; }
        if (countdownTicks >= 80) {
            steve.sendChatMessage("🚀 GO!");
            raceStarted = true;
            // Chuẩn bị tool trước, rồi mới đào
            var inv = steve.getMemory().getInventory();
            boolean hasPick = inv.hasItem(net.minecraft.world.item.Items.WOODEN_PICKAXE, 1)
                           || inv.hasItem(net.minecraft.world.item.Items.STONE_PICKAXE, 1)
                           || inv.hasItem(net.minecraft.world.item.Items.IRON_PICKAXE, 1)
                           || inv.hasItem(net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1);
            if (!hasPick) {
                // Craft tool chain: planks → crafting_table → pickaxe
                executor.enqueue(new Task("gather", Map.of("resource", "wood")));
                executor.enqueue(new Task("craft",  Map.of("item", "crafting_table", "quantity", 1)));
                executor.enqueue(new Task("craft",  Map.of("item", "wooden_pickaxe", "quantity", 1)));
            }
            executor.enqueue(new Task("mine", Map.of("block", "stone", "quantity", 200)));
        }
    }

    private void tickMiningRace(SteveEntity steve, ActionExecutor executor) {
        steveMineCount = steve.getMemory().getInventory()
                .countItem(net.minecraft.world.item.Items.COBBLESTONE);

        if (gameTicks % (20 * 30) == 0) {
            int remaining = (gameDuration - gameTicks) / 20;
            steve.sendChatMessage("⛏ Steve: " + steveMineCount + " stone | Còn " + remaining + "s");
        }

        if (gameTicks >= gameDuration) {
            steve.sendChatMessage("🏁 Hết giờ! Steve đào được " + steveMineCount + " stone!");
            steve.sendChatMessage("📊 " + challengerName + " đào được bao nhiêu? So sánh nhé!");
            activeGame = GameType.NONE;
        }
    }

    // ── Survival Challenge ────────────────────────────────────────────────────

    private void startSurvivalChallenge(SteveEntity steve, ActionExecutor executor) {
        gameDuration      = SURVIVE_DURATION;
        prevInventorySize = steve.getMemory().getInventory().size();
        steve.sendChatMessage("🌅 Survival Challenge! Steve tự sinh tồn 1 ngày!");
        steve.sendChatMessage("Score: mobs killed + items gathered + days survived");

        executor.enqueue(new Task("gather", Map.of("resource", "wood")));
        executor.enqueue(new Task("craft",  Map.of("item", "crafting_table", "quantity", 1)));
        executor.enqueue(new Task("craft",  Map.of("item", "wooden_pickaxe", "quantity", 1)));
        executor.enqueue(new Task("mine",   Map.of("block", "stone", "quantity", 16)));
        executor.enqueue(new Task("craft",  Map.of("item", "furnace", "quantity", 1)));
        executor.enqueue(new Task("farm",   Map.of("crop", "wheat", "action", "harvest")));
    }

    private void tickSurvivalChallenge(SteveEntity steve, ActionExecutor executor) {
        long time = steve.level().getDayTime() % 24000;

        if (time >= 12500 && !shelterQueued) {
            shelterQueued = true;
            steve.sendChatMessage("🌙 Trời tối! Tìm chỗ trú ẩn...");
            executor.enqueue(new Task("build", Map.of("structure", "shelter")));
            executor.enqueue(new Task("sleep", Map.of()));
        }
        if (time < 1000) shelterQueued = false;

        // Track items gathered
        int currentInvSize = steve.getMemory().getInventory().size();
        if (currentInvSize > prevInventorySize) {
            survivalItemsGathered += (currentInvSize - prevInventorySize);
        }
        prevInventorySize = currentInvSize;

        if (gameTicks % (20 * 60 * 5) == 0 && gameTicks > 0) {
            int minutesLeft = (gameDuration - gameTicks) / (20 * 60);
            int score = survivalItemsGathered * 2 + survivalMobsKilled * 5;
            steve.sendChatMessage("⏱ Còn " + minutesLeft + " phút | HP: "
                    + (int) steve.getHealth() + "/20 | Score: " + score);
        }

        if (gameTicks >= gameDuration) {
            int finalScore = survivalItemsGathered * 2 + survivalMobsKilled * 5 + 50; // +50 bonus sống sót
            steve.sendChatMessage("🎉 Survival Challenge xong! Score: " + finalScore
                    + " (items: " + survivalItemsGathered + ", mobs: " + survivalMobsKilled + ")");
            activeGame = GameType.NONE;
        }
    }

    /** Gọi từ CombatAction khi Steve kill mob trong Survival Challenge. */
    public void onMobKilled() {
        if (activeGame == GameType.SURVIVAL_CHALLENGE) survivalMobsKilled++;
    }

    // ── Treasure Hunt ─────────────────────────────────────────────────────────

    private void startTreasureHunt(SteveEntity steve, ActionExecutor executor) {
        gameDuration = HUNT_DURATION;
        steve.sendChatMessage("🗺 Treasure Hunt! Tìm kiếm chest ẩn trong 10 phút!");
        sendToNextHuntDirection(steve, executor);
    }

    private void sendToNextHuntDirection(SteveEntity steve, ActionExecutor executor) {
        // Loop lại từ đầu khi hết 4 hướng
        if (huntDirectionIndex >= HUNT_OFFSETS.length) huntDirectionIndex = 0;
        int[] offset = HUNT_OFFSETS[huntDirectionIndex++];
        net.minecraft.core.BlockPos pos = steve.blockPosition();
        executor.enqueue(new Task("pathfind", Map.of(
            "destination", (pos.getX() + offset[0]) + " " + pos.getY() + " " + (pos.getZ() + offset[1])
        )));
        executor.enqueue(new Task("chest", Map.of("action", "open")));
    }

    private void tickTreasureHunt(SteveEntity steve, ActionExecutor executor) {
        // Mỗi 2 phút → đổi hướng tìm kiếm mới
        if (gameTicks % (20 * 60 * 2) == 0 && gameTicks > 0) {
            steve.sendChatMessage("🔍 Tìm kiếm hướng mới... (" + (gameTicks / (20 * 60)) + " phút)");
            sendToNextHuntDirection(steve, executor);
        }

        if (gameTicks >= gameDuration) {
            steve.sendChatMessage("⏰ Hết giờ Treasure Hunt! Kiểm tra inventory nhé!");
            activeGame = GameType.NONE;
        }
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private void printGameSummary(SteveEntity steve) {
        switch (activeGame) {
            case MINING_RACE ->
                steve.sendChatMessage("🛑 Race dừng. Steve đào được " + steveMineCount + " stone.");
            case SURVIVAL_CHALLENGE -> {
                int score = survivalItemsGathered * 2 + survivalMobsKilled * 5;
                steve.sendChatMessage("🛑 Survival dừng. Score: " + score
                        + " | Items: " + survivalItemsGathered + " | Mobs: " + survivalMobsKilled);
            }
            case TREASURE_HUNT ->
                steve.sendChatMessage("🛑 Treasure Hunt dừng. Đã tìm " + huntDirectionIndex + " hướng.");
            default ->
                steve.sendChatMessage("🛑 Mini-game " + activeGame.name() + " đã dừng.");
        }
    }
}
