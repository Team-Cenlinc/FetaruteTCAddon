package org.fetarute.typewriter

import com.typewritermc.core.entries.Ref
import org.fetarute.fetaruteTCAddon.api.FetaruteApi
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi
import org.fetarute.typewriter.entries.action.AssignTripActionEntry

/** 本扩展派出的任务的来源标记。 */
internal const val SOURCE = "typewriter"

/** 派任务的动作条目 ID 记在任务附加数据的这个键下。 */
internal const val ENTRY_KEY = "entry"

/** FetaruteTCAddon 的驾驶任务 API；插件未加载时为 null。 */
internal fun driveApi(): DriveApi? = FetaruteApi.getInstance()?.drive()

/** 没指定动作条目时认所有任务；指定了只认这个条目派出的任务。 */
internal fun DriveApi.TaskView.assignedBy(entry: Ref<AssignTripActionEntry>): Boolean =
    !entry.isSet || (source() == SOURCE && metadata()[ENTRY_KEY] == entry.id)

/** 驾驶方式。 */
enum class DrivingMode(internal val api: DriveApi.Mode) {
    MANUAL(DriveApi.Mode.MANUAL),
    ATO(DriveApi.Mode.ATO),
}

/** 任务的终态。 */
enum class TaskEndState {
    COMPLETED,
    ABANDONED,
    EXPIRED,
    FAILED,
    INTERRUPTED,
}

/** 一站的停车结果。 */
enum class StopWindow {
    ACCURATE,
    ACCEPTED,
    SHORT,
    OVERRUN,
    SKIPPED,
}
