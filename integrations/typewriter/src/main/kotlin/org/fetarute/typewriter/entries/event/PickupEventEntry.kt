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

@Entry("fetarute_pickup_event", "When a FetaruteTC train waits for its driver", Colors.YELLOW, "mdi:train-car-passenger-door")
@ContextKeys(PickupContextKeys::class)
/**
 * The `Pickup Event` is triggered as the train of a driving task waits for the player
 * at the terminus or in the depot: when it stops to wait, when the player boards,
 * and when the time is up and the train leaves without them.
 *
 * ## How could this be used?
 * Tell the player where the train is waiting ("your train is ready at platform 2"),
 * or move the quest back a step when the train left without them.
 */
class PickupEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only tasks given by this action. Leave empty for any task.")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
    @Help("Only tasks from these sources. Leave empty for any source.")
    val sources: List<TaskSource> = emptyList(),
    @Help("Only pickups at the terminus or in the depot. Leave empty for both.")
    val kind: Optional<PickupKind> = Optional.empty(),
    @Help("Only this step. Leave empty for every step.")
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
