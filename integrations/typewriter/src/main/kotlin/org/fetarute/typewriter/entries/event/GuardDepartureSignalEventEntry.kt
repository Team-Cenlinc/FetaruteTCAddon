package org.fetarute.typewriter.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.ContextKeys
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.KeyType
import com.typewritermc.core.interaction.EntryContextKey
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.triggerAllFor
import org.fetarute.fetaruteTCAddon.api.event.GuardDepartureSignalEvent
import org.fetarute.typewriter.SignalGivenBy
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_departure_signal_event", "When a FetaruteTC guard gives the starting signal", Colors.YELLOW, "mdi:bell-ring-outline")
@ContextKeys(GuardDepartureSignalContextKeys::class)
/**
 * The `Guard Departure Signal Event` is triggered when the starting signal goes to the driver: the guard held the
 * buzzer, or the time ran out and the station gave it instead (counted as a timeout).
 *
 * ## How could this be used?
 * Teach the buzzer: praise the player the first time they give the signal themselves.
 */
class GuardDepartureSignalEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only this station name. Leave blank for any station.")
    val station: String = "",
    @Help("Only signals given this way. Leave empty for both.")
    val givenBy: Optional<SignalGivenBy> = Optional.empty(),
) : EventEntry

enum class GuardDepartureSignalContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    STATION(String::class),

    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    GIVEN_BY(String::class),
}

@EntryListener(GuardDepartureSignalEventEntry::class)
fun onGuardDepartureSignal(event: GuardDepartureSignalEvent, query: Query<GuardDepartureSignalEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val by = if (event.isByStation) SignalGivenBy.STATION else SignalGivenBy.GUARD
    query.findWhere {
        (it.station.isBlank() || it.station.equals(event.station, ignoreCase = true)) &&
            it.givenBy.map { wanted -> wanted == by }.orElse(true)
    }.triggerAllFor(player) {
        GuardDepartureSignalContextKeys.STATION += event.station
        GuardDepartureSignalContextKeys.TRAIN += event.trainName
        GuardDepartureSignalContextKeys.GIVEN_BY += by.name
    }
}
