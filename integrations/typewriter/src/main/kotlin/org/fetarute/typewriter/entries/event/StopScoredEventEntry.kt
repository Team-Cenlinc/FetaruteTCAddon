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
import org.fetarute.fetaruteTCAddon.api.event.DriverStopScoredEvent
import org.fetarute.typewriter.StopWindow
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignTripActionEntry
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_stop_scored_event", "When a FetaruteTC driver stops at a station", Colors.YELLOW, "mdi:sign-direction")
@ContextKeys(StopScoredContextKeys::class)
/**
 * The `Stop Scored Event` is triggered each time the driver stops at a station (or runs past it)
 * and the stop has been scored.
 *
 * ## How could this be used?
 * Give a tutorial hint when the player stops short, or count accurate stops for an achievement.
 */
class StopScoredEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only stops of tasks given by this action. Leave empty for any stop, also without a task.")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
    @Help("Only this station name. Leave blank for any station.")
    val station: String = "",
    @Help("Only this stop result. Leave empty for any result.")
    val window: Optional<StopWindow> = Optional.empty(),
) : EventEntry

enum class StopScoredContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    STATION(String::class),

    @KeyType(String::class)
    WINDOW(String::class),

    @KeyType(Double::class)
    OFFSET(Double::class),

    @KeyType(String::class)
    TRAIN(String::class),
}

@EntryListener(StopScoredEventEntry::class)
fun onStopScored(event: DriverStopScoredEvent, query: Query<StopScoredEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val stop = event.stop
    val task = event.task.orElse(null)
    query.findWhere {
        (!it.assignedBy.isSet || task?.assignedBy(it.assignedBy) == true) &&
            (it.station.isBlank() || it.station.equals(stop.station(), ignoreCase = true)) &&
            it.window.map { wanted -> wanted.name == stop.window().name }.orElse(true)
    }.triggerAllFor(player) {
        StopScoredContextKeys.STATION += stop.station()
        StopScoredContextKeys.WINDOW += stop.window().name
        StopScoredContextKeys.OFFSET += stop.offsetBlocks()
        StopScoredContextKeys.TRAIN += event.trainName
    }
}
