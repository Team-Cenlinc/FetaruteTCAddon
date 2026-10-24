package org.fetarute.fetaruteTCAddon.drive.crew;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 信号员班次的记录（{@code drive_task_records}，mode 为 {@link DriveTaskRecord#MODE_SIGNALLER}）。
 *
 * <p>记录表按车次设计，时刻表与运营日不能为空：信号员按班次记，时刻表用全零的哨兵 UUID，运营日取下岗时刻按服务器时区的日期，车次号与列车留空，交路代码一栏记控制区代码，
 * 终态一栏记下岗原因。明细带 formatVersion 与班次里的各项计数。
 */
public final class SignallerRecordCodec {

  /** 明细格式版本。 */
  public static final int FORMAT_VERSION = 1;

  /** 信号员记录的时刻表 ID：没有时刻表，用全零的哨兵。 */
  public static final UUID NO_TIMETABLE = new UUID(0L, 0L);

  private SignallerRecordCodec() {}

  /**
   * 一个班次的记录。
   *
   * @param id 记录 ID
   * @param serverId 产生记录的服务器；单服时为 {@code null}
   * @param result 成绩
   * @param zone 运营日按这个时区取下岗时刻的日期（服务器时区）
   */
  public static DriveTaskRecord record(
      UUID id, String serverId, SignallerShift shift, ScoreRules.Result result, ZoneId zone) {
    LocalDate serviceDate = shift.end().atZone(zone).toLocalDate();
    return new DriveTaskRecord(
        id,
        serverId,
        shift.player(),
        shift.playerName(),
        NO_TIMETABLE,
        "",
        serviceDate,
        shift.zone(),
        "",
        DriveTaskRecord.MODE_SIGNALLER,
        shift.endReason().name(),
        result.points(),
        result.grade().name(),
        shift.start(),
        shift.end(),
        encode(shift));
  }

  /** 明细 JSON：运营商、控制区、时长与各项计数。键只增不改。 */
  public static String encode(SignallerShift shift) {
    JsonObject root = new JsonObject();
    root.addProperty("formatVersion", FORMAT_VERSION);
    root.addProperty("role", "signaller");
    root.addProperty("operator", shift.operator());
    root.addProperty("zone", shift.zone());
    if (!shift.parentZone().isEmpty()) {
      root.addProperty("parentZone", shift.parentZone());
    }
    root.addProperty("durationSeconds", shift.duration().toSeconds());
    root.addProperty("trainsHandled", shift.trainsHandled());
    root.addProperty("routesSet", shift.routesSet());
    root.addProperty("autoRouted", shift.autoRouted());
    root.addProperty("autoPassed", shift.autoPassed());
    root.addProperty("preRouted", shift.preRouted());
    root.addProperty("holds", shift.holds());
    root.addProperty("callsAnswered", shift.callsAnswered());
    root.addProperty("callsMissed", shift.callsMissed());
    root.addProperty("callsWrong", shift.callsWrong());
    root.addProperty("keptTime", shift.keptTime());
    root.addProperty("lostTime", shift.lostTime());
    root.addProperty("conflictsGood", shift.conflictsGood());
    root.addProperty("conflictsBad", shift.conflictsBad());
    root.addProperty("tripsCancelled", shift.tripsCancelled());
    root.addProperty("skipsOrdered", shift.skipsOrdered());
    root.addProperty("simRight", shift.simRight());
    root.addProperty("simWrong", shift.simWrong());
    root.addProperty("simMissed", shift.simMissed());
    root.addProperty("endReason", shift.endReason().name().toLowerCase(Locale.ROOT));
    return root.toString();
  }
}
