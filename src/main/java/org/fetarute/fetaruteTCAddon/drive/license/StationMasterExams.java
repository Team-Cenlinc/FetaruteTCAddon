package org.fetarute.fetaruteTCAddon.drive.license;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterShift;
import org.fetarute.fetaruteTCAddon.stationmaster.StationMasterExaminer;

/**
 * 站长的考试与练习：报名后到车站上岗，亲手给满几列车发车信号，其间考官安排一次模拟事件；按扣分判定（见 {@link StationMasterExam}）。
 *
 * <p>练习与考试同一套做法，只是不发证、不冷却：做满列数并处置对模拟事件（处置错了再来一次）计一次练习。考试与练习的这一班照常结算（考试写记录不发奖励，练习两样都不做）。只在主线程调用。
 */
final class StationMasterExams implements StationMasterExaminer {

  /** 一次考试或练习。 */
  private static final class Run {
    final String classId;
    final boolean practice;
    boolean onDuty;
    String station = "";
    int signals;
    int autoSignals;
    int watchMissed;
    int simWrong;
    int simMissed;

    /** 亲手发满几次发车信号后安排模拟事件。 */
    int drillAfter;

    Drill drill = Drill.NONE;
    boolean finished;

    Run(String classId, boolean practice) {
      this.classId = classId;
      this.practice = practice;
    }
  }

  /** 考官安排的模拟事件的进度。 */
  private enum Drill {
    NONE,
    DUE,
    OUT,
    DONE
  }

  private final FetaruteTCAddon plugin;
  private final LicenseService licenses;
  private final Map<UUID, Run> runs = new HashMap<>();

  StationMasterExams(FetaruteTCAddon plugin, LicenseService licenses) {
    this.plugin = plugin;
    this.licenses = licenses;
  }

  // ---- 报名 ----

  /** 报名考试或练习（等级、前置、冷却、练习次数已由调用方查过）。 */
  LicenseService.Reply start(Player player, LicenseClass license, boolean practice, Instant now) {
    if (plugin.getStationMasterService().isEmpty()) {
      return new LicenseService.Reply(
          "drive.license.exam.station-master-unavailable", Map.of("name", license.name()));
    }
    UUID id = player.getUniqueId();
    int minutes = licenses.config().examWindowMinutes();
    licenses.beginExam(
        player,
        new LicenseService.Exam(
            license.id(),
            LicenseClass.Exam.STATION_MASTER,
            now.plus(Duration.ofMinutes(minutes)),
            practice));
    Run run = new Run(license.id(), practice);
    int trains = license.examStops();
    run.drillAfter =
        trains <= 1 ? 0 : 1 + ThreadLocalRandom.current().nextInt(Math.max(1, trains - 1));
    runs.put(id, run);
    Map<String, String> values = new LinkedHashMap<>(licenses.passValues(license, ""));
    values.put("trains", String.valueOf(trains));
    values.put("window", String.valueOf(minutes));
    values.put("penalty", String.valueOf(StationMasterExam.PENALTY));
    String brief =
        practice
            ? "drive.license.practice.station-master.brief."
            : "drive.license.exam.brief.station-master.";
    for (String line : List.of("header", "how", "pass")) {
      licenses.tell(player, brief + line, values);
    }
    licenses.stationMasterHandbook().give(player, plugin.getLocaleManager(), true);
    return new LicenseService.Reply(brief + "handbook", values);
  }

  // ---- 考官 ----

  @Override
  public boolean examining(UUID player) {
    Run run = runs.get(player);
    return run != null && run.onDuty;
  }

  @Override
  public boolean practicing(UUID player) {
    Run run = runs.get(player);
    return run != null && run.onDuty && run.practice;
  }

  @Override
  public void onDutyStarted(UUID id, String station, Instant now) {
    Run run = runs.get(id);
    Player player = Bukkit.getPlayer(id);
    if (run == null || run.finished || player == null) {
      return;
    }
    run.onDuty = true;
    run.station = station == null ? "" : station;
    run.drill = run.drillAfter == 0 ? Drill.DUE : Drill.NONE;
    boolean late = licenses.exam(id).map(exam -> now.isAfter(exam.deadline())).orElse(false);
    if (late) {
      run.finished = true;
      licenses.tell(
          player,
          run.practice
              ? "drive.license.practice.station-master.expired"
              : "drive.license.exam.expired",
          Map.of("name", licenses.nameOf(run.classId)));
      endShiftLater(id, run);
      return;
    }
    licenses
        .config()
        .find(run.classId)
        .ifPresent(
            license -> {
              Map<String, String> values = progress(license, run);
              values.put("station", run.station);
              licenses.tell(player, "drive.license.exam.station-master.started", values);
            });
  }

  @Override
  public void onEvent(UUID id, Event event) {
    Run run = runs.get(id);
    if (run == null || !run.onDuty || run.finished) {
      return;
    }
    switch (event) {
      case SIGNAL -> run.signals++;
      case AUTO_SIGNAL -> run.autoSignals++;
      case WATCH_MISSED -> run.watchMissed++;
      case SIM_WRONG -> run.simWrong++;
      case SIM_MISSED -> run.simMissed++;
      default -> {}
    }
    if (run.drill == Drill.OUT) {
      switch (event) {
        case SIM_RIGHT -> run.drill = Drill.DONE;
          // 练习里处置错了或没按时处置再来一次，直到处置对；考试只来一次
        case SIM_WRONG, SIM_MISSED -> run.drill = run.practice ? Drill.DUE : Drill.DONE;
        case SIM_CANCELLED -> run.drill = Drill.DUE;
        default -> {}
      }
    }
    if (run.drill == Drill.NONE && event == Event.SIGNAL && run.signals >= run.drillAfter) {
      run.drill = Drill.DUE;
    }
    Optional<LicenseClass> license = licenses.config().find(run.classId);
    Player player = Bukkit.getPlayer(id);
    if (license.isEmpty() || player == null) {
      return;
    }
    String key =
        switch (event) {
          case SIGNAL -> "drive.license.exam.station-master.progress";
          case AUTO_SIGNAL -> "drive.license.exam.station-master.deduct-auto";
          case WATCH_MISSED -> "drive.license.exam.station-master.deduct-watch";
          case SIM_WRONG, SIM_MISSED -> "drive.license.exam.station-master.deduct-sim";
          default -> null;
        };
    if (key != null) {
      licenses.tell(player, key, progress(license.get(), run));
    }
    if (run.practice) {
      if (run.signals >= license.get().examStops() && run.drill == Drill.DONE) {
        finishPractice(id, player, run, license.get());
      }
      return;
    }
    StationMasterExam.judge(
            license.get(),
            run.signals,
            run.autoSignals,
            run.watchMissed,
            run.simWrong,
            run.simMissed,
            run.drill == Drill.DONE)
        .ifPresent(result -> finishExam(id, player, run, license.get(), result));
  }

  @Override
  public void onDutyEnded(UUID id, StationMasterShift.EndReason reason) {
    Run run = runs.get(id);
    if (run == null || !run.onDuty) {
      return;
    }
    run.onDuty = false;
    if (run.finished) {
      licenses.closeExam(id);
      return;
    }
    Optional<LicenseClass> license = licenses.config().find(run.classId);
    if (license.isEmpty()) {
      licenses.closeExam(id);
      return;
    }
    Player player = Bukkit.getPlayer(id);
    if (run.practice) {
      finishPractice(id, player, run, license.get());
      return;
    }
    finishExam(id, player, run, license.get(), StationMasterExam.ended());
  }

  // ---- 由操作许可证服务调用 ----

  /**
   * 过了报名时限：考试中正在值守的按作废了结这一班；练习中正在值守的接着练（练到下岗为止）；没上岗的交给调用方按超时了结。
   *
   * @return 已处理（调用方不必再管）
   */
  boolean expire(UUID id) {
    Run run = runs.get(id);
    if (run == null || !run.onDuty) {
      return false;
    }
    if (run.practice || run.finished) {
      return true;
    }
    licenses
        .config()
        .find(run.classId)
        .ifPresent(
            license ->
                finishExam(id, Bukkit.getPlayer(id), run, license, StationMasterExam.expired()));
    return true;
  }

  /** 正在值守站长考试或练习的这一班（放弃前要先下岗）。 */
  boolean onExamDuty(UUID id) {
    Run run = runs.get(id);
    return run != null && run.onDuty;
  }

  /** 考试或练习了结：忘掉这一次。 */
  void forget(UUID id) {
    runs.remove(id);
  }

  /** 每隔一会儿：该来模拟事件时请站长服务出一道（身旁没有合适的车就等）。 */
  void tick() {
    for (Map.Entry<UUID, Run> entry : new ArrayList<>(runs.entrySet())) {
      Run run = entry.getValue();
      if (run.finished || !run.onDuty || run.drill != Drill.DUE) {
        continue;
      }
      if (plugin
          .getStationMasterService()
          .map(service -> service.drill(entry.getKey()))
          .orElse(false)) {
        run.drill = Drill.OUT;
      }
    }
  }

  // ---- 了结 ----

  /** 考试判定：及格发证，不及格进入冷却，不计成绩的可以马上重考。正在值守时这一班随之结束（下一拍下岗）。 */
  private void finishExam(
      UUID id, Player player, Run run, LicenseClass license, ExamEvaluation.Result result) {
    run.finished = true;
    Map<String, String> values = progress(license, run);
    values.putAll(result.values());
    values.putAll(licenses.passValues(license, values.getOrDefault("points", "")));
    licenses.settleExam(
        id, player, license, result, "drive.license.exam.station-master", false, values);
    if (!run.onDuty) {
      licenses.closeExam(id);
      return;
    }
    endShiftLater(id, run);
  }

  /** 练习了结：做满列数并处置对模拟事件计一次练习，告诉玩家这一班按考试标准折算的得分。 */
  private void finishPractice(UUID id, Player player, Run run, LicenseClass license) {
    run.finished = true;
    boolean done = run.signals >= license.examStops() && run.drill == Drill.DONE;
    int runsDone = licenses.trainingRuns(id, license.id()) + (done ? 1 : 0);
    if (done) {
      licenses.countPractice(id, player, license.id(), runsDone);
    }
    if (player != null) {
      Map<String, String> values = progress(license, run);
      values.put("done", String.valueOf(runsDone));
      values.put("required", String.valueOf(license.trainingRuns()));
      licenses.tell(
          player,
          done
              ? "drive.license.practice.station-master.review.done"
              : "drive.license.practice.station-master.review.unfinished",
          values);
    }
    if (!run.onDuty) {
      licenses.closeExam(id);
      return;
    }
    endShiftLater(id, run);
  }

  /** 不在考官的回调里下岗：下一拍以 {@code EXAM} 结束这一班。到那时这次考试已经了结（玩家先下了岗）就什么也不做。 */
  private void endShiftLater(UUID id, Run run) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (runs.get(id) != run) {
                return;
              }
              boolean ended =
                  run.onDuty
                      && plugin
                          .getStationMasterService()
                          .map(service -> service.endDuty(id, StationMasterShift.EndReason.EXAM))
                          .orElse(false);
              if (!ended && runs.get(id) == run) {
                licenses.closeExam(id);
              }
            });
  }

  private static Map<String, String> progress(LicenseClass license, Run run) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("name", license.name());
    values.put("signals", String.valueOf(run.signals));
    values.put("trains", String.valueOf(license.examStops()));
    values.put(
        "points",
        String.valueOf(
            StationMasterExam.points(
                run.autoSignals, run.watchMissed, run.simWrong, run.simMissed)));
    values.put("min", String.valueOf(license.minPoints()));
    return values;
  }
}
