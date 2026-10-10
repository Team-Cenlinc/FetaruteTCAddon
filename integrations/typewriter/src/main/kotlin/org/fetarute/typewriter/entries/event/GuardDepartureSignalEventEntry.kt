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
import org.fetarute.fetaruteTCAddon.api.event.GuardDepartureSignalEvent
import org.fetarute.typewriter.SignalGivenBy
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_departure_signal_event", "FTCA 车掌发出发车信号时", Colors.YELLOW, "mdi:bell-ring-outline")
@ContextKeys(GuardDepartureSignalContextKeys::class)
/**
 * “车掌发车信号”事件：发车信号传给驾驶员时触发——车掌按了发车铃，或时限到了由站台代发（记一次超时）。
 *
 * ## 用法示例
 * 教发车铃：玩家第一次自己发出发车信号时表扬一句。
 */
class GuardDepartureSignalEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个站名的车站；留空不限车站。")
    val station: String = "",
    @Help("只要以这种方式发出的信号；留空两种都算。")
    val givenBy: Optional<SignalGivenBy> = Optional.empty(),
) : EventEntry

enum class GuardDepartureSignalContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    STATION(String::class),

    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    GIVEN_BY(String::class),
}

@EntryListener(GuardDepartureSignalEventEntry::class)
fun onGuardDepartureSignal(event: GuardDepartureSignalEvent, query: Query<GuardDepartureSignalEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val by = if (event.isByStation) SignalGivenBy.STATION else SignalGivenBy.GUARD
    query.findWhere {
        (it.station.isBlank() || it.station.equals(event.station, ignoreCase = true)) &&
            it.givenBy.map { wanted -> wanted == by }.orElse(true)
    }.triggerAllFor(player) {
        GuardDepartureSignalContextKeys.STATION += event.station
        GuardDepartureSignalContextKeys.TRAIN += event.trainName
        GuardDepartureSignalContextKeys.GIVEN_BY += by.name
    }
}
