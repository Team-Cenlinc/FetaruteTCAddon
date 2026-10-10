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
import org.fetarute.fetaruteTCAddon.api.event.GuardIncidentReportEvent
import org.fetarute.typewriter.GuardIncident
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_incident_event", "When a FetaruteTC guard reports an incident", Colors.YELLOW, "mdi:alert-circle")
@ContextKeys(GuardIncidentContextKeys::class)
/**
 * The `Guard Incident Event` is triggered when the guard files an incident report at a station
 * (person or object caught in a door, crowded boarding, passenger assistance, equipment fault).
 * The current step gets more time; reports cost no points.
 *
 * ## How could this be used?
 * Start a side quest when the player reports a passenger asking for help.
 */
class GuardIncidentEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only this reason. Leave empty for any reason.")
    val incident: Optional<GuardIncident> = Optional.empty(),
    @Help("Only this station name. Leave blank for any station.")
    val station: String = "",
) : EventEntry

enum class GuardIncidentContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    INCIDENT(String::class),

    @KeyType(String::class)
    STATION(String::class),

    @KeyType(String::class)
    TRAIN(String::class),
}

@EntryListener(GuardIncidentEventEntry::class)
fun onGuardIncident(event: GuardIncidentReportEvent, query: Query<GuardIncidentEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val incident = event.incident.name
    query.findWhere {
        it.incident.map { wanted -> wanted.name == incident }.orElse(true) &&
            (it.station.isBlank() || it.station.equals(event.station, ignoreCase = true))
    }.triggerAllFor(player) {
        GuardIncidentContextKeys.INCIDENT += incident
        GuardIncidentContextKeys.STATION += event.station
        GuardIncidentContextKeys.TRAIN += event.trainName
    }
}
