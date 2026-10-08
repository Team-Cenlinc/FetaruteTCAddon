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
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskFinishedEvent
import org.fetarute.typewriter.TaskEndState
import org.fetarute.typewriter.TaskSource
import org.fetarute.typewriter.allows
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignTripActionEntry
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_task_finished_event", "When a player's FetaruteTC driving task ends", Colors.YELLOW, "mdi:train-car")
@ContextKeys(TaskFinishedContextKeys::class)
/**
 * The `Task Finished Event` is triggered when a driving task ends: driven to the end, abandoned,
 * expired before the player took over, or interrupted.
 *
 * Points and grade are only set when the player actually drove the train; otherwise they are 0 and empty.
 *
 * Besides tasks from the task board and from Typewriter, FetaruteTCAddon also records a task when a player
 * takes over a scheduled train directly, drives on with the next trip at the terminus, or takes a road test
 * or its practice run. Use the sources to tell them apart.
 *
 * ## How could this be used?
 * Reward the player when the trip was completed with grade A or better,
 * or move the quest back a step when the task was abandoned.
 */
class TaskFinishedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only tasks given by this action. Leave empty for any task.")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
    @Help("Only trips of this route code. Leave blank for any route.")
    val route: String = "",
    @Help("Only tasks that ended this way. Leave empty for any end.")
    val state: Optional<TaskEndState> = Optional.empty(),
    @Help("Only tasks from these sources. Leave empty for any source.")
    val sources: List<TaskSource> = emptyList(),
) : EventEntry

enum class TaskFinishedContextKeys(override val klass: KClass<*>) : EntryContextKey {
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

@EntryListener(TaskFinishedEventEntry::class)
fun onTaskFinished(event: DriverTaskFinishedEvent, query: Query<TaskFinishedEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val task = event.task
    val state = task.state().name
    val score = event.score.orElse(null)
    query.findWhere {
        task.assignedBy(it.assignedBy) &&
            (it.route.isBlank() || it.route.equals(task.routeCode(), ignoreCase = true)) &&
            it.state.map { wanted -> wanted.name == state }.orElse(true) &&
            it.sources.allows(task)
    }.triggerAllFor(player) {
        TaskFinishedContextKeys.TRIP += task.tripCode()
        TaskFinishedContextKeys.ROUTE += task.routeCode()
        TaskFinishedContextKeys.TRAIN += task.trainName().orElse("")
        TaskFinishedContextKeys.STATE += state
        TaskFinishedContextKeys.REASON += task.endReason()
        TaskFinishedContextKeys.POINTS += (score?.points() ?: 0)
        TaskFinishedContextKeys.GRADE += (score?.grade() ?: "")
        TaskFinishedContextKeys.SOURCE += task.source()
    }
}
