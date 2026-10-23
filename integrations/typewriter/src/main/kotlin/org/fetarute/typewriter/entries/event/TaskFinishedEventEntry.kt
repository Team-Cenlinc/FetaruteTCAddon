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

@Entry("fetarute_task_finished_event", "玩家的 FTCA 驾驶任务结束时", Colors.YELLOW, "mdi:train-car")
@ContextKeys(TaskFinishedContextKeys::class)
/**
 * “驾驶任务结束”事件：驾驶任务结束时触发——开到终点、放弃、还没接班就作废，或被中断。
 *
 * 得分与评级只在玩家真的开过车时才有，否则为 0 与空。
 *
 * 除了任务板与 Typewriter 派的任务，玩家直接接管调度列车、在终点站接着开下一趟、参加路考或路考练习时，
 * FetaruteTCAddon 也会记一个任务；用“来源”区分。
 *
 * ## 用法示例
 * 车次以 A 级以上完成时发奖励；任务被放弃时把剧情退回一步。
 */
class TaskFinishedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个动作派出的驾驶任务；留空不限。")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
    @Help("只要这个交路编号的车次；留空不限交路。")
    val route: String = "",
    @Help("只要以这种终态结束的；留空不限终态。")
    val state: Optional<TaskEndState> = Optional.empty(),
    @Help("只要这些来源的驾驶任务；留空不限来源。")
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
