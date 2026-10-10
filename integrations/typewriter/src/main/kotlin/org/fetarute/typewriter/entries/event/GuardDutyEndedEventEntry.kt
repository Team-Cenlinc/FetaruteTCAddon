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
import org.fetarute.fetaruteTCAddon.api.event.GuardDutyEndedEvent
import org.fetarute.typewriter.GuardDutyEndReason
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_duty_ended_event", "When a player goes off duty as a FetaruteTC guard", Colors.YELLOW, "mdi:bell-off")
@ContextKeys(GuardDutyEndedContextKeys::class)
/**
 * The `Guard Duty Ended Event` is triggered when the player stops being the guard of a train:
 * they went off duty, reached the handover station, missed the train, timed out too often,
 * logged off, or were taken off duty.
 *
 * ## How could this be used?
 * Hide the guard sidebar hints, or tell the player what to do next after the handover station.
 */
class GuardDutyEndedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only duties that ended for this reason. Leave empty for any reason.")
    val reason: Optional<GuardDutyEndReason> = Optional.empty(),
) : EventEntry

enum class GuardDutyEndedContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    REASON(String::class),
}

@EntryListener(GuardDutyEndedEventEntry::class)
fun onGuardDutyEnded(event: GuardDutyEndedEvent, query: Query<GuardDutyEndedEventEntry>) {
    val player = event.player.orElse(null) ?: return
    query.findWhere {
        it.reason.map { wanted -> wanted.name == event.reason }.orElse(true)
    }.triggerAllFor(player) {
        GuardDutyEndedContextKeys.TRAIN += event.trainName
        GuardDutyEndedContextKeys.REASON += event.reason
    }
}
