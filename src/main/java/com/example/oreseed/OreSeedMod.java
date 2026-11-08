package com.example.oreseed;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Singleplayer only. Press K: the integrated server's real world generator
 * generates the chunks around you and the mod lists every deepslate coal ore
 * and deepslate emerald ore it finds. Because the real generator is used,
 * the positions are exact for the world's seed.
 */
public class OreSeedMod implements ClientModInitializer {

    private static final long EXPECTED_SEED = 6608149111735331168L;
    private static final int RADIUS_CHUNKS = 12; // 12 = 25x25 chunks. Raise it to search further.
    private static final int SCAN_MIN_Y = -64;   // bottom of the scan (-64 = bedrock layer)
    private static final int SCAN_MAX_Y = 16;    // top of the scan (deepslate ores only live below this)
    private static final long TICK_BUDGET_NS = 30_000_000L; // 30 ms of work per server tick

    private static KeyMapping scanKey;

    // shared between the client thread and the server thread
    private static volatile boolean startRequested = false;
    private static volatile boolean cancelRequested = false;
    private static volatile boolean running = false;
    private static volatile int reqX, reqY, reqZ;
    private static final Queue<String> messages = new ConcurrentLinkedQueue<>();

    // server-thread only
    private static List<int[]> order = null;
    private static int next = 0;
    private static BlockPos origin = BlockPos.ZERO;
    private static final List<BlockPos> coal = new ArrayList<>();
    private static final List<BlockPos> emerald = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        System.out.println("[OreSeed] 1.0.1 loaded");

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("oreseed", "main"));
        scanKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.oreseed.scan",
                75, // K
                category));

        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
        ServerTickEvents.END_SERVER_TICK.register(OreSeedMod::serverTick);
    }

    // ---------- client side ----------

    private void clientTick(Minecraft mc) {
        String m;
        while ((m = messages.poll()) != null) {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[OreSeed] " + m));
        }

        while (scanKey.consumeClick()) {
            if (mc.player == null) continue;
            if (mc.getSingleplayerServer() == null) {
                mc.player.sendSystemMessage(Component.literal("[OreSeed] Singleplayer worlds only."));
                continue;
            }
            if (running) {
                cancelRequested = true;
            } else {
                BlockPos p = mc.player.blockPosition();
                reqX = p.getX();
                reqY = p.getY();
                reqZ = p.getZ();
                startRequested = true;
            }
        }
    }

    // ---------- server side ----------

    private static void say(String s) {
        messages.add(s);
    }

    private static void serverTick(MinecraftServer server) {
        if (cancelRequested) {
            cancelRequested = false;
            if (running) {
                running = false;
                order = null;
                say("Scan cancelled.");
            }
        }

        if (startRequested) {
            startRequested = false;
            if (!running) startScan(server);
        }

        if (!running || order == null) return;

        ServerLevel level = server.overworld();
        long end = System.nanoTime() + TICK_BUDGET_NS;
        int before = next;

        while (next < order.size() && System.nanoTime() < end) {
            int[] c = order.get(next++);
            scanChunk(level, c[0], c[1]);
        }

        if (next / 100 > before / 100 && next < order.size()) {
            say("Scanned " + next + " / " + order.size() + " chunks...");
        }

        if (next >= order.size()) finish();
    }

    private static void startScan(MinecraftServer server) {
        ServerLevel level = server.overworld();
        long seed = level.getSeed();
        if (seed != EXPECTED_SEED) {
            say("Warning: this world's seed is " + seed + ", not " + EXPECTED_SEED + ". Scanning it anyway.");
        } else {
            say("World seed matches " + EXPECTED_SEED + ".");
        }

        origin = new BlockPos(reqX, reqY, reqZ);
        int ocx = reqX >> 4;
        int ocz = reqZ >> 4;

        order = new ArrayList<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                order.add(new int[] { ocx + dx, ocz + dz });
            }
        }
        // nearest chunks first
        order.sort(Comparator.comparingInt(c -> (c[0] - ocx) * (c[0] - ocx) + (c[1] - ocz) * (c[1] - ocz)));

        next = 0;
        coal.clear();
        emerald.clear();
        running = true;
        say("Scanning " + order.size() + " chunks around you. Press K again to cancel.");
    }

    private static void scanChunk(ServerLevel level, int cx, int cz) {
        ChunkAccess chunk = level.getChunk(cx, cz); // generates it with the real world generator if needed
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int minY = Math.max(level.getMinY(), SCAN_MIN_Y);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= SCAN_MAX_Y; y++) {
                    p.set(cx * 16 + x, y, cz * 16 + z);
                    Block b = chunk.getBlockState(p).getBlock();
                    if (b == Blocks.DEEPSLATE_COAL_ORE) {
                        coal.add(p.immutable());
                    } else if (b == Blocks.DEEPSLATE_EMERALD_ORE) {
                        emerald.add(p.immutable());
                    }
                }
            }
        }
    }

    private static void finish() {
        running = false;
        order = null;

        coal.sort(Comparator.comparingDouble(OreSeedMod::flatDistSqr));
        emerald.sort(Comparator.comparingDouble(OreSeedMod::flatDistSqr));

        say("Done. Deepslate emerald ore: " + emerald.size() + ", deepslate coal ore: " + coal.size());
        report("Emerald", emerald);
        report("Coal", coal);

        try {
            Path out = FabricLoader.getInstance().getGameDir().resolve("oreseed-results.txt");
            List<String> lines = new ArrayList<>();
            lines.add("Seed " + EXPECTED_SEED + ", scan centre " + origin.toShortString());
            lines.add("== Deepslate emerald ore (" + emerald.size() + ") ==");
            for (BlockPos b : emerald) lines.add(b.getX() + " " + b.getY() + " " + b.getZ());
            lines.add("== Deepslate coal ore (" + coal.size() + ") ==");
            for (BlockPos b : coal) lines.add(b.getX() + " " + b.getY() + " " + b.getZ());
            Files.write(out, lines);
            say("Full list saved to oreseed-results.txt in your Minecraft folder.");
        } catch (Exception e) {
            say("Could not write the results file: " + e.getMessage());
        }
    }

    // distance ignoring height, so it doesn't matter that you're standing on the surface
    private static double flatDistSqr(BlockPos b) {
        double dx = b.getX() - origin.getX();
        double dz = b.getZ() - origin.getZ();
        return dx * dx + dz * dz;
    }

    private static void report(String name, List<BlockPos> list) {
        if (list.isEmpty()) {
            say(name + ": none found in range.");
            return;
        }
        int n = Math.min(5, list.size());
        for (int i = 0; i < n; i++) {
            BlockPos b = list.get(i);
            int dist = (int) Math.sqrt(flatDistSqr(b));
            say(name + " #" + (i + 1) + ": " + b.getX() + " " + b.getY() + " " + b.getZ()
                    + " (" + dist + " blocks away horizontally)");
        }
    }
}
