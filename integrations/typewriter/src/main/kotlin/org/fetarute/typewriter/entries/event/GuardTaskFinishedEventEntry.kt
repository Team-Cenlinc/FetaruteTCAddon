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
import org.fetarute.fetaruteTCAddon.api.event.GuardTaskFinishedEvent
import org.fetarute.typewriter.GuardTaskSource
import org.fetarute.typewriter.TaskEndState
import org.fetarute.typewriter.allows
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignGuardDutyActionEntry
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_task_finished_event", "When a player's FetaruteTC guard duty ends", Colors.YELLOW, "mdi:bell-check")
@ContextKeys(GuardTaskFinishedContextKeys::class)
/**
 * The `Guard Task Finished Event` is triggered when a guard duty ends: worked to the terminus or handover station,
 * abandoned, expired before the player went on duty (or the train left without them), failed, or interrupted.
 *
 * Points and grade are only set when the player worked at least one station of the trip; otherwise they are 0 and empty.
 *
 * ## How could this be used?
 * Reward the player when the duty was completed with grade A or better,
 * or move the quest back a step when the train left without them.
 */
class GuardTaskFinishedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only duties given by this action. Leave empty for any duty.")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
    @Help("Only trips of this route code. Leave blank for any route.")
    val route: String = "",
    @Help("Only duties that ended this way. Leave empty for any end.")
    val state: Optional<TaskEndState> = Optional.empty(),
    @Help("Only duties from these sources. Leave empty for any source.")
    val sources: List<GuardTaskSource> = emptyList(),
) : EventEntry

enum class GuardTaskFinishedContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRIP(String::class),

    @KeyType(String::class)
    ROUTE(String::class),

    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    STATE(String::class),

    @KeyType(String::class)
    REASON(String::class),

    @KeyType(Int::class)
    POINTS(Int::class),

    @KeyType(String::class)
    GRADE(String::class),

    @KeyType(String::class)
    SOURCE(String::class),
}

@EntryListener(GuardTaskFinishedEventEntry::class)
fun onGuardTaskFinished(event: GuardTaskFinishedEvent, query: Query<GuardTaskFinishedEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val task = event.task
    val state = task.state().name
    query.findWhere {
        task.assignedBy(it.assignedBy) &&
            (it.route.isBlank() || it.route.equals(task.routeCode(), ignoreCase = true)) &&
            it.state.map { wanted -> wanted.name == state }.orElse(true) &&
            it.sources.allows(task)
    }.triggerAllFor(player) {
        GuardTaskFinishedContextKeys.TRIP += task.tripCode()
        GuardTaskFinishedContextKeys.ROUTE += task.routeCode()
        GuardTaskFinishedContextKeys.TRAIN += task.trainName().orElse("")
        GuardTaskFinishedContextKeys.STATE += state
        GuardTaskFinishedContextKeys.REASON += task.endReason()
        GuardTaskFinishedContextKeys.POINTS += task.points().orElse(0)
        GuardTaskFinishedContextKeys.GRADE += task.grade().orElse("")
        GuardTaskFinishedContextKeys.SOURCE += task.source()
    }
}
