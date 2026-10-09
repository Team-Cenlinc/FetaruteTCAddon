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
import org.fetarute.fetaruteTCAddon.api.event.GuardTripScoredEvent
import org.fetarute.typewriter.GuardTripState
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignGuardDutyActionEntry
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_trip_scored_event", "When a FetaruteTC guard's trip is scored", Colors.YELLOW, "mdi:star-circle")
@ContextKeys(GuardTripScoredContextKeys::class)
/**
 * The `Guard Trip Scored Event` is triggered each time a trip the player worked as guard is scored:
 * when the train starts its next trip at the terminus, or when the player goes off duty.
 * Only scheduled trips with at least one worked station are scored.
 *
 * ## How could this be used?
 * Reward the player when the trip was worked to the end with grade A or better.
 */
class GuardTripScoredEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only the trip of a duty given by this action. Leave empty for any trip, also without a duty.")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
    @Help("Only trips of this route code. Leave blank for any route.")
    val route: String = "",
    @Help("Only trips that ended this way. Leave empty for any end.")
    val state: Optional<GuardTripState> = Optional.empty(),
) : EventEntry

enum class GuardTripScoredContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRIP(String::class),

    @KeyType(String::class)
    ROUTE(String::class),

    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    STATE(String::class),

    @KeyType(Int::class)
    POINTS(Int::class),

    @KeyType(String::class)
    GRADE(String::class),

    @KeyType(Int::class)
    STATIONS(Int::class),

    @KeyType(Double::class)
    KILOMETRES(Double::class),
}

@EntryListener(GuardTripScoredEventEntry::class)
fun onGuardTripScored(event: GuardTripScoredEvent, query: Query<GuardTripScoredEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val task = event.task.orElse(null)
    val score = event.score
    query.findWhere {
        (!it.assignedBy.isSet || task?.assignedBy(it.assignedBy) == true) &&
            (it.route.isBlank() || it.route.equals(event.routeCode, ignoreCase = true)) &&
            it.state.map { wanted -> wanted.name == event.state }.orElse(true)
    }.triggerAllFor(player) {
        GuardTripScoredContextKeys.TRIP += event.tripCode
        GuardTripScoredContextKeys.ROUTE += event.routeCode
        GuardTripScoredContextKeys.TRAIN += event.trainName
        GuardTripScoredContextKeys.STATE += event.state
        GuardTripScoredContextKeys.POINTS += score.points()
        GuardTripScoredContextKeys.GRADE += score.grade()
        GuardTripScoredContextKeys.STATIONS += score.stops().size
        GuardTripScoredContextKeys.KILOMETRES += score.kilometres()
    }
}
