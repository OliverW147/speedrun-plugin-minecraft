package com.manhunt;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.advancement.Advancement;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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

    // Per-hunter tracked runner. Absent (or value not in `runners`) = track nearest runner (default).
    // Toggled by right-clicking the compass, which cycles: nearest -> runner0 -> runner1 -> ... -> nearest.
    private static final Map<UUID, UUID> hunterTarget = new HashMap<>();

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

        // Right-click the tracker compass to cycle which runner it tracks.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (world.isClient || !gameRunning) return TypedActionResult.pass(stack);
            if (!(player instanceof ServerPlayerEntity)) return TypedActionResult.pass(stack);
            if (!isManhuntCompass(stack)) return TypedActionResult.pass(stack);
            ServerPlayerEntity hunter = (ServerPlayerEntity) player;
            if (!hunters.contains(hunter.getUuid())) return TypedActionResult.pass(stack);

            cycleTarget(hunter);
            // Consume so the click doesn't also count as a normal item use; keeps the stack in hand
            // without triggering the client swing/use packet that success() would.
            return TypedActionResult.consume(stack);
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

            // Record where each runner has been, per dimension.
            // runnerPortalPositions.get(runner).get(dim) = most recent position that runner was seen at in `dim`.
            // Its presence for a dimension means "this runner has visited that dimension" — which is what the
            // compass uses to decide whether to point (visited) or spin (never visited).
            for (UUID runnerId : runners) {
                ServerPlayerEntity runner = server.getPlayerManager().getPlayer(runnerId);
                if (runner == null) continue;
                String currentDim = runner.world.getRegistryKey().getValue().toString();
                String lastDim = runnerLastDimension.get(runnerId);
                Map<String, BlockPos> visited = runnerPortalPositions.computeIfAbsent(runnerId, k -> new HashMap<>());

                if (lastDim != null && !lastDim.equals(currentDim)) {
                    // Runner just changed dimension: pin the departure point in the dimension they left,
                    // so a hunter still in that dimension points at the portal/exit the runner used.
                    BlockPos departurePos = runnerLastPos.get(runnerId);
                    if (departurePos != null) visited.put(lastDim, departurePos);
                }

                // Always refresh the runner's current location in their current dimension. This both seeds
                // the runner's starting dimension (which never fires a change event) and keeps the
                // last-known position fresh while they remain in a dimension.
                visited.put(currentDim, runner.getBlockPos());
                runnerLastDimension.put(runnerId, currentDim);
                runnerLastPos.put(runnerId, runner.getBlockPos());
            }

            // Update compasses
            for (UUID hunterId : hunters) {
                ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterId);
                if (hunter == null) continue;

                ServerPlayerEntity targetRunner = resolveTarget(server, hunterId);
                if (targetRunner == null) continue;
                updateCompass(hunter, targetRunner);
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
        hunterTarget.clear();
    }

    private void removeAllManhuntCompasses(ServerPlayerEntity player) {
        for (int i = 0; i < player.inventory.size(); i++) {
            if (isManhuntCompass(player.inventory.getStack(i))) {
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
            if (isManhuntCompass(player.inventory.getStack(i))) return true;
        }
        return false;
    }

    private static boolean isManhuntCompass(ItemStack s) {
        return s.getItem() == Items.COMPASS && s.hasTag() && s.getTag().contains("manhunt");
    }

    private void giveCompassTo(ServerPlayerEntity hunter) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        CompoundTag tag = compass.getOrCreateTag();
        tag.putBoolean("manhunt", true);
        compass.setCustomName(new LiteralText("§6Runner Tracker").formatted(Formatting.ITALIC));
        hunter.inventory.insertStack(compass);
    }

    // Ordered, stable list of currently-online runners (sorted by name for a predictable cycle order).
    private List<ServerPlayerEntity> onlineRunners(MinecraftServer server) {
        List<ServerPlayerEntity> list = new ArrayList<>();
        for (UUID id : runners) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) list.add(p);
        }
        list.sort(Comparator.comparing(p -> p.getName().getString().toLowerCase()));
        return list;
    }

    // Resolve which runner a hunter's compass should track this tick.
    // Honors an explicit target if that runner is still online; otherwise falls back to nearest
    // (and clears a stale target so the compass name reverts to "nearest").
    private ServerPlayerEntity resolveTarget(MinecraftServer server, UUID hunterId) {
        ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterId);
        if (hunter == null) return null;
        UUID targetId = hunterTarget.get(hunterId);
        if (targetId != null) {
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetId);
            if (target != null && runners.contains(targetId)) return target;
            hunterTarget.remove(hunterId); // target left the game — revert to nearest
        }
        return getNearestRunner(server, hunter);
    }

    private ServerPlayerEntity getNearestRunner(MinecraftServer server, ServerPlayerEntity hunter) {
        ServerPlayerEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (UUID runnerId : runners) {
            ServerPlayerEntity runner = server.getPlayerManager().getPlayer(runnerId);
            if (runner == null) continue;
            double dist = hunter.squaredDistanceTo(runner);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = runner;
            }
        }
        return nearest;
    }

    // Advance this hunter's compass target one step: nearest -> runner0 -> runner1 -> ... -> nearest.
    private void cycleTarget(ServerPlayerEntity hunter) {
        MinecraftServer server = hunter.getServer();
        if (server == null) return;
        List<ServerPlayerEntity> ordered = onlineRunners(server);
        if (ordered.isEmpty()) return;

        UUID current = hunterTarget.get(hunter.getUuid());
        UUID next;
        String label;
        if (current == null) {
            // nearest -> first runner
            next = ordered.get(0).getUuid();
            label = ordered.get(0).getName().getString();
        } else {
            int idx = -1;
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).getUuid().equals(current)) { idx = i; break; }
            }
            if (idx < 0 || idx == ordered.size() - 1) {
                next = null; // wrap back to nearest after the last runner (or if current target vanished)
                label = null;
            } else {
                next = ordered.get(idx + 1).getUuid();
                label = ordered.get(idx + 1).getName().getString();
            }
        }

        if (next == null) {
            hunterTarget.remove(hunter.getUuid());
            hunter.sendMessage(new LiteralText("§6Tracking: §enearest runner"), true);
        } else {
            hunterTarget.put(hunter.getUuid(), next);
            hunter.sendMessage(new LiteralText("§6Tracking: §e" + label), true);
        }
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

        // Cross-dimension: look up the runner's last-known position in the HUNTER's dimension.
        // If absent, the tracked runner has never been to this dimension -> the compass will spin.
        BlockPos portalPos = null;
        if (!sameDim) {
            Map<String, BlockPos> visited = runnerPortalPositions.get(runner.getUuid());
            if (visited != null) portalPos = visited.get(hunterDim);
        }

        boolean locked = hunterTarget.containsKey(hunter.getUuid());
        String runnerName = runner.getName().getString();
        // What the compass is tracking, shown both as the item name and (when relevant) on the action bar.
        String trackingLabel = locked ? runnerName : runnerName + " §7(nearest)";

        String actionBar;
        if (!sameDim) {
            actionBar = "§7" + runnerName + " is in another dimension!" + (portalPos != null ? " §8(last seen here marked)" : "");
        } else if (inDeadZone) {
            actionBar = "§e" + runnerName + " is nearby!";
        } else {
            actionBar = "";
        }

        for (int i = 0; i < hunter.inventory.size(); i++) {
            ItemStack stack = hunter.inventory.getStack(i);
            if (!isManhuntCompass(stack)) continue;
            CompoundTag tag = stack.getOrCreateTag();
            stack.setCustomName(new LiteralText("§6Tracker: §e" + trackingLabel).formatted(Formatting.ITALIC));

            if (inDeadZone) {
                // Point at a nonexistent dimension so the compass spins rather than pointing to spawn
                CompoundTag fakePos = new CompoundTag();
                fakePos.putInt("X", 0); fakePos.putInt("Y", 0); fakePos.putInt("Z", 0);
                tag.put("LodestonePos", fakePos);
                tag.putString("LodestoneDimension", "manhunt:void");
                tag.putBoolean("LodestoneTracked", false);
            } else if (!sameDim && portalPos != null) {
                // Runner is in another dim but has been to this one — point at their last-known spot here
                CompoundTag lodestonePos = new CompoundTag();
                lodestonePos.putInt("X", portalPos.getX());
                lodestonePos.putInt("Y", portalPos.getY());
                lodestonePos.putInt("Z", portalPos.getZ());
                tag.put("LodestonePos", lodestonePos);
                tag.putString("LodestoneDimension", hunterDim);
                tag.putBoolean("LodestoneTracked", false);
            } else if (!sameDim) {
                // Tracked runner has never visited the hunter's dimension — spin
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
