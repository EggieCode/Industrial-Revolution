package me.steven.indrev.networks.item

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import me.steven.indrev.IndustrialRevolution
import me.steven.indrev.api.machines.Tier
import me.steven.indrev.blocks.machine.pipes.ItemPipeBlock
import me.steven.indrev.config.IRConfig
import me.steven.indrev.networks.EndpointData
import me.steven.indrev.networks.Network
import me.steven.indrev.networks.Node
import me.steven.indrev.utils.ReusableArrayDeque
import me.steven.indrev.utils.isLoaded
import me.steven.indrev.utils.itemStorageOf
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil
import net.minecraft.block.Block
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.gen.feature.TreeFeature
import java.util.*
import kotlin.collections.List
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.isNotEmpty
import kotlin.collections.set
import kotlin.collections.Map
import kotlin.collections.LinkedHashSet
import kotlin.math.ceil

class ItemNetwork(
    world: ServerWorld,
    pipes: MutableSet<BlockPos> = ObjectOpenHashSet(),
    containers: MutableMap<BlockPos, EnumSet<Direction>> = Object2ObjectOpenHashMap()
) : Network(Type.ITEM, world, pipes, containers) {

    var tier = Tier.MK1
    private val maxCableTransfer: Long
        get() = when (tier) {
            Tier.MK1 -> IRConfig.cables.itemPipeMk1
            Tier.MK2 -> IRConfig.cables.itemPipeMk2
            Tier.MK3 -> IRConfig.cables.itemPipeMk3
            else -> IRConfig.cables.itemPipeMk4
        }.toLong()

    private val deques = Object2ObjectOpenHashMap<BlockPos, EnumMap<EndpointData.Mode, ReusableArrayDeque<Node>>>()
    private var workQueue : LinkedHashSet<WorkQueueItem> = LinkedHashSet();
    private var workQueueCountPerTik : Int = 0;

    override fun tick(world: ServerWorld) {
        val state = Type.ITEM.getNetworkState(world) as ItemNetworkState
        if (containers.isEmpty()) {
            return
        }
        if (queue.isEmpty()) {
            buildQueue();
        }
        // Build queue on first tik
        if (world.time % 20 == 0L) {
            containers.forEach inner@{ (pos, directions) ->
                if (!world.isLoaded(pos)) {
                    return
                }
                var nodes = queue[pos] ?: return
                workQueue.add(WorkQueueItem(pos, directions, nodes))
            }
            // Divide by 19, first tik of the second go to building queue
            workQueueCountPerTik = ceil(workQueue.size.toDouble() / 19).toInt();

            IndustrialRevolution.LOGGER.warn("IndRev (%d, %d, %s): ItemNetwork Work Queue: %d (Per tick: %d)".format(world.time, this.hashCode(), this.pipes.first().toShortString(), workQueue.size, workQueueCountPerTik))
            return;
        }
        var iterator = workQueue.iterator()
        var counter = 0;
        while (iterator.hasNext()) {
           var item = iterator.next();

           item.directions.forEach { dir ->
               val nodes = queue[item.pos] ?: return@forEach
               val data = state.getEndpointData(item.pos.offset(dir), dir.opposite) ?: return@forEach
               val filterData = state.getFilterData(item.pos.offset(dir), dir.opposite)
               if (data.type == EndpointData.Type.INPUT) return@forEach

               val deque = getQueue(item.pos, data, filterData, nodes)

               if (data.type == EndpointData.Type.OUTPUT)
                   tickOutput(item.pos, dir, deque, state, data, filterData)
               else if (data.type == EndpointData.Type.RETRIEVER)
                   tickRetriever(item.pos, dir, deque, state, data, filterData)

               deque.resetHead()
            }

            iterator.remove();
            counter++;
            if(workQueueCountPerTik <= counter && world.time % 20 != 19L) {
                break;
            }
        }
//        IndustrialRevolution.LOGGER.warn("IndRev (%d, %d, %s): ItemNetwork Work Queue Done: %d".format(world.time, this.hashCode(), this.pipes.first().toShortString(), counter))
    }

    private fun getQueue(pos: BlockPos, data: EndpointData, filter: ItemFilterData, nodes: List<Node>): ReusableArrayDeque<Node> {
        var queuesByNodes = deques[pos]
        if (queuesByNodes == null) {
            queuesByNodes = EnumMap(EndpointData.Mode::class.java)
            this.deques[pos] = queuesByNodes
        }
        var queue = queuesByNodes[data.mode]
        if (queue == null) {
            queue = ReusableArrayDeque(nodes)
            queue.apply(data.mode!!.getItemSorter(world, data.type) { filter.matches(it) })
            queuesByNodes[data.mode] = queue
        }

        if (data.mode == EndpointData.Mode.ROUND_ROBIN || data.mode == EndpointData.Mode.RANDOM) {
            queue.apply(data.mode!!.getItemSorter(world, data.type) { filter.matches(it) })
        }

        return queue
    }

    private fun tickOutput(pos: BlockPos, dir: Direction, queue: ReusableArrayDeque<Node>, state: ItemNetworkState, data: EndpointData, filterData: ItemFilterData) {
        val extractable = itemStorageOf(world, pos, dir)
        var remaining = maxCableTransfer
        while (queue.isNotEmpty() && remaining > 0) {
            val node = queue.removeFirst()
            val (_, targetPos, _, targetDir) = node
            if (!world.isLoaded(targetPos)) continue
            val targetData = state.getEndpointData(targetPos.offset(targetDir), targetDir.opposite)
            val input = targetData == null || targetData.type == EndpointData.Type.INPUT
            if (!input) continue
            val targetFilterData = state.getFilterData(targetPos.offset(targetDir), targetDir.opposite)

            fun doMove() {
                val insertable = itemStorageOf(world, targetPos, targetDir)
                val moved = StorageUtil.move(extractable, insertable, { filterData.matches(it) && targetFilterData.matches(it) }, remaining, null)
                remaining -= moved
                if (moved > 0 && remaining > 0) {
                    doMove()
                }
            }
            doMove()
        }
    }

    private fun tickRetriever(pos: BlockPos, dir: Direction, queue: ReusableArrayDeque<Node>, state: ItemNetworkState, data: EndpointData, filterData: ItemFilterData) {
        val insertable = itemStorageOf(world, pos, dir)
        var remaining = maxCableTransfer
        while (queue.isNotEmpty() && remaining > 0) {
            val node = queue.removeFirst()
            val (_, targetPos, _, targetDir) = node
            if (!world.isLoaded(targetPos)) continue
            val targetData = state.getEndpointData(targetPos.offset(targetDir), targetDir.opposite)
            val isRetriever = targetData?.type == EndpointData.Type.RETRIEVER
            if (isRetriever) continue
            val targetFilterData = state.getFilterData(targetPos.offset(targetDir), targetDir.opposite)

           fun doMove() {
               val extractable = itemStorageOf(world, targetPos, targetDir)
               val moved = StorageUtil.move(extractable, insertable, { filterData.matches(it) && targetFilterData.matches(it) }, remaining, null)
               remaining -= moved
               if (moved > 0 && remaining > 0)
                   doMove()
           }
            doMove()
        }
    }

    override fun appendPipe(block: Block, blockPos: BlockPos) {
        val cable = block as? ItemPipeBlock ?: return
        this.tier = cable.tier
        super.appendPipe(block, blockPos)
    }

    data class WorkQueueItem (val pos: BlockPos, val directions: EnumSet<Direction>, val nodes: MutableList<Node>) {

    }
}