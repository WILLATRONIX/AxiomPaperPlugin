package com.moulberry.axiom.packet;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.BlockBuffer;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public class SetBlockBufferPacketListener {

    private final AxiomPaper plugin;
    private final Method updateBlockEntityTicker;

    public SetBlockBufferPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;

        ReflectionRemapper reflectionRemapper = ReflectionRemapper.forReobfMappingsInPaperJar();
        String methodName = reflectionRemapper.remapMethodName(LevelChunk.class, "updateBlockEntityTicker", BlockEntity.class);

        try {
            this.updateBlockEntityTicker = LevelChunk.class.getDeclaredMethod(methodName, BlockEntity.class);
            this.updateBlockEntityTicker.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public void onReceive(ServerPlayer player, FriendlyByteBuf friendlyByteBuf) {
        ResourceKey<Level> worldKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        BlockBuffer buffer = new BlockBuffer();

        while (true) {
            long index = friendlyByteBuf.readLong();
            if (index == Long.MAX_VALUE) break;

            PalettedContainer<BlockState> palettedContainer = buffer.getOrCreateSection(index);
            palettedContainer.read(friendlyByteBuf);
        }

        player.getServer().execute(() -> {
            ServerLevel world = player.getServer().getLevel(worldKey);
            if (world == null) return;

            BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

            var lightEngine = world.getChunkSource().getLightEngine();

            BlockState emptyState = BlockBuffer.EMPTY_STATE;

            for (Long2ObjectMap.Entry<PalettedContainer<BlockState>> entry : buffer.entrySet()) {
                int cx = BlockPos.getX(entry.getLongKey());
                int cy = BlockPos.getY(entry.getLongKey());
                int cz = BlockPos.getZ(entry.getLongKey());
                PalettedContainer<BlockState> container = entry.getValue();

                if (cy < world.getMinSection() || cy >= world.getMaxSection()) {
                    continue;
                }

                LevelChunk chunk = world.getChunk(cx, cz);
                chunk.setUnsaved(true);

                LevelChunkSection section = chunk.getSection(world.getSectionIndexFromSectionY(cy));
                PalettedContainer<BlockState> sectionStates = section.getStates();
                boolean hasOnlyAir = section.hasOnlyAir();

                Heightmap worldSurface = null;
                Heightmap oceanFloor = null;
                Heightmap motionBlocking = null;
                Heightmap motionBlockingNoLeaves = null;
                for (Map.Entry<Heightmap.Types, Heightmap> heightmap : chunk.getHeightmaps()) {
                    switch (heightmap.getKey()) {
                        case WORLD_SURFACE -> worldSurface = heightmap.getValue();
                        case OCEAN_FLOOR -> oceanFloor = heightmap.getValue();
                        case MOTION_BLOCKING -> motionBlocking = heightmap.getValue();
                        case MOTION_BLOCKING_NO_LEAVES -> motionBlockingNoLeaves = heightmap.getValue();
                        default -> {}
                    }
                }

                sectionStates.acquire();
                try {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState blockState = container.get(x, y, z);
                                if (blockState == emptyState) continue;

                                int bx = cx*16 + x;
                                int by = cy*16 + y;
                                int bz = cz*16 + z;

                                blockPos.set(bx, by, bz);

                                if (hasOnlyAir && blockState.isAir()) {
                                    continue;
                                }

                                BlockState old = section.setBlockState(x, y, z, blockState, false);
                                if (blockState != old) {
                                    Block block = blockState.getBlock();
                                    motionBlocking.update(x, by, z, blockState);
                                    motionBlockingNoLeaves.update(x, by, z, blockState);
                                    oceanFloor.update(x, by, z, blockState);
                                    worldSurface.update(x, by, z, blockState);

                                    if (false) { // Full update
                                        old.onRemove(world, blockPos, blockState, false);

                                        if (sectionStates.get(x, y, z).is(block)) {
                                            blockState.onPlace(world, blockPos, old, false);
                                        }
                                    }

                                    boolean oldHasBlockEntity = old.hasBlockEntity();
                                    if (old.is(block)) {
                                        if (blockState.hasBlockEntity()) {
                                            BlockEntity blockEntity = chunk.getBlockEntity(blockPos, LevelChunk.EntityCreationType.CHECK);
                                            if (blockEntity == null) {
                                                blockEntity = ((EntityBlock)block).newBlockEntity(blockPos, blockState);
                                                if (blockEntity != null) {
                                                    chunk.addAndRegisterBlockEntity(blockEntity);
                                                }
                                            } else {
                                                blockEntity.setBlockState(blockState);

                                                try {
                                                    this.updateBlockEntityTicker.invoke(chunk, blockEntity);
                                                } catch (IllegalAccessException | InvocationTargetException e) {
                                                    throw new RuntimeException(e);
                                                }
                                            }
                                        }
                                    } else if (oldHasBlockEntity) {
                                        chunk.removeBlockEntity(blockPos);
                                    }

                                    world.getChunkSource().blockChanged(blockPos); // todo: maybe simply resend chunk instead of this?

                                    if (LightEngine.hasDifferentLightProperties(chunk, blockPos, old, blockState)) {
                                        lightEngine.checkBlock(blockPos);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    sectionStates.release();
                }

                boolean nowHasOnlyAir = section.hasOnlyAir();
                if (hasOnlyAir != nowHasOnlyAir) {
                    world.getChunkSource().getLightEngine().updateSectionStatus(SectionPos.of(cx, cy, cz), nowHasOnlyAir);
                }
            }
        });
    }

}