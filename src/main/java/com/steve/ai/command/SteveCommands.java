package com.steve.ai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("null") // Brigadier API (StringArgumentType, CommandContext, Component) guaranteed non-null at runtime
public class SteveCommands {

    // Shared thread pool for async LLM command processing — prevents thread leak
    private static final ExecutorService COMMAND_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "steve-command-worker");
                t.setDaemon(true);
                return t;
            });

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("steve")
            .then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::spawnSteve)))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::removeSteve)))
            .then(Commands.literal("list")
                .executes(SteveCommands::listSteves))
            .then(Commands.literal("stop")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::stopSteve)))
            .then(Commands.literal("inv")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::showInventory)))
            .then(Commands.literal("tell")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(SteveCommands::tellSteve))))
            // ── Gameplay enhancement commands ─────────────────────────────────
            .then(Commands.literal("minigame")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("game", StringArgumentType.string())
                        .executes(SteveCommands::startMiniGame))))
            .then(Commands.literal("guard")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("toggle", StringArgumentType.string())
                        .executes(SteveCommands::toggleNightGuard))))
            .then(Commands.literal("autofarm")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("toggle", StringArgumentType.string())
                        .executes(SteveCommands::toggleAutoFarm))))
            .then(Commands.literal("role")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("role", StringArgumentType.string())
                        .executes(SteveCommands::assignRole))))
            .then(Commands.literal("cobuild")
                .then(Commands.argument("structure", StringArgumentType.string())
                    .executes(SteveCommands::coBuild)))
            .then(Commands.literal("brain")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("toggle", StringArgumentType.string())
                        .executes(SteveCommands::toggleBrain))))
        );
    }

    private static int spawnSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        ServerLevel serverLevel = source.getLevel();
        if (serverLevel == null) {
            source.sendFailure(Component.literal("Command must be run on server"));
            return 0;
        }

        SteveManager manager = SteveMod.getSteveManager();
        
        Vec3 sourcePos = source.getPosition();
        if (source.getEntity() != null) {
            Vec3 lookVec = source.getEntity().getLookAngle();
            sourcePos = sourcePos.add(lookVec.x * 3, 0, lookVec.z * 3);
        } else {
            sourcePos = sourcePos.add(3, 0, 0);
        }
        Vec3 spawnPos = sourcePos;
        
        SteveEntity steve = manager.spawnSteve(serverLevel, spawnPos, name);
        if (steve != null) {
            source.sendSuccess(() -> Component.literal("Spawned Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to spawn Steve. Name may already exist or max limit reached."));
            return 0;
        }
    }

    private static int removeSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        if (manager.removeSteve(name)) {
            source.sendSuccess(() -> Component.literal("Removed Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    private static int listSteves(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveManager manager = SteveMod.getSteveManager();
        
        var names = manager.getSteveNames();
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active Steves"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Active Steves (" + names.size() + "): " + String.join(", ", names)), false);
        }
        return 1;
    }

    private static int stopSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);
        
        if (steve != null) {
            steve.getActionExecutor().stopCurrentAction();
            steve.getMemory().clearTaskQueue();
            source.sendSuccess(() -> Component.literal("Stopped Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    private static int showInventory(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();

        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);

        if (steve == null) {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }

        var summary = steve.getMemory().getInventory().getSummary();
        if (summary.isEmpty()) {
            source.sendSuccess(() -> Component.literal(name + "'s inventory: [empty]"), false);
        } else {
            source.sendSuccess(() -> Component.literal(name + "'s inventory: " + String.join(", ", summary)), false);
        }
        return 1;
    }

    private static int tellSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String command = StringArgumentType.getString(context, "command");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);
        
        if (steve != null) {
            // Use shared thread pool instead of raw new Thread() to prevent thread leak
            COMMAND_EXECUTOR.submit(() ->
                steve.getActionExecutor().processNaturalLanguageCommand(command)
            );
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    // ── Gameplay enhancement command handlers ─────────────────────────────────

    private static int startMiniGame(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String game  = StringArgumentType.getString(context, "game").toLowerCase();
        CommandSourceStack source = context.getSource();

        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) { source.sendFailure(Component.literal("Steve not found: " + name)); return 0; }

        com.steve.ai.minigame.MiniGameManager.GameType type = switch (game) {
            case "race"    -> com.steve.ai.minigame.MiniGameManager.GameType.MINING_RACE;
            case "survive" -> com.steve.ai.minigame.MiniGameManager.GameType.SURVIVAL_CHALLENGE;
            case "hunt"    -> com.steve.ai.minigame.MiniGameManager.GameType.TREASURE_HUNT;
            case "stop"    -> null;
            default        -> null;
        };

        if (type == null) {
            steve.getMiniGameManager().stopGame(steve);
        } else {
            net.minecraft.world.entity.player.Player player = source.getEntity() instanceof net.minecraft.world.entity.player.Player p ? p : null;
            steve.getMiniGameManager().startGame(type, player, steve, steve.getActionExecutor());
        }
        return 1;
    }

    private static int toggleNightGuard(CommandContext<CommandSourceStack> context) {
        String name   = StringArgumentType.getString(context, "name");
        String toggle = StringArgumentType.getString(context, "toggle").toLowerCase();
        CommandSourceStack source = context.getSource();

        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) { source.sendFailure(Component.literal("Steve not found: " + name)); return 0; }

        steve.getQolManager().setNightGuard(toggle.equals("on"), steve);
        return 1;
    }

    private static int toggleAutoFarm(CommandContext<CommandSourceStack> context) {
        String name   = StringArgumentType.getString(context, "name");
        String toggle = StringArgumentType.getString(context, "toggle").toLowerCase();
        CommandSourceStack source = context.getSource();

        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) { source.sendFailure(Component.literal("Steve not found: " + name)); return 0; }

        steve.getQolManager().setAutoFarm(toggle.equals("on"), steve);
        return 1;
    }

    private static int assignRole(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String role = StringArgumentType.getString(context, "role").toUpperCase();
        CommandSourceStack source = context.getSource();

        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) { source.sendFailure(Component.literal("Steve not found: " + name)); return 0; }

        try {
            com.steve.ai.behavior.MultiAgentCoordinator.TeamRole teamRole =
                com.steve.ai.behavior.MultiAgentCoordinator.TeamRole.valueOf(role);
            com.steve.ai.SteveMod.getMultiAgentCoordinator().assignRole(steve, teamRole);
            source.sendSuccess(() -> Component.literal(name + " assigned role: " + role), true);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid role. Use: TANK, DPS, SUPPORT, BUILDER"));
        }
        return 1;
    }

    private static int toggleBrain(CommandContext<CommandSourceStack> context) {
        String name   = StringArgumentType.getString(context, "name");
        String toggle = StringArgumentType.getString(context, "toggle").toLowerCase();
        CommandSourceStack source = context.getSource();

        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) { source.sendFailure(Component.literal("Steve not found: " + name)); return 0; }

        if (toggle.equals("on")) {
            steve.enableGrpcBrain();
            source.sendSuccess(() -> Component.literal("🧠 Hybrid brain ON for " + name + " (connecting to Node.js:50051)"), true);
        } else {
            steve.disableGrpcBrain();
            source.sendSuccess(() -> Component.literal("🧠 Hybrid brain OFF for " + name), true);
        }
        return 1;
    }

    private static int coBuild(CommandContext<CommandSourceStack> context) {
        String structure = StringArgumentType.getString(context, "structure");
        CommandSourceStack source = context.getSource();

        java.util.List<SteveEntity> allSteves = com.steve.ai.SteveMod.getMultiAgentCoordinator().getAllSteves();

        if (allSteves.isEmpty()) {
            source.sendFailure(Component.literal("No active Steves found!"));
            return 0;
        }

        com.steve.ai.SteveMod.getMultiAgentCoordinator().assignCoBuildTasks(allSteves, structure);
        source.sendSuccess(() -> Component.literal("Co-build started: " + allSteves.size() + " Steves building " + structure), true);
        return 1;
    }
}
