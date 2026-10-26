package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("信号员班次记录：哨兵字段与明细")
class SignallerRecordCodecTest {

  @Test
  @DisplayName("按班次记：时刻表全零、运营日取下岗时刻的服务器时区日期、交路代码记控制区、终态记下岗原因")
  void sentinelFields() {
    SignallerShift shift = SignallerScoreTest.shift(12, 2, 4, 1);
    UUID id = UUID.randomUUID();
    ScoreRules.Result result = new ScoreRules.Result(85, ScoreRules.Grade.A);
    // 下岗 09:00Z：上海是 17:00 同一天，檀香山（UTC-10）是前一天 23:00
    DriveTaskRecord record =
        SignallerRecordCodec.record(id, "srv-1", shift, result, ZoneId.of("Asia/Shanghai"));

    assertEquals(id, record.id());
    assertEquals("srv-1", record.serverId());
    assertEquals(shift.player(), record.playerId());
    assertEquals("Sinapole", record.playerName());
    assertEquals(new UUID(0L, 0L), record.timetableId());
    assertEquals("", record.tripCode());
    assertEquals(LocalDate.of(2026, 10, 20), record.serviceDate());
    assertEquals("WSD", record.routeCode());
    assertEquals("", record.trainName());
    assertEquals(DriveTaskRecord.MODE_SIGNALLER, record.mode());
    assertEquals("OFF_DUTY", record.state());
    assertEquals(85, record.points());
    assertEquals("A", record.grade());
    assertEquals(shift.start(), record.startedAt());
    assertEquals(shift.end(), record.finishedAt());

    assertEquals(
        LocalDate.of(2026, 10, 19),
        SignallerRecordCodec.record(id, null, shift, result, ZoneId.of("Pacific/Honolulu"))
            .serviceDate());
  }

  @Test
  @DisplayName("明细记下取消车次与令越站的次数")
  void detailCountsCancellationsAndSkips() {
    SignallerShift shift =
        new SignallerShift(
            UUID.randomUUID(),
            "Sig",
            "SURC",
            "ABC",
            java.time.Instant.parse("2026-10-24T08:00:00Z"),
            java.time.Instant.parse("2026-10-24T09:00:00Z"),
            SignallerShift.Counts.builder()
                .tripsCancelled(2)
                .skipsOrdered(3)
                .simRight(4)
                .simWrong(5)
                .simMissed(6)
                .build(),
            SignallerShift.EndReason.OFF_DUTY);
    JsonObject root = JsonParser.parseString(SignallerRecordCodec.encode(shift)).getAsJsonObject();

    assertEquals(2, root.get("tripsCancelled").getAsInt());
    assertEquals(3, root.get("skipsOrdered").getAsInt());
    assertEquals(4, root.get("simRight").getAsInt());
    assertEquals(5, root.get("simWrong").getAsInt());
    assertEquals(6, root.get("simMissed").getAsInt());
    assertEquals(null, root.get("parentZone"), "普通控制区不写所属大区");
  }

  @Test
  @DisplayName("中心工位上的班次：控制区记临时代码，另记所属大区")
  void workstationShiftNamesItsParentZone() {
    SignallerShift shift =
        new SignallerShift(
            UUID.randomUUID(),
            "Sig",
            "SURC",
            "BIG~2",
            java.time.Instant.parse("2026-10-24T08:00:00Z"),
            java.time.Instant.parse("2026-10-24T09:00:00Z"),
            SignallerShift.Counts.builder().build(),
            SignallerShift.EndReason.OFF_DUTY,
            false,
            false,
            "BIG");
    JsonObject root = JsonParser.parseString(SignallerRecordCodec.encode(shift)).getAsJsonObject();

    assertEquals("BIG~2", root.get("zone").getAsString());
    assertEquals("BIG", root.get("parentZone").getAsString());
  }

  @Test
  @DisplayName("明细带格式版本、角色与班次里的各项计数")
  void detailJson() {
    JsonObject root =
        JsonParser.parseString(SignallerRecordCodec.encode(SignallerScoreTest.shift(12, 2, 4, 1)))
            .getAsJsonObject();
    assertEquals(SignallerRecordCodec.FORMAT_VERSION, root.get("formatVersion").getAsInt());
    assertEquals("signaller", root.get("role").getAsString());
    assertEquals("SURC", root.get("operator").getAsString());
    assertEquals("WSD", root.get("zone").getAsString());
    assertEquals(3600L, root.get("durationSeconds").getAsLong());
    assertEquals(12, root.get("trainsHandled").getAsInt());
    assertEquals(7, root.get("routesSet").getAsInt());
    assertEquals(2, root.get("autoRouted").getAsInt());
    assertEquals(4, root.get("autoPassed").getAsInt());
    assertEquals(2, root.get("preRouted").getAsInt());
    assertEquals(1, root.get("holds").getAsInt());
    assertEquals(3, root.get("callsAnswered").getAsInt());
    assertEquals(1, root.get("callsMissed").getAsInt());
    assertEquals("off_duty", root.get("endReason").getAsString());
  }
}
