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
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.matches
import org.fetarute.fetaruteTCAddon.api.event.GuardTaskClaimEvent
import org.fetarute.typewriter.GuardTaskSource
import org.fetarute.typewriter.allows
import kotlin.reflect.KClass

@Entry("fetarute_guard_claim_requirement", "Refuse FetaruteTC guard duties the player has not unlocked", Colors.YELLOW, "mdi:lock")
@ContextKeys(GuardClaimRequirementContextKeys::class)
/**
 * The `Guard Claim Requirement` refuses a guard duty when the player does not meet the requirements,
 * for example a line that is only unlocked further on in the story. Its triggers fire when it refused a duty.
 *
 * It applies to duties taken from the guard board and to duties given by plugins, Typewriter included:
 * an `Assign Guard Duty` refused here fires its failed triggers. A player who goes on duty with `/fta guard on`
 * without taking a duty is not affected.
 *
 * ## How could this be used?
 * Lock the express line until the player has worked five trips as guard on the local line, and tell them so when they try.
 */
class GuardClaimRequirementEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only trips of this route code. Leave blank for any route.")
    val route: String = "",
    @Help("Only duties from these sources. Leave empty for any source.")
    val sources: List<GuardTaskSource> = emptyList(),
    @Help("The player needs to meet all of these to take the duty. Leave empty to never refuse.")
    val requirements: List<Criteria> = emptyList(),
) : EventEntry

enum class GuardClaimRequirementContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRIP(String::class),

    @KeyType(String::class)
    ROUTE(String::class),

    @KeyType(String::class)
    TAKEOVER_STATION(String::class),

    @KeyType(String::class)
    SOURCE(String::class),
}

@EntryListener(GuardClaimRequirementEventEntry::class, ignoreCancelled = true)
fun onGuardTaskClaim(event: GuardTaskClaimEvent, query: Query<GuardClaimRequirementEventEntry>) {
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
        GuardClaimRequirementContextKeys.TRIP += task.tripCode()
        GuardClaimRequirementContextKeys.ROUTE += task.routeCode()
        GuardClaimRequirementContextKeys.TAKEOVER_STATION += task.takeoverStation().name()
        GuardClaimRequirementContextKeys.SOURCE += task.source()
    }
}
