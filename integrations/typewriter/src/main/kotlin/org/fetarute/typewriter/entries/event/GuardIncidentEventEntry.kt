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
import org.fetarute.fetaruteTCAddon.api.event.GuardIncidentReportEvent
import org.fetarute.typewriter.GuardIncident
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_incident_event", "FTCA 车掌报告异常情况时", Colors.YELLOW, "mdi:alert-circle")
@ContextKeys(GuardIncidentContextKeys::class)
/**
 * “车掌异常报告”事件：车掌在车站提交异常情况报告时触发（夹人夹物、上下车拥挤、乘客求助、设备异常）。
 * 当前这一步的时限延长；报告不扣分。
 *
 * ## 用法示例
 * 玩家报告乘客求助时开启一段支线剧情。
 */
class GuardIncidentEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个原因；留空不限原因。")
    val incident: Optional<GuardIncident> = Optional.empty(),
    @Help("只要这个站名的车站；留空不限车站。")
    val station: String = "",
) : EventEntry

enum class GuardIncidentContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    INCIDENT(String::class),

    @KeyType(String::class)
    STATION(String::class),

    @KeyType(String::class)
    TRAIN(String::class),
}

@EntryListener(GuardIncidentEventEntry::class)
fun onGuardIncident(event: GuardIncidentReportEvent, query: Query<GuardIncidentEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val incident = event.incident.name
    query.findWhere {
        it.incident.map { wanted -> wanted.name == incident }.orElse(true) &&
            (it.station.isBlank() || it.station.equals(event.station, ignoreCase = true))
    }.triggerAllFor(player) {
        GuardIncidentContextKeys.INCIDENT += incident
        GuardIncidentContextKeys.STATION += event.station
        GuardIncidentContextKeys.TRAIN += event.trainName
    }
}
