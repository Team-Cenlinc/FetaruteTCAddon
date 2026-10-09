package org.fetarute.typewriter

import com.typewritermc.core.entries.Ref
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi
import org.fetarute.typewriter.entries.action.AssignGuardDutyActionEntry

/** 没指定动作条目时认所有车掌任务；指定了只认这个条目派出的任务。 */
internal fun GuardApi.TaskView.assignedBy(entry: Ref<AssignGuardDutyActionEntry>): Boolean =
    !entry.isSet || (source() == SOURCE && metadata()[ENTRY_KEY] == entry.id)

/** 车掌任务从哪里来；与 FetaruteTCAddon 车掌任务的来源标记一一对应，其他插件派的归为 [OTHER_PLUGIN]。 */
enum class GuardTaskSource(private val key: String?) {
    GUARD_BOARD("board"),
    TYPEWRITER(SOURCE),
    OTHER_PLUGIN(null),
    ;

    fun matches(source: String): Boolean =
        if (key != null) key == source else GuardTaskSource.entries.none { it.key == source }
}

/** 没选来源时认所有车掌任务（也认没接任务的值乘）；选了只认这些来源的任务。 */
internal fun List<GuardTaskSource>.allows(task: GuardApi.TaskView?): Boolean =
    isEmpty() || (task != null && any { it.matches(task.source()) })

/** 一趟车掌值乘的终态。 */
enum class GuardTripState {
    /** 开完这一趟。 */
    COMPLETED,

    /** 中途离开。 */
    ABANDONED,

    /** 被撤下、列车不在了等。 */
    INTERRUPTED,

    /** 连续超时、漏乘或换端没坐进车尾。 */
    FAILED,
}

/** 一站的作业结果。 */
enum class GuardStopResult {
    /** 各项都合格。 */
    CLEAN,

    /** 提前关门或监视不合格（各扣 2 分）。 */
    MINOR,

    /** 开错了车门。 */
    WRONG_DOOR,

    /** 有超时，由站台代开、代关或代发发车信号。 */
    TIMED_OUT,
    ;

    companion object {
        fun of(work: GuardApi.StopWork): GuardStopResult = when {
            work.timedOut() -> TIMED_OUT
            work.wrongDoor() -> WRONG_DOOR
            work.closedEarly() ||
                work.closingWatch().orElse(true) == false ||
                work.departureWatch().orElse(true) == false -> MINOR
            else -> CLEAN
        }
    }
}

/** 发车信号由谁发出。 */
enum class SignalGivenBy {
    /** 车掌按发车铃。 */
    GUARD,

    /** 发车铃超时，站台代发。 */
    STATION,
}

/** 异常情况报告的原因。 */
enum class GuardIncident {
    CAUGHT,
    CROWDED,
    PASSENGER,
    EQUIPMENT,
}

/** 值乘结束的原因。 */
enum class GuardDutyEndReason {
    /** 车掌自己结束（含放弃任务）。 */
    COMMAND,
    OFFLINE,
    DEATH,
    GAME_MODE,

    /** 列车不在了。 */
    TRAIN_GONE,

    /** 连续多站超时。 */
    TIMEOUTS,

    /** 列车开走时车掌不在车上。 */
    LEFT_BEHIND,

    /** 车掌功能被关掉。 */
    DISABLED,

    /** 管理员撤下。 */
    ADMIN,

    /** 终点站换端没坐进车尾。 */
    CAB_CHANGE,

    /** 车掌考试未通过。 */
    EXAM,

    /** 值乘到交班站。 */
    HANDOVER,
}
