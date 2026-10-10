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

@Entry("fetarute_guard_trip_scored_event", "FTCA 车掌的一趟结算时", Colors.YELLOW, "mdi:star-circle")
@ContextKeys(GuardTripScoredContextKeys::class)
/**
 * “车掌一趟结算”事件：玩家当车掌的一趟结算时触发——列车在终点站开下一趟时，或玩家离岗时。
 * 只结算按时刻表运行、至少做了一站作业的车次。
 *
 * ## 用法示例
 * 这一趟值乘到底、评级 A 以上时发奖励。
 */
class GuardTripScoredEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个动作派出的车掌任务里的那一趟；留空则任何一趟都算，包括不领任务的值乘。")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
    @Help("只要这个交路编号的车次；留空不限交路。")
    val route: String = "",
    @Help("只要以这种结局结束的一趟；留空不限结局。")
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
