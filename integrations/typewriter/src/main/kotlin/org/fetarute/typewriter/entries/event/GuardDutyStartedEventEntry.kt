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
import com.typewritermc.loader.ListenerPriority
import org.fetarute.fetaruteTCAddon.api.event.GuardDutyStartEvent
import org.fetarute.typewriter.GuardTaskSource
import org.fetarute.typewriter.allows
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignGuardDutyActionEntry
import kotlin.reflect.KClass

@Entry("fetarute_guard_duty_started_event", "玩家上岗当 FTCA 车掌时", Colors.YELLOW, "mdi:bell-ring")
@ContextKeys(GuardDutyStartedContextKeys::class)
/**
 * “车掌上岗”事件：玩家当上一列车的车掌时触发——为车掌任务坐进车尾驾驶室，或用 `/fta guard on` 上岗。
 *
 * ## 用法示例
 * 用标题显示车次号，或开始一个“到下一站开门”的剧情目标。
 */
class GuardDutyStartedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个动作派出的车掌任务；留空则任何上岗都算，包括不领任务直接上岗。")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
    @Help("只要这些来源的车掌任务；留空则任何上岗都算，包括不领任务直接上岗。")
    val sources: List<GuardTaskSource> = emptyList(),
) : EventEntry

enum class GuardDutyStartedContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    TRIP(String::class),

    @KeyType(String::class)
    ROUTE(String::class),

    @KeyType(String::class)
    TAKEOVER_STATION(String::class),

    @KeyType(String::class)
    HANDOVER_STATION(String::class),
}

// 上岗前的可取消事件：别的插件取消了就不算上岗
@EntryListener(GuardDutyStartedEventEntry::class, priority = ListenerPriority.MONITOR, ignoreCancelled = true)
fun onGuardDutyStart(event: GuardDutyStartEvent, query: Query<GuardDutyStartedEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val task = event.task.orElse(null)
    query.findWhere {
        (!it.assignedBy.isSet || task?.assignedBy(it.assignedBy) == true) && it.sources.allows(task)
    }.triggerAllFor(player) {
        GuardDutyStartedContextKeys.TRAIN += event.trainName
        GuardDutyStartedContextKeys.TRIP += (task?.tripCode() ?: "")
        GuardDutyStartedContextKeys.ROUTE += (task?.routeCode() ?: "")
        GuardDutyStartedContextKeys.TAKEOVER_STATION += (task?.takeoverStation()?.name() ?: "")
        GuardDutyStartedContextKeys.HANDOVER_STATION += (task?.handoverStation()?.map { it.name() }?.orElse("") ?: "")
    }
}
