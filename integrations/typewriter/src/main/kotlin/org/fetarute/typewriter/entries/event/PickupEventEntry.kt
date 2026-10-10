package org.fetarute.typewriter.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.ContextKeys
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.KeyType
import com.typewritermc.core.interaction.EntryContextKey
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.triggerAllFor
import org.fetarute.fetaruteTCAddon.api.event.DriverPickupEvent
import org.fetarute.typewriter.TaskSource
import org.fetarute.typewriter.allows
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignTripActionEntry
import java.util.Optional
import kotlin.reflect.KClass

/** 从哪里接车。 */
enum class PickupKind {
    /** 终点站待命车。 */
    TERMINAL,

    /** 车库出车。 */
    DEPOT,
}

/** 接车进展。 */
enum class PickupStage {
    /** 列车停着等驾驶员。 */
    WAITING,

    /** 驾驶员已上车接班。 */
    BOARDED,

    /** 等到时限，照常发车。 */
    EXPIRED,
}

@Entry("fetarute_pickup_event", "FTCA 列车等驾驶员接车时", Colors.YELLOW, "mdi:train-car-passenger-door")
@ContextKeys(PickupContextKeys::class)
/**
 * “接车”事件：驾驶任务的列车在终点站或车库等玩家时触发——停下等候时、玩家上车接班时、
 * 等到时限照常发车时。
 *
 * ## 用法示例
 * 告诉玩家列车停在哪里（“你的车在 2 站台等你”），或列车没等到玩家开走了时把剧情退回一步。
 */
class PickupEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个动作派出的驾驶任务；留空不限。")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
    @Help("只要这些来源的驾驶任务；留空不限来源。")
    val sources: List<TaskSource> = emptyList(),
    @Help("只要终点站或车库的接车；留空两种都算。")
    val kind: Optional<PickupKind> = Optional.empty(),
    @Help("只要这一步进展；留空每一步都算。")
    val stage: Optional<PickupStage> = Optional.empty(),
) : EventEntry

enum class PickupContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRIP(String::class),

    @KeyType(String::class)
    ROUTE(String::class),

    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    KIND(String::class),

    @KeyType(String::class)
    STAGE(String::class),

    @KeyType(String::class)
    LOCATION(String::class),
}

@EntryListener(PickupEventEntry::class)
fun onPickup(event: DriverPickupEvent, query: Query<PickupEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val task = event.task
    query.findWhere {
        task.assignedBy(it.assignedBy) &&
            it.sources.allows(task) &&
            it.kind.map { wanted -> wanted.name == event.kind.name }.orElse(true) &&
            it.stage.map { wanted -> wanted.name == event.stage.name }.orElse(true)
    }.triggerAllFor(player) {
        PickupContextKeys.TRIP += task.tripCode()
        PickupContextKeys.ROUTE += task.routeCode()
        PickupContextKeys.TRAIN += event.trainName
        PickupContextKeys.KIND += event.kind.name
        PickupContextKeys.STAGE += event.stage.name
        PickupContextKeys.LOCATION += event.location
    }
}
