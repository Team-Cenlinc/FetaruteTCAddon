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
import com.typewritermc.core.entries.emptyRef
import org.fetarute.fetaruteTCAddon.api.event.GuardStopWorkedEvent
import org.fetarute.typewriter.GuardStopResult
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignGuardDutyActionEntry
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_stop_worked_event", "When a FetaruteTC guard finishes the work at a station", Colors.YELLOW, "mdi:door-sliding")
@ContextKeys(GuardStopWorkedContextKeys::class)
/**
 * The `Guard Stop Worked Event` is triggered each time the guard has worked a station (doors opened and closed,
 * starting signal given) and the train leaves it. Stations the train ran past, or left before the doors were
 * released, do not count.
 *
 * The departure watch may still be running when this fires; the final score is in the `Guard Trip Scored Event`.
 *
 * ## How could this be used?
 * Give a tutorial hint after a station where the doors timed out, or count clean stations for an achievement.
 */
class GuardStopWorkedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only stations of duties given by this action. Leave empty for any station, also without a duty.")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
    @Help("Only this station name. Leave blank for any station.")
    val station: String = "",
    @Help("Only this result. Leave empty for any result.")
    val result: Optional<GuardStopResult> = Optional.empty(),
) : EventEntry

enum class GuardStopWorkedContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    STATION(String::class),

    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    RESULT(String::class),

    @KeyType(Int::class)
    INCIDENTS(Int::class),
}

@EntryListener(GuardStopWorkedEventEntry::class)
fun onGuardStopWorked(event: GuardStopWorkedEvent, query: Query<GuardStopWorkedEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val work = event.work
    val result = GuardStopResult.of(work)
    val task = event.task.orElse(null)
    query.findWhere {
        (!it.assignedBy.isSet || task?.assignedBy(it.assignedBy) == true) &&
            (it.station.isBlank() || it.station.equals(work.station(), ignoreCase = true)) &&
            it.result.map { wanted -> wanted == result }.orElse(true)
    }.triggerAllFor(player) {
        GuardStopWorkedContextKeys.STATION += work.station()
        GuardStopWorkedContextKeys.TRAIN += event.trainName
        GuardStopWorkedContextKeys.RESULT += result.name
        GuardStopWorkedContextKeys.INCIDENTS += work.incidents()
    }
}
