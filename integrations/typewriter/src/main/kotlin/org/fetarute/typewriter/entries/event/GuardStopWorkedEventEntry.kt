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

@Entry("fetarute_guard_stop_worked_event", "FTCA 车掌做完一站作业时", Colors.YELLOW, "mdi:door-sliding")
@ContextKeys(GuardStopWorkedContextKeys::class)
/**
 * “车掌做完一站”事件：车掌做完一站作业（开关车门、发出发车信号）、列车开出这一站时触发。
 * 越站的、没放行就开走的不算。
 *
 * 触发时出站监视可能还没结束；这一站的最终扣分以“车掌一趟结算”事件为准。
 *
 * ## 用法示例
 * 车门超时的那一站之后给一条教学提示，或数无扣分的站来发成就。
 */
class GuardStopWorkedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个动作派出的车掌任务里的站；留空则任何站都算，包括不领任务的值乘。")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
    @Help("只要这个站名的车站；留空不限车站。")
    val station: String = "",
    @Help("只要这个结果；留空不限结果。")
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
