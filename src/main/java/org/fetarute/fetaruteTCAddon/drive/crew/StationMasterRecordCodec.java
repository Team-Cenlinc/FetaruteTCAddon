package org.fetarute.fetaruteTCAddon.drive.crew;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 站长班次的记录（{@code drive_task_records}，mode 为 {@link DriveTaskRecord#MODE_STATION_MASTER}）。
 *
 * <p>与信号员记录同一写法：时刻表用全零的哨兵 UUID，运营日取下岗时刻按服务器时区的日期，车次号与列车留空，交路代码一栏记站码，终态一栏记下岗原因。明细带 formatVersion
 * 与班次里的各项计数。
 */
public final class StationMasterRecordCodec {

  /** 明细格式版本。 */
  public static final int FORMAT_VERSION = 1;

  private StationMasterRecordCodec() {}

  /**
   * 一个班次的记录。
   *
   * @param id 记录 ID
   * @param serverId 产生记录的服务器；单服时为 {@code null}
   * @param result 成绩
   * @param zone 运营日按这个时区取下岗时刻的日期（服务器时区）
   */
  public static DriveTaskRecord record(
      UUID id, String serverId, StationMasterShift shift, ScoreRules.Result result, ZoneId zone) {
    LocalDate serviceDate = shift.end().atZone(zone).toLocalDate();
    return new DriveTaskRecord(
        id,
        serverId,
        shift.player(),
        shift.playerName(),
        SignallerRecordCodec.NO_TIMETABLE,
        "",
        serviceDate,
        shift.station(),
        "",
        DriveTaskRecord.MODE_STATION_MASTER,
        shift.endReason().name(),
        result.points(),
        result.grade().name(),
        shift.start(),
        shift.end(),
        encode(shift));
  }

  /** 明细 JSON：运营商、车站、时长与各项计数。键只增不改。 */
  public static String encode(StationMasterShift shift) {
    StationMasterShift.Counts counts = shift.counts();
    JsonObject root = new JsonObject();
    root.addProperty("formatVersion", FORMAT_VERSION);
    root.addProperty("role", "station-master");
    root.addProperty("operator", shift.operator());
    root.addProperty("station", shift.station());
    root.addProperty("durationSeconds", shift.duration().toSeconds());
    root.addProperty("signals", counts.signals());
    root.addProperty("autoSignals", counts.autoSignals());
    root.addProperty("holds", counts.holds());
    root.addProperty("holdsExpired", counts.holdsExpired());
    root.addProperty("arrivalOk", counts.arrivalOk());
    root.addProperty("arrivalMissed", counts.arrivalMissed());
    root.addProperty("arrivalAbsent", counts.arrivalAbsent());
    root.addProperty("departureOk", counts.departureOk());
    root.addProperty("departureMissed", counts.departureMissed());
    root.addProperty("emergencies", counts.emergencies());
    root.addProperty("clearedRiders", counts.clearedRiders());
    root.addProperty("simRight", counts.simRight());
    root.addProperty("simWrong", counts.simWrong());
    root.addProperty("simMissed", counts.simMissed());
    root.addProperty("endReason", shift.endReason().name().toLowerCase(Locale.ROOT));
    return root.toString();
  }
}
