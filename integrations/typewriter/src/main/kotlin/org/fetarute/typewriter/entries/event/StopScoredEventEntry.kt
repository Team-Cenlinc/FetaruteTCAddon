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
import org.fetarute.fetaruteTCAddon.api.event.DriverStopScoredEvent
import org.fetarute.typewriter.StopOutcome
import org.fetarute.typewriter.TaskSource
import org.fetarute.typewriter.allows
import org.fetarute.typewriter.assignedBy
import org.fetarute.typewriter.entries.action.AssignTripActionEntry
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_stop_scored_event", "FTCA 驾驶员每站停车评定时", Colors.YELLOW, "mdi:sign-direction")
@ContextKeys(StopScoredContextKeys::class)
/**
 * “每站停车评定”事件：驾驶员每到一站停车（或越站）、这一站评定完成时触发。
 *
 * ## 用法示例
 * 玩家停短时给一条教学提示，或数停准的站来发成就。
 */
class StopScoredEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个动作派出的驾驶任务里的停车；留空则任何停车都算，包括不领任务的驾驶。")
    val assignedBy: Ref<AssignTripActionEntry> = emptyRef(),
    @Help("只要这个站名的车站；留空不限车站。")
    val station: String = "",
    @Help("只要这个停车结果；留空不限结果。")
    val outcome: Optional<StopOutcome> = Optional.empty(),
    @Help("只要这些来源的驾驶任务里的停车；留空则任何停车都算，包括不领任务的驾驶。")
    val sources: List<TaskSource> = emptyList(),
) : EventEntry

enum class StopScoredContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    STATION(String::class),

    @KeyType(String::class)
    OUTCOME(String::class),

    @KeyType(Double::class)
    OFFSET(Double::class),

    @KeyType(String::class)
    TRAIN(String::class),
}

@EntryListener(StopScoredEventEntry::class)
fun onStopScored(event: DriverStopScoredEvent, query: Query<StopScoredEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val stop = event.stop
    val task = event.task.orElse(null)
    query.findWhere {
        (!it.assignedBy.isSet || task?.assignedBy(it.assignedBy) == true) &&
            (it.station.isBlank() || it.station.equals(stop.station(), ignoreCase = true)) &&
            it.outcome.map { wanted -> wanted.name == stop.outcome().name }.orElse(true) &&
            it.sources.allows(task)
    }.triggerAllFor(player) {
        StopScoredContextKeys.STATION += stop.station()
        StopScoredContextKeys.OUTCOME += stop.outcome().name
        StopScoredContextKeys.OFFSET += stop.offsetBlocks()
        StopScoredContextKeys.TRAIN += event.trainName
    }
}
