package org.fetarute.typewriter

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import lirand.api.extensions.events.unregister
import lirand.api.extensions.server.registerEvents
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.fetarute.fetaruteTCAddon.api.event.GuardTripScoredEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 一名玩家的累计车掌成绩。 */
data class GuardStats(
    val trips: Int = 0,
    val completed: Int = 0,
    val totalPoints: Long = 0,
    val bestGrade: String = "",
    val lastPoints: Int = 0,
)

/**
 * 在线玩家累计车掌成绩的缓存：事实条目可能在异步线程读，不能每次查库。
 *
 * 进服时从 FetaruteTCAddon 读一次，之后每结算一趟就地累加（每趟成绩事件与车掌记录同一口径：只算按时刻表运行、做过作业的趟）。
 */
@Singleton
class GuardStatsCache : Initializable, Listener {
    private val stats = ConcurrentHashMap<UUID, GuardStats>()

    override suspend fun initialize() {
        plugin.registerEvents(this)
        server.onlinePlayers.forEach { load(it.uniqueId) }
    }

    override suspend fun shutdown() {
        unregister()
        stats.clear()
    }

    fun of(playerId: UUID): GuardStats = stats[playerId] ?: GuardStats()

    private fun load(playerId: UUID) {
        val api = guardApi() ?: return
        api.stats(playerId).thenCombine(api.records(playerId, 1)) { total, last ->
            GuardStats(
                total.tasks(),
                total.completed(),
                total.totalPoints(),
                total.bestGrade().orElse(""),
                last.firstOrNull()?.points() ?: 0,
            )
        }.thenAccept { loaded ->
            // 读库期间玩家已经下线的不要再放回来
            if (server.getPlayer(playerId) != null) stats[playerId] = loaded
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = load(event.player.uniqueId)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        stats.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onTripScored(event: GuardTripScoredEvent) {
        val score = event.score
        val completed = event.state == GuardTripState.COMPLETED.name
        stats.compute(event.playerId) { _, current ->
            val base = current ?: GuardStats()
            base.copy(
                trips = base.trips + 1,
                completed = base.completed + if (completed) 1 else 0,
                totalPoints = base.totalPoints + if (completed) score.points().coerceAtLeast(0) else 0,
                bestGrade = DriverStatsCache.better(base.bestGrade, score.grade()),
                lastPoints = score.points(),
            )
        }
    }
}
