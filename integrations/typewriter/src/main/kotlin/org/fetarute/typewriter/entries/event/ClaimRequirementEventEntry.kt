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
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.entry.triggerAllFor
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskClaimEvent
import org.fetarute.typewriter.TaskSource
import org.fetarute.typewriter.allows
import kotlin.reflect.KClass

@Entry("fetarute_claim_requirement", "Refuse FetaruteTC driving tasks the player has not unlocked", Colors.YELLOW, "mdi:lock")
@ContextKeys(ClaimRequirementContextKeys::class)
/**
 * The `Claim Requirement` refuses a driving task when the player does not meet the requirements,
 * for example a line that is only unlocked further on in the story. Its triggers fire when it refused a task.
 *
 * It applies to tasks taken from the task board and to tasks given by plugins, Typewriter included:
 * an `Assign Trip` refused here fires its failed triggers. When a player takes over a scheduled train directly,
 * or drives on with the next trip at the terminus, refusing the task does not stop the train;
 * the player still drives it, only without a score.
 *
 * ## How could this be used?
 * Lock route R2 until the player has completed five trips on route R1, and tell them so when they try.
 */
class ClaimRequirementEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only trips of this route code. Leave blank for any route.")
    val route: String = "",
    @Help("Only tasks from these sources. Leave empty for any source.")
    val sources: List<TaskSource> = emptyList(),
    @Help("The player needs to meet all of these to take the task. Leave empty to never refuse.")
    val requirements: List<Criteria> = emptyList(),
) : EventEntry

enum class ClaimRequirementContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRIP(String::class),

    @KeyType(String::class)
    ROUTE(String::class),

    @KeyType(String::class)
    TAKEOVER_STATION(String::class),

    @KeyType(String::class)
    SOURCE(String::class),
}

@EntryListener(ClaimRequirementEventEntry::class, ignoreCancelled = true)
fun onTaskClaim(event: DriverTaskClaimEvent, query: Query<ClaimRequirementEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val task = event.task
    val refused = query.findWhere {
        (it.route.isBlank() || it.route.equals(task.routeCode(), ignoreCase = true)) &&
            it.sources.allows(task) &&
            !it.requirements.matches(player)
    }.toList()
    if (refused.isEmpty()) return
    event.isCancelled = true
    refused.triggerAllFor(player) {
        ClaimRequirementContextKeys.TRIP += task.tripCode()
        ClaimRequirementContextKeys.ROUTE += task.routeCode()
        ClaimRequirementContextKeys.TAKEOVER_STATION += task.takeoverStation().name()
        ClaimRequirementContextKeys.SOURCE += task.source()
    }
}
