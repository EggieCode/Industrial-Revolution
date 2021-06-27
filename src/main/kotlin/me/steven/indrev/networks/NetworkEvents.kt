package me.steven.indrev.networks

import me.steven.indrev.blockentities.cables.BasePipeBlockEntity
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.block.entity.BlockEntity
import net.minecraft.server.world.ServerWorld

object NetworkEvents : ServerTickEvents.EndWorldTick, ServerBlockEntityEvents.Load {
    override fun onEndTick(world: ServerWorld) {
        Network.Type.ENERGY.getNetworkState(world).tick(world)
        Network.Type.FLUID.getNetworkState(world).tick(world)
        Network.Type.ITEM.getNetworkState(world).tick(world)
    }

    override fun onLoad(blockEntity: BlockEntity, world: ServerWorld) {
        if (blockEntity is BasePipeBlockEntity) {
            val networkState = blockEntity.pipeType.getNetworkState(world)
            networkState.queueUpdate(blockEntity.pos.asLong())
        }
    }
}