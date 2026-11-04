package dev.railrestock.runtime;

import dev.railrestock.config.RestockConfig;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads block types only; container contents are never inferred before opening. */
public final class ContainerScanner {
    public record Candidate(BlockPos pos, long key, BlockHitResult hit) { }

    /**
     * Vanilla container blocks whose {@code onUse} always returns SUCCESS, so a
     * non-empty main hand still opens them instead of using the held item. Using
     * them lets restocking run without touching the held hotbar slot.
     */
    private static boolean alwaysConsumesUse(BlockState state) {
        Class<?> type = state.getBlock().getClass();
        return type == ChestBlock.class || type == TrappedChestBlock.class
                || type == BarrelBlock.class || type == ShulkerBoxBlock.class;
    }

    private static boolean isContainer(BlockState state, RestockConfig config) {
        return (config.includeChests && state.getBlock() instanceof ChestBlock)
                || (config.includeBarrels && state.getBlock() instanceof BarrelBlock)
                || (config.includeShulkerBoxes && state.getBlock() instanceof ShulkerBoxBlock);
    }

    /**
     * Other container subclasses may run item-specific use code, so they are only
     * accepted while the main hand is empty and nothing could be misplaced.
     */
    private static boolean supported(BlockState state, RestockConfig config, ItemStack mainHand) {
        if (!isContainer(state, config)) return false;
        return alwaysConsumesUse(state) || mainHand.isEmpty();
    }

    public List<Candidate> find(MinecraftClient client, RestockConfig config,
                                Map<Long, Long> cooldowns, long tick) {
        List<Candidate> result = new ArrayList<>();
        if (client.player == null || client.world == null) return result;
        ItemStack mainHand = client.player.getMainHandStack();
        double reach = client.player.getBlockInteractionRange();
        // Conservative search bound only; it never extends actual interaction reach.
        int radius = Math.min(12, (int) Math.ceil(reach) + 1);
        BlockPos origin = client.player.getBlockPos();
        Set<Long> seen = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (!client.world.isChunkLoaded(pos)) continue;
                    BlockState state = client.world.getBlockState(pos);
                    if (!supported(state, config, mainHand)) continue;
                    long key = canonicalKey(client, pos, state);
                    if (seen.contains(key) || cooldowns.getOrDefault(key, 0L) > tick) continue;
                    BlockHitResult hit = hit(client, pos, config);
                    if (hit != null) {
                        seen.add(key);
                        result.add(new Candidate(pos, key, hit));
                    }
                }
            }
        }
        Vec3d eye = client.player.getEyePos();
        result.sort(Comparator.comparingDouble(c -> eye.squaredDistanceTo(c.hit().getPos())));
        return result;
    }

    /** Re-raycasts right before interacting so moving away cancels the attempt. */
    public BlockHitResult hit(MinecraftClient client, net.minecraft.util.math.BlockPos pos, RestockConfig config) {
        if (client.player == null || client.world == null || !client.world.isChunkLoaded(pos)) return null;
        BlockState state = client.world.getBlockState(pos);
        if (!supported(state, config, client.player.getMainHandStack())) return null;
        if (!client.player.canInteractWithBlockAt(pos, 0.0)) return null;
        if (state.getBlock() instanceof ChestBlock) {
            if (ChestBlock.isChestBlocked(client.world, pos)) return null;
            if (state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                BlockPos other = pos.offset(ChestBlock.getFacing(state));
                if (ChestBlock.isChestBlocked(client.world, other)) return null;
            }
        }
        Vec3d eye = client.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        // Sample visible faces too, without changing the player's view direction.
        Vec3d[] points = {center, center.add(0.40, 0, 0), center.add(-0.40, 0, 0),
                center.add(0, 0.40, 0), center.add(0, -0.40, 0),
                center.add(0, 0, 0.40), center.add(0, 0, -0.40)};
        double reachSq = Math.pow(client.player.getBlockInteractionRange(), 2);
        for (Vec3d point : points) {
            BlockHitResult hit = client.world.raycast(new RaycastContext(eye, point,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                    && eye.squaredDistanceTo(hit.getPos()) <= reachSq) return hit;
        }
        return null;
    }

    private long canonicalKey(MinecraftClient client, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.offset(ChestBlock.getFacing(state));
            if (client.world != null && client.world.getBlockState(other).getBlock() == state.getBlock()) {
                return Math.min(pos.asLong(), other.asLong());
            }
        }
        return pos.asLong();
    }
}
