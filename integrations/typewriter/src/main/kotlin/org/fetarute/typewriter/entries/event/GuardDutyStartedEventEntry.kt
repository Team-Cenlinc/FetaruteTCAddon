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

@Entry("fetarute_guard_duty_started_event", "When a player goes on duty as a FetaruteTC guard", Colors.YELLOW, "mdi:bell-ring")
@ContextKeys(GuardDutyStartedContextKeys::class)
/**
 * The `Guard Duty Started Event` is triggered when the player goes on duty as the guard of a train:
 * by sitting in the rear cab for a guard duty, or with `/fta guard on`.
 *
 * ## How could this be used?
 * Show a title with the trip number, or start a quest objective "open the doors at the next station".
 */
class GuardDutyStartedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only duties given by this action. Leave empty for any duty, also going on duty without one.")
    val assignedBy: Ref<AssignGuardDutyActionEntry> = emptyRef(),
    @Help("Only duties from these sources. Leave empty for any duty, also going on duty without one.")
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
