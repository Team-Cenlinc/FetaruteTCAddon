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

@Entry("fetarute_guard_task_finished_event", "玩家的 FTCA 车掌任务结束时", Colors.YELLOW, "mdi:bell-check")
@ContextKeys(GuardTaskFinishedContextKeys::class)
/**
 * “车掌任务结束”事件：车掌任务结束时触发——值乘到终点站或交班站、放弃、还没上岗就作废（或列车没等到玩家开走了）、
 * 未完成，或被中断。
 *
 * 得分与评级只在玩家至少做了这一趟的一站作业时才有，否则为 0 与空。
 *
 * ## 用法示例
 * 车掌任务以 A 级以上完成时发奖励；列车没等到玩家开走了时把剧情退回一步。
 */
class GuardTaskFinishedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个动作派出的车掌任务；留空不限。")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
    @Help("只要这个交路编号的车次；留空不限交路。")
    val route: String = "",
    @Help("只要以这种终态结束的；留空不限终态。")
    val state: Optional<TaskEndState> = Optional.empty(),
    @Help("只要这些来源的车掌任务；留空不限来源。")
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
