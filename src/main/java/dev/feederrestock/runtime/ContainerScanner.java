package dev.feederrestock.runtime;

import dev.feederrestock.integration.FeederMarks;
import dev.feederrestock.logic.ContainerVisits;
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

/**
 * Reads block types only; container contents are never inferred before opening.
 *
 * <p>Sources are exclusively the containers registered with mapart-feeder. The mod
 * never searches for containers on its own: a chest that was not marked with the
 * feeder's key is never touched, no matter how close it is.</p>
 */
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

    /**
     * Other container subclasses may run item-specific use code, so they are only
     * accepted while the main hand is empty and nothing could be misplaced.
     */
    private static boolean supported(BlockState state, ItemStack mainHand) {
        boolean container = state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof ShulkerBoxBlock;
        if (!container) return false;
        return alwaysConsumesUse(state) || mainHand.isEmpty();
    }

    /**
     * Registered containers of this dimension that are reachable right now and still
     * worth opening. Nothing outside the mark list is ever considered.
     */
    public List<Candidate> findRegistered(MinecraftClient client, FeederMarks marks, String dimension,
                                          Map<Long, Long> cooldowns, ContainerVisits visits, long tick) {
        List<Candidate> result = new ArrayList<>();
        if (client.player == null || client.world == null) return result;
        ItemStack mainHand = client.player.getMainHandStack();
        Set<Long> seen = new HashSet<>();
        for (BlockPos marked : marks.positions(dimension)) {
            if (!client.world.isChunkLoaded(marked)) continue;
            BlockState state = client.world.getBlockState(marked);
            if (!supported(state, mainHand)) continue;
            long key = canonicalKey(client, marked, state);
            if (seen.contains(key) || visits.contains(key) || cooldowns.getOrDefault(key, 0L) > tick) continue;
            BlockPos reachable = marked;
            BlockHitResult hit = hit(client, marked);
            if (hit == null && state.getBlock() instanceof ChestBlock
                    && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                // The registered half may be the far one; the other half of the same
                // double chest is still the registered container.
                BlockPos other = marked.offset(ChestBlock.getFacing(state));
                BlockHitResult otherHit = hit(client, other);
                if (otherHit != null) {
                    reachable = other;
                    hit = otherHit;
                }
            }
            if (hit == null) continue;
            seen.add(key);
            result.add(new Candidate(reachable, key, hit));
        }
        Vec3d eye = client.player.getEyePos();
        result.sort(Comparator.comparingDouble(c -> eye.squaredDistanceTo(c.hit().getPos())));
        return result;
    }

    /** Re-raycasts right before interacting so moving away cancels the attempt. */
    public BlockHitResult hit(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.world == null || !client.world.isChunkLoaded(pos)) return null;
        BlockState state = client.world.getBlockState(pos);
        if (!supported(state, client.player.getMainHandStack())) return null;
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

    /**
     * Keep a visit while either half of a double chest remains within physical reach.
     * A little hysteresis keeps a visit from re-arming at the reach boundary.
     */
    public boolean isStillNearby(MinecraftClient client, long key) {
        if (client.player == null || client.world == null) return false;
        BlockPos pos = BlockPos.fromLong(key);
        if (client.player.canInteractWithBlockAt(pos, 1.0)) return true;
        if (client.world.isChunkLoaded(pos)) {
            BlockState state = client.world.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                return client.player.canInteractWithBlockAt(pos.offset(ChestBlock.getFacing(state)), 1.0);
            }
        }
        return false;
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
