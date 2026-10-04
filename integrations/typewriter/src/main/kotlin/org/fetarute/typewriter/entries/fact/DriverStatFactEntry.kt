package org.fetarute.typewriter.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player
import org.fetarute.typewriter.DriverStatsCache
import org.koin.java.KoinJavaComponent

/** 读哪一项累计成绩。 */
enum class DriverStat {
    TASKS,
    COMPLETED,
    TOTAL_POINTS,
    BEST_GRADE,
    LAST_POINTS,
}

@Entry("fetarute_driver_stat_fact", "A FetaruteTC driving record of the player", Colors.PURPLE, "mdi:trophy")
/**
 * A [fact](/docs/creating-stories/facts) with one of the player's driving records.
 *
 * <fields.ReadonlyFactInfo />
 *
 * | Stat | Value |
 * |------|-------|
 * | Tasks | Trips the player drove |
 * | Completed | Trips driven to the end |
 * | Total points | Sum of points over trips driven to the end (same as the leaderboard) |
 * | Best grade | S = 5, A = 4, B = 3, C = 2, D = 1, none = 0 |
 * | Last points | Points of the last trip (0 to 100) |
 *
 * ## How could this be used?
 * Unlock a harder line after the player has completed five trips with grade B or better.
 */
class DriverStatFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("Which record to read.")
    val stat: DriverStat = DriverStat.COMPLETED,
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val stats = KoinJavaComponent.get<DriverStatsCache>(DriverStatsCache::class.java).of(player.uniqueId)
        val value = when (stat) {
            DriverStat.TASKS -> stats.tasks
            DriverStat.COMPLETED -> stats.completed
            DriverStat.TOTAL_POINTS -> stats.totalPoints.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            DriverStat.BEST_GRADE -> DriverStatsCache.gradeRank(stats.bestGrade)
            DriverStat.LAST_POINTS -> stats.lastPoints
        }
        return FactData(value)
    }
}
