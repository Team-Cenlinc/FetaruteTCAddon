package org.fetarute.typewriter.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.ContextKeys
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.KeyType
import com.typewritermc.core.interaction.EntryContextKey
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.triggerAllFor
import org.fetarute.fetaruteTCAddon.api.event.GuardEmergencyStopEvent
import kotlin.reflect.KClass

@Entry("fetarute_guard_emergency_event", "When a FetaruteTC guard pulls the emergency stop", Colors.YELLOW, "mdi:alarm-light")
@ContextKeys(GuardEmergencyContextKeys::class)
/**
 * The `Guard Emergency Event` is triggered when the guard pulls the emergency stop while the train is moving.
 *
 * ## How could this be used?
 * Ask the player in a dialogue why they stopped the train.
 */
class GuardEmergencyEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
) : EventEntry

enum class GuardEmergencyContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRAIN(String::class),
}

@EntryListener(GuardEmergencyEventEntry::class)
fun onGuardEmergency(event: GuardEmergencyStopEvent, query: Query<GuardEmergencyEventEntry>) {
    val player = event.player.orElse(null) ?: return
    query.find().triggerAllFor(player) {
        GuardEmergencyContextKeys.TRAIN += event.trainName
    }
}
