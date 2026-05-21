package com.manhunt;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.advancement.Advancement;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ManhuntMod implements DedicatedServerModInitializer {

    private static final Set<UUID> hunters = new HashSet<>();
    private static final Set<UUID> runners = new HashSet<>();
    private static boolean gameRunning = false;
    private static int gracePeriod = 0;
    private static int deadZoneRadius = 20;
    private static int graceTicksLeft = 0;

    // Map<runnerUUID, Map<dimensionId, portalBlockPos>> — portal positions per dimension
    private static final Map<UUID, Map<String, BlockPos>> runnerPortalPositions = new HashMap<>();
    // Previous dimension per runner, to detect dimension changes
    private static final Map<UUID, String> runnerLastDimension = new HashMap<>();
    // Last known BlockPos per runner (from previous tick), used to capture departure position on dim change
    private static final Map<UUID, BlockPos> runnerLastPos = new HashMap<>();

    private static final SuggestionProvider<net.minecraft.server.command.ServerCommandSource> ONLINE_PLAYERS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (ServerPlayerEntity p : ctx.getSource().getMinecraftServer().getPlayerManager().getPlayerList()) {
                String name = p.getName().getString();
                if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
            }
            return builder.buildFuture();
        };

    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {

            dispatcher.register(literal("manhunt")
                .then(literal("start").executes(ctx -> {
                    if (hunters.isEmpty() || runners.isEmpty()) {
                        ctx.getSource().sendFeedback(new LiteralText("§cNeed at least one hunter and one runner."), false);
                        return 0;
                    }
                    if (gameRunning) {
                        ctx.getSource().sendFeedback(new LiteralText("§cGame already running. Use /manhunt stop first."), false);
                        return 0;
                    }
                    MinecraftServer server = ctx.getSource().getMinecraftServer();
                    gameRunning = true;
                    graceTicksLeft = gracePeriod * 20;

                    // Freeze hunters
                    for (UUID id : hunters) {
                        ServerPlayerEntity h = server.getPlayerManager().getPlayer(id);
                        if (h != null) h.setVelocity(0, 0, 0);
                    }

                    broadcast(server, "§6Manhunt starting! Hunters are frozen for §e" + gracePeriod + " §6seconds...");
                    giveCompasses(server);
                    return 1;
                }))
                .then(literal("stop").executes(ctx -> {
                    endGame(ctx.getSource().getMinecraftServer(), "§cManhunt stopped.");
                    return 1;
                }))
                .then(literal("status").executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getMinecraftServer();
                    String status = gameRunning
                        ? (graceTicksLeft > 0 ? "§eGRACE PERIOD (" + (graceTicksLeft / 20) + "s)" : "§aRUNNING")
                        : "§cSTOPPED";
                    StringBuilder msg = new StringBuilder("§6Manhunt: " + status + "\n§eHunters: ");
                    for (UUID id : hunters) {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                        msg.append(p != null ? p.getName().getString() : "?").append(" ");
                    }
                    msg.append("\n§bRunners: ");
                    for (UUID id : runners) {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                        msg.append(p != null ? p.getName().getString() : "?").append(" ");
                    }
                    msg.append("\n§7Grace: §f").append(gracePeriod).append("s  §7Dead zone: §f").append(deadZoneRadius).append(" blocks");
                    ctx.getSource().sendFeedback(new LiteralText(msg.toString()), false);
                    return 1;
                }))
                .then(literal("setgrace")
                    .then(argument("seconds", IntegerArgumentType.integer(0, 300)).executes(ctx -> {
                        gracePeriod = IntegerArgumentType.getInteger(ctx, "seconds");
                        ctx.getSource().sendFeedback(new LiteralText("§aGrace period set to §e" + gracePeriod + " §aseconds."), false);
                        return 1;
                    })))
                .then(literal("compass").executes(ctx -> {
                    if (!gameRunning) {
                        ctx.getSource().sendFeedback(new LiteralText("§cNo game running."), false);
                        return 0;
                    }
                    MinecraftServer server = ctx.getSource().getMinecraftServer();
                    for (UUID id : hunters) {
                        ServerPlayerEntity h = server.getPlayerManager().getPlayer(id);
                        if (h == null) continue;
                        removeAllManhuntCompasses(h);
                        giveCompassTo(h);
                    }
                    ctx.getSource().sendFeedback(new LiteralText("§aGave compass to all hunters."), false);
                    return 1;
                }))
                .then(literal("setrange")
                    .then(argument("blocks", IntegerArgumentType.integer(0, 10000)).executes(ctx -> {
                        deadZoneRadius = IntegerArgumentType.getInteger(ctx, "blocks");
                        ctx.getSource().sendFeedback(new LiteralText("§aCompass dead zone set to §e" + deadZoneRadius + " §ablocks."), false);
                        return 1;
                    })))
            );

            dispatcher.register(literal("hunter")
                .then(argument("player", StringArgumentType.word()).suggests(ONLINE_PLAYERS).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "player");
                    MinecraftServer server = ctx.getSource().getMinecraftServer();
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
                    if (target == null) {
                        ctx.getSource().sendFeedback(new LiteralText("§cPlayer not found: " + name), false);
                        return 0;
                    }
                    runners.remove(target.getUuid());
                    hunters.add(target.getUuid());
                    ctx.getSource().sendFeedback(new LiteralText("§e" + name + " §ais now a hunter."), false);
                    target.sendMessage(new LiteralText("§aYou are now a §ehunter§a!"), false);
                    return 1;
                }))
            );

            dispatcher.register(literal("runner")
                .then(argument("player", StringArgumentType.word()).suggests(ONLINE_PLAYERS).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "player");
                    MinecraftServer server = ctx.getSource().getMinecraftServer();
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
                    if (target == null) {
                        ctx.getSource().sendFeedback(new LiteralText("§cPlayer not found: " + name), false);
                        return 0;
                    }
                    hunters.remove(target.getUuid());
                    runners.add(target.getUuid());
                    ctx.getSource().sendFeedback(new LiteralText("§b" + name + " §ais now the runner."), false);
                    target.sendMessage(new LiteralText("§aYou are now the §bspeedrunner§a! Good luck!"), false);
                    return 1;
                }))
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!gameRunning) return;

            // Grace period countdown
            if (graceTicksLeft > 0) {
                graceTicksLeft--;

                // Freeze all hunters every tick during grace
                for (UUID id : hunters) {
                    ServerPlayerEntity h = server.getPlayerManager().getPlayer(id);
                    if (h != null) {
                        h.setVelocity(0, 0, 0);
                        h.velocityModified = true;
                    }
                }

                // Countdown titles at 10, 5, 4, 3, 2, 1
                int secsLeft = graceTicksLeft / 20;
                int ticks = graceTicksLeft % 20;
                if (ticks == 0) {
                    if (secsLeft <= 5 && secsLeft > 0) {
                        String color = secsLeft <= 3 ? "§c" : "§e";
                        broadcastTitle(server, color + secsLeft);
                    } else if (secsLeft == 10) {
                        broadcastTitle(server, "§e10");
                    }
                }

                if (graceTicksLeft == 0) {
                    broadcast(server, "§a§lGO! Hunters are released!");
                    broadcastTitle(server, "§a§lGO!");
                }
                return;
            }

            // Check win conditions every 20 ticks
            if (server.getTicks() % 20 != 0) return;

            // Check if runner is dead (all runners offline counts as hunters win for simplicity)
            boolean allRunnersGone = true;
            for (UUID id : runners) {
                if (server.getPlayerManager().getPlayer(id) != null) {
                    allRunnersGone = false;
                    break;
                }
            }

            // Check dragon kill via advancement
            for (UUID runnerId : runners) {
                ServerPlayerEntity runner = server.getPlayerManager().getPlayer(runnerId);
                if (runner == null) continue;
                Advancement dragonAdv = server.getAdvancementLoader().get(new Identifier("minecraft", "end/kill_dragon"));
                if (dragonAdv != null && runner.getAdvancementTracker().getProgress(dragonAdv).isDone()) {
                    broadcast(server, "§b§l" + runner.getName().getString() + " killed the dragon! Runner wins!");
                    endGame(server, null);
                    return;
                }
            }

            // Track runner dimension changes to record portal positions
            for (UUID runnerId : runners) {
                ServerPlayerEntity runner = server.getPlayerManager().getPlayer(runnerId);
                if (runner == null) continue;
                String currentDim = runner.world.getRegistryKey().getValue().toString();
                String lastDim = runnerLastDimension.get(runnerId);
                if (lastDim != null && !lastDim.equals(currentDim)) {
                    // Runner just changed dimension.
                    // Record arrival pos in new dim (portal in runnerDim side).
                    // Also record that the runner was last seen in lastDim — stored under lastDim key so hunters
                    // in lastDim can point to where runner exited.
                    Map<String, BlockPos> portals = runnerPortalPositions.computeIfAbsent(runnerId, k -> new HashMap<>());
                    portals.put(currentDim, runner.getBlockPos());
                    // departure pos stored in pending map, applied next tick when we still have the old pos
                    // Actually we already lost the old pos — store current as best approximation for lastDim too
                    // (arrival in currentDim ≈ portal in currentDim; departure from lastDim is unknown here)
                    // The departure pos was captured last tick — use runnerLastPortalDeparturePos
                    BlockPos departurePos = runnerLastPos.get(runnerId);
                    if (departurePos != null) {
                        portals.put(lastDim, departurePos);
                    }
                }
                runnerLastDimension.put(runnerId, currentDim);
                runnerLastPos.put(runnerId, runner.getBlockPos());
            }

            // Update compasses
            for (UUID hunterId : hunters) {
                ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterId);
                if (hunter == null) continue;

                ServerPlayerEntity nearestRunner = null;
                double nearestDist = Double.MAX_VALUE;
                for (UUID runnerId : runners) {
                    ServerPlayerEntity runner = server.getPlayerManager().getPlayer(runnerId);
                    if (runner == null) continue;
                    double dist = hunter.squaredDistanceTo(runner);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearestRunner = runner;
                    }
                }

                if (nearestRunner == null) continue;
                updateCompass(hunter, nearestRunner);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!gameRunning) return;
            if (server.getTicks() % 20 != 0) return;

            // Check runners for death
            for (UUID id : new HashSet<>(runners)) {
                ServerPlayerEntity runner = server.getPlayerManager().getPlayer(id);
                if (runner != null && runner.isDead()) {
                    broadcast(server, "§c§l" + runner.getName().getString() + " was caught! Hunters win!");
                    endGame(server, null);
                    return;
                }
            }

            // Check hunters for death — restore compass on respawn
            for (UUID id : new HashSet<>(hunters)) {
                ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(id);
                if (hunter != null && !hunter.isDead() && pendingCompassRestore.contains(id)) {
                    pendingCompassRestore.remove(id);
                    removeAllManhuntCompasses(hunter);
                    giveCompassTo(hunter);
                }
                if (hunter != null && hunter.isDead() && hunters.contains(id)) {
                    pendingCompassRestore.add(id);
                }
            }
        });

    }

    private static final Set<UUID> pendingCompassRestore = new HashSet<>();

    private void broadcast(MinecraftServer server, String msg) {
        server.getPlayerManager().broadcastChatMessage(new LiteralText(msg), MessageType.SYSTEM, UUID.randomUUID());
    }

    private void broadcastTitle(MinecraftServer server, String msg) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(
                net.minecraft.network.packet.s2c.play.TitleS2CPacket.Action.TITLE,
                new LiteralText(msg)));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(
                net.minecraft.network.packet.s2c.play.TitleS2CPacket.Action.TIMES, null, 0, 25, 10));
        }
    }

    private void endGame(MinecraftServer server, String message) {
        if (message != null) broadcast(server, message);
        gameRunning = false;
        graceTicksLeft = 0;
        hunters.clear();
        runners.clear();
        pendingCompassRestore.clear();
        runnerPortalPositions.clear();
        runnerLastDimension.clear();
        runnerLastPos.clear();
    }

    private void removeAllManhuntCompasses(ServerPlayerEntity player) {
        for (int i = 0; i < player.inventory.size(); i++) {
            ItemStack s = player.inventory.getStack(i);
            if (s.getItem() == Items.COMPASS && s.hasTag() && s.getTag().contains("manhunt")) {
                player.inventory.removeStack(i);
                i--; // adjust index after removal
            }
        }
    }

    private void giveCompasses(MinecraftServer server) {
        for (UUID hunterId : hunters) {
            ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterId);
            if (hunter == null) continue;
            if (!hasCompass(hunter)) giveCompassTo(hunter);
        }
    }

    private boolean hasCompass(ServerPlayerEntity player) {
        for (int i = 0; i < player.inventory.size(); i++) {
            ItemStack s = player.inventory.getStack(i);
            if (s.getItem() == Items.COMPASS && s.hasTag() && s.getTag().contains("manhunt")) return true;
        }
        return false;
    }

    private void giveCompassTo(ServerPlayerEntity hunter) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        CompoundTag tag = compass.getOrCreateTag();
        tag.putBoolean("manhunt", true);
        compass.setCustomName(new LiteralText("§6Runner Tracker").formatted(Formatting.ITALIC));
        hunter.inventory.insertStack(compass);
    }

    private void updateCompass(ServerPlayerEntity hunter, ServerPlayerEntity runner) {
        String hunterDim = hunter.world.getRegistryKey().getValue().toString();
        String runnerDim = runner.world.getRegistryKey().getValue().toString();
        boolean sameDim = hunterDim.equals(runnerDim);

        // Dead zone check — XZ distance only, same dimension only
        double dx = hunter.getX() - runner.getX();
        double dz = hunter.getZ() - runner.getZ();
        double xzDist = Math.sqrt(dx * dx + dz * dz);
        boolean inDeadZone = sameDim && xzDist <= deadZoneRadius;

        // Find portal position in hunter's dimension that runner used to enter their current dim
        // i.e. the last position recorded when runner arrived in runnerDim (which is the portal in runnerDim)
        // For the hunter's compass to point at the portal on their side, we want the portal pos in hunterDim
        // That's the position runner was at when they LEFT hunterDim (entered another dim)
        BlockPos portalPos = null;
        if (!sameDim) {
            Map<String, BlockPos> portals = runnerPortalPositions.get(runner.getUuid());
            if (portals != null) {
                // The portal on hunter's side is where runner was when they last arrived in runnerDim from hunterDim.
                // We stored arrival position in runnerDim — to get the hunter-side portal we need the
                // position stored for hunterDim (where runner arrived when coming into hunterDim, or left from).
                // Simpler: store departure pos. We stored arrival in currentDim on dim change.
                // So portals.get(runnerDim) = where runner arrived in runnerDim = the portal in runnerDim.
                // portals.get(hunterDim) = where runner arrived in hunterDim = portal in hunterDim (hunter's side).
                portalPos = portals.get(hunterDim);
            }
        }

        String actionBar;
        if (!sameDim) {
            actionBar = portalPos != null ? "§7Runner is in another dimension! (portal marked)" : "§7Runner is in another dimension!";
        } else if (inDeadZone) {
            actionBar = "§eRunner is nearby!";
        } else {
            actionBar = "";
        }

        for (int i = 0; i < hunter.inventory.size(); i++) {
            ItemStack stack = hunter.inventory.getStack(i);
            if (stack.getItem() != Items.COMPASS || !stack.hasTag() || !stack.getTag().contains("manhunt")) continue;
            CompoundTag tag = stack.getOrCreateTag();

            if (inDeadZone) {
                // Point at a nonexistent dimension so the compass spins rather than pointing to spawn
                CompoundTag fakePos = new CompoundTag();
                fakePos.putInt("X", 0); fakePos.putInt("Y", 0); fakePos.putInt("Z", 0);
                tag.put("LodestonePos", fakePos);
                tag.putString("LodestoneDimension", "manhunt:void");
                tag.putBoolean("LodestoneTracked", false);
            } else if (!sameDim && portalPos != null) {
                // Point at runner's portal in hunter's dimension
                CompoundTag lodestonePos = new CompoundTag();
                lodestonePos.putInt("X", portalPos.getX());
                lodestonePos.putInt("Y", portalPos.getY());
                lodestonePos.putInt("Z", portalPos.getZ());
                tag.put("LodestonePos", lodestonePos);
                tag.putString("LodestoneDimension", hunterDim);
                tag.putBoolean("LodestoneTracked", false);
            } else if (!sameDim) {
                // No portal recorded yet — spin
                CompoundTag fakePos = new CompoundTag();
                fakePos.putInt("X", 0); fakePos.putInt("Y", 0); fakePos.putInt("Z", 0);
                tag.put("LodestonePos", fakePos);
                tag.putString("LodestoneDimension", "manhunt:void");
                tag.putBoolean("LodestoneTracked", false);
            } else {
                // Same dim, outside dead zone — track runner directly
                BlockPos pos = runner.getBlockPos();
                CompoundTag lodestonePos = new CompoundTag();
                lodestonePos.putInt("X", pos.getX());
                lodestonePos.putInt("Y", pos.getY());
                lodestonePos.putInt("Z", pos.getZ());
                tag.put("LodestonePos", lodestonePos);
                tag.putString("LodestoneDimension", runnerDim);
                tag.putBoolean("LodestoneTracked", false);
            }
        }

        hunter.sendMessage(new LiteralText(actionBar), true);
    }
}
