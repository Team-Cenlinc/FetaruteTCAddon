package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildProgress;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.junit.jupiter.api.Test;

/** 编表任务在命令层的两处：status 里的"编表"段，以及编完落库前对库况的复查。 */
class FtaTimetableCommandBuildJobsTest {

  private static final UUID LINE = UUID.randomUUID();
  private static final UUID BUILT_ID = UUID.randomUUID();

  @Test
  void statusSaysSoWhenNothingIsBuilding() {
    List<String> lines = plain(FtaTimetableCommand.describeBuildJobs(List.of()));

    assertEquals(List.of("===== 编表 =====", "  当前没有正在进行的编表。"), lines);
  }

  @Test
  void statusShowsStageDetailAndBuildCountWithACancelButton() {
    TimetableBuildJobs jobs = new TimetableBuildJobs();
    TimetableBuildJobs.Job job = jobs.start("WS,MT/BASE", "alice", Set.of(LINE), "BASE").get();
    job.progress().stage(TimetableBuildProgress.Stage.RELAX);
    job.progress().detail("试 156s");
    job.progress().fullBuild();
    job.progress().fullBuild();

    String text = String.join("\n", plain(FtaTimetableCommand.describeBuildJobs(jobs.list())));

    assertTrue(text.contains("WS,MT/BASE"), text);
    assertTrue(text.contains("alice 发起"), text);
    assertTrue(text.contains("[取消]"), text);
    assertTrue(text.contains("放宽间隔搜索"), text);
    assertTrue(text.contains("试 156s"), text);
    assertTrue(text.contains("2 次"), text);
  }

  @Test
  void statusDropsTheCancelButtonOnceSaving() {
    TimetableBuildJobs jobs = new TimetableBuildJobs();
    TimetableBuildJobs.Job job = jobs.start("WS/BASE", "alice", Set.of(LINE), "BASE").get();
    job.progress().stage(TimetableBuildProgress.Stage.REPORT);
    job.progress().beginSaving();

    String text = String.join("\n", plain(FtaTimetableCommand.describeBuildJobs(jobs.list())));

    assertFalse(text.contains("[取消]"), text);
    assertTrue(text.contains("正在保存，不能取消"), text);
  }

  @Test
  void savingIsRefusedWhenTheTablesChangedDuringTheBuild() {
    Timetable built = table(BUILT_ID, TimetableStatus.DRAFT);

    assertTrue(
        FtaTimetableCommand.staleSaveReason("WS", built, Optional.empty()).isEmpty(), "库里本来没有：新建");
    assertTrue(
        FtaTimetableCommand.staleSaveReason(
                "WS", built, Optional.of(table(BUILT_ID, TimetableStatus.DRAFT)))
            .isEmpty(),
        "覆盖 build 开始时那份草稿");
    Optional<String> published =
        FtaTimetableCommand.staleSaveReason(
            "WS", built, Optional.of(table(BUILT_ID, TimetableStatus.PUBLISHED)));
    assertTrue(published.orElseThrow().contains("WS/BASE 在编表期间已投入运行"), published::toString);
    Optional<String> another =
        FtaTimetableCommand.staleSaveReason(
            "WS", built, Optional.of(table(UUID.randomUUID(), TimetableStatus.DRAFT)));
    assertTrue(another.orElseThrow().contains("另有一份草稿"), another::toString);
  }

  private static Timetable table(UUID id, TimetableStatus status) {
    return new Timetable(
        id,
        UUID.nameUUIDFromBytes("company".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        LINE,
        "BASE",
        "基本图",
        status,
        ZoneId.of("UTC"),
        0,
        86_400,
        List.of(),
        List.of(),
        List.of(),
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private static List<String> plain(List<Component> components) {
    return components.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
  }
}
