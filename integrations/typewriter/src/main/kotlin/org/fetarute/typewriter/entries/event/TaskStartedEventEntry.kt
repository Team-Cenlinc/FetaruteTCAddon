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
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskStartedEvent
import org.fetarute.typewriter.TaskSource
import org.fetarute.typewriter.allows
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignTripActionEntry
import kotlin.reflect.KClass

@Entry("fetarute_task_started_event", "玩家开始驾驶 FTCA 驾驶任务时", Colors.YELLOW, "mdi:train")
@ContextKeys(TaskStartedContextKeys::class)
/**
 * “开始驾驶任务”事件：玩家接管驾驶任务的列车、开始驾驶时触发。
 *
 * ## 用法示例
 * 用标题显示车次号，或开始一个“开到下一站”的剧情目标。
 */
class TaskStartedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个动作派出的驾驶任务；留空不限。")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
    @Help("只要这个交路编号的车次；留空不限交路。")
    val route: String = "",
    @Help("只要这些来源的驾驶任务；留空不限来源。")
    val sources: List<TaskSource> = emptyList(),
) : EventEntry

enum class TaskStartedContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRIP(String::class),

    @KeyType(String::class)
    ROUTE(String::class),

    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    TAKEOVER_STATION(String::class),

    @KeyType(String::class)
    HANDOVER_STATION(String::class),

    @KeyType(String::class)
    SOURCE(String::class),
}

@EntryListener(TaskStartedEventEntry::class)
fun onTaskStarted(event: DriverTaskStartedEvent, query: Query<TaskStartedEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val task = event.task
    query.findWhere {
        task.assignedBy(it.assignedBy) &&
            (it.route.isBlank() || it.route.equals(task.routeCode(), ignoreCase = true)) &&
            it.sources.allows(task)
    }.triggerAllFor(player) {
        TaskStartedContextKeys.TRIP += task.tripCode()
        TaskStartedContextKeys.ROUTE += task.routeCode()
        TaskStartedContextKeys.TRAIN += event.trainName
        TaskStartedContextKeys.TAKEOVER_STATION += task.takeoverStation().name()
        TaskStartedContextKeys.HANDOVER_STATION += task.handoverStation().map { it.name() }.orElse("")
        TaskStartedContextKeys.SOURCE += task.source()
    }
}
