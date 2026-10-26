package org.fetarute.fetaruteTCAddon.drive.license;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.drive.tutorial.DriveTutorials;
import org.fetarute.fetaruteTCAddon.signaller.SignallerExaminer;
import org.fetarute.fetaruteTCAddon.signaller.SignallerService;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 信号员考试与练习：报名后在时限内到任一控制台上岗。
 *
 * <p>考试从上岗（转人工）起，亲手排满几列车即交卷，按 {@link SignallerExam}
 * 判定；交卷、不及格或作废时这一班随之结束（按考试结算：写记录、不发奖励），要继续值守就重新上岗。 练习是带练（{@link
 * SignallerTutorial}）：一步步提示，做完全部步骤并用下岗命令下岗计一次练习；练习的这一班不写记录、不发奖励。
 *
 * <p>练习讲到“回复呼叫”那一步、考试亲手排到第几列时（随机），以区内一列车的名义来一次模拟呼叫（{@code SignallerService#drillCall}，不发给真的驾驶员）；
 * 练习里回错或超时就再来一次，直到回对。
 *
 * <p>考试期间临时挂上信号员权限，了结后收回（考过的按持证给）。只在服务器主线程调用。
 */
final class SignallerExams implements SignallerExaminer {

  /** 一名考生这一次的考试或练习。 */
  private static final class Run {
    final String classId;
    final boolean practice;
    final SignallerTutorial tutorial;
    final Set<String> tips = new HashSet<>();
    boolean onDuty;
    String zone = "";
    int manual;
    int autoRouted;
    int callsMissed;
    int callsWrong;

    /** 模拟呼叫的进度。 */
    Drill drill = Drill.NONE;

    /** 考试：亲手排到第几列后来模拟呼叫（0 为一上岗就来）。 */
    int drillAfter;

    /** 已判定（考试）或已讲评（练习），等这一班下岗后了结。 */
    boolean finished;

    Run(String classId, boolean practice) {
      this.classId = classId;
      this.practice = practice;
      this.tutorial = practice ? new SignallerTutorial() : null;
    }
  }

  /** 模拟呼叫的进度。 */
  private enum Drill {
    /** 还没到时候。 */
    NONE,
    /** 该来了：下一次巡查时发出（列车表里没有合适的车就等）。 */
    DUE,
    /** 已发出，等信号员回复。 */
    OUT,
    /** 已了结。 */
    DONE
  }

  private final FetaruteTCAddon plugin;
  private final LicenseService licenses;
  private final Map<UUID, Run> runs = new HashMap<>();

  SignallerExams(FetaruteTCAddon plugin, LicenseService licenses) {
    this.plugin = plugin;
    this.licenses = licenses;
  }

  // ---- 报名 ----

  /** 报名考试或练习（等级、前置、冷却、练习次数已由调用方查过）。 */
  LicenseService.Reply start(Player player, LicenseClass license, boolean practice, Instant now) {
    if (plugin.getSignallerService().filter(SignallerService::enabled).isEmpty()) {
      // 信号员功能关着：报了名也上不了岗，不登记。
      return new LicenseService.Reply(
          "drive.license.exam.signaller-unavailable", Map.of("name", license.name()));
    }
    UUID id = player.getUniqueId();
    int minutes = licenses.config().examWindowMinutes();
    licenses.beginExam(
        player,
        new LicenseService.Exam(
            license.id(),
            LicenseClass.Exam.SIGNALLER,
            now.plus(Duration.ofMinutes(minutes)),
            practice));
    Run run = new Run(license.id(), practice);
    int trains = license.examStops();
    run.drillAfter =
        trains <= 1 ? 0 : 1 + ThreadLocalRandom.current().nextInt(Math.max(1, trains - 1));
    runs.put(id, run);
    Map<String, String> values = new LinkedHashMap<>(licenses.passValues(license, ""));
    values.put("trains", String.valueOf(license.examStops()));
    values.put("window", String.valueOf(minutes));
    values.put("penalty", String.valueOf(SignallerExam.PENALTY));
    String brief =
        practice
            ? "drive.license.practice.signaller.brief."
            : "drive.license.exam.brief.signaller.";
    for (String line : List.of("header", "how", "pass")) {
      licenses.tell(player, brief + line, values);
    }
    if (!practice) {
      licenses.tell(player, "drive.license.exam.brief.signaller.fail", values);
    }
    licenses.signallerHandbook().give(player, plugin.getLocaleManager(), true);
    if (practice) {
      render(player, run.tutorial.begin(now));
    }
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
  public void onDutyStarted(UUID id, String zoneName, boolean manual, boolean stations) {
    Run run = current(id);
    Player player = Bukkit.getPlayer(id);
    if (run == null || run.finished || player == null) {
      return;
    }
    run.onDuty = true;
    run.zone = zoneName == null ? "" : zoneName;
    run.manual = 0;
    run.autoRouted = 0;
    run.callsMissed = 0;
    run.callsWrong = 0;
    run.drill = !run.practice && run.drillAfter == 0 ? Drill.DUE : Drill.NONE;
    boolean late =
        licenses.exam(id).map(exam -> Instant.now().isAfter(exam.deadline())).orElse(false);
    if (late) {
      // 报名时限已过、超时检查还没轮到：不开始，这一班随之结束（考试不计成绩）。
      run.finished = true;
      licenses.tell(
          player,
          run.practice ? "drive.license.practice.signaller.expired" : "drive.license.exam.expired",
          Map.of("name", licenses.nameOf(run.classId)));
      endShiftLater(id, run);
      return;
    }
    if (run.practice) {
      render(player, run.tutorial.dutyStarted(manual, stations, Instant.now()));
      drillWhenAsked(run);
      return;
    }
    Optional<LicenseClass> license = licenses.config().find(run.classId);
    if (license.isEmpty()) {
      return;
    }
    Map<String, String> values = new LinkedHashMap<>(licenses.passValues(license.get(), ""));
    values.put("zone", run.zone);
    values.put("trains", String.valueOf(license.get().examStops()));
    licenses.tell(player, "drive.license.exam.signaller.started", values);
    if (!manual) {
      licenses.tell(player, "drive.license.exam.signaller.wait-manual", values);
    }
  }

  @Override
  public void onEvent(UUID id, Event event) {
    Run run = current(id);
    if (run == null || !run.onDuty || run.finished) {
      return;
    }
    switch (event) {
      case ENTERED_MANUAL -> run.manual++;
      case ENTERED_AUTO_ROUTED -> run.autoRouted++;
      case CALL_MISSED, DRILL_MISSED -> run.callsMissed++;
      case CALL_WRONG, DRILL_WRONG -> run.callsWrong++;
      default -> {}
    }
    if (run.drill == Drill.OUT) {
      switch (event) {
        case DRILL_ANSWERED -> run.drill = Drill.DONE;
          // 练习里回错或超时再来一次，直到回对；考试只来一次
        case DRILL_WRONG, DRILL_MISSED -> run.drill =
            run.practice && run.tutorial.current() == SignallerTutorial.Step.ANSWER_CALL
                ? Drill.DUE
                : Drill.DONE;
          // 用的车不在了：再来一次；考试已排满时不再等它（列车表里未必还有车可拿来呼叫）
        case DRILL_CANCELLED -> run.drill =
            !run.practice
                    && licenses
                        .config()
                        .find(run.classId)
                        .filter(license -> run.manual >= license.examStops())
                        .isPresent()
                ? Drill.DONE
                : Drill.DUE;
        default -> {}
      }
    }
    if (!run.practice
        && run.drill == Drill.NONE
        && event == Event.ENTERED_MANUAL
        && run.manual >= run.drillAfter) {
      run.drill = Drill.DUE;
    }
    Player player = Bukkit.getPlayer(id);
    if (player == null) {
      return;
    }
    if (run.practice) {
      render(player, run.tutorial.event(event, Instant.now()));
      tip(player, run, event);
      drillWhenAsked(run);
      return;
    }
    Optional<LicenseClass> license = licenses.config().find(run.classId);
    if (license.isEmpty()) {
      return;
    }
    switch (event) {
      case AUTO -> finishExam(id, player, run, license.get(), SignallerExam.toAuto());
      case IDLE_AUTO -> finishExam(id, player, run, license.get(), SignallerExam.idleAuto());
      case ENTERED_MANUAL,
          ENTERED_AUTO_ROUTED,
          CALL_MISSED,
          DRILL_MISSED,
          CALL_WRONG,
          DRILL_WRONG,
          DRILL_ANSWERED,
          DRILL_CANCELLED -> {
        Map<String, String> values = progress(license.get(), run);
        String key =
            switch (event) {
              case ENTERED_MANUAL -> "drive.license.exam.signaller.progress";
              case ENTERED_AUTO_ROUTED -> "drive.license.exam.signaller.deduct-auto-routed";
              case CALL_MISSED, DRILL_MISSED -> "drive.license.exam.signaller.deduct-call-missed";
              case CALL_WRONG, DRILL_WRONG -> "drive.license.exam.signaller.deduct-call-wrong";
              default -> null;
            };
        if (key != null) {
          licenses.tell(player, key, values);
        }
        SignallerExam.judge(
                license.get(),
                run.manual,
                run.autoRouted,
                run.callsMissed,
                run.callsWrong,
                run.drill == Drill.DONE)
            .ifPresent(result -> finishExam(id, player, run, license.get(), result));
      }
      default -> {}
    }
  }

  @Override
  public void onDutyEnded(UUID id, SignallerShift.EndReason reason) {
    Run run = runs.get(id);
    if (run == null || !run.onDuty) {
      return;
    }
    run.onDuty = false;
    Player player = Bukkit.getPlayer(id);
    if (run.finished) {
      licenses.closeExam(id);
      return;
    }
    Optional<LicenseClass> license = licenses.config().find(run.classId);
    if (license.isEmpty()) {
      licenses.closeExam(id);
      return;
    }
    if (run.practice) {
      List<SignallerTutorial.Change> changes = run.tutorial.dutyEnded(reason, Instant.now());
      if (player != null) {
        render(player, changes);
      }
      finishPractice(id, player, run, license.get());
      return;
    }
    finishExam(id, player, run, license.get(), SignallerExam.ended());
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
    Optional<LicenseClass> license = licenses.config().find(run.classId);
    if (license.isEmpty()) {
      // 重载后配置里已没有这一项：等这一班下岗时了结（下岗时找不到这一项直接了结），这一班仍按考试结算。
      return true;
    }
    finishExam(id, Bukkit.getPlayer(id), run, license.get(), SignallerExam.expired());
    return true;
  }

  /** 正在值守信号员考试或练习的这一班（放弃前要先下岗）。 */
  boolean onExamDuty(UUID id) {
    Run run = runs.get(id);
    return run != null && run.onDuty;
  }

  /** 考试或练习了结：忘掉这一次。 */
  void forget(UUID id) {
    runs.remove(id);
  }

  /** 练习中每隔一会儿：认识控制台一步按时间继续，久未完成的一步再提示。 */
  void tick(Instant now) {
    for (Map.Entry<UUID, Run> entry : new ArrayList<>(runs.entrySet())) {
      Run run = entry.getValue();
      if (run.finished) {
        continue;
      }
      Player player = Bukkit.getPlayer(entry.getKey());
      if (run.practice && player != null) {
        render(player, run.tutorial.tick(now));
        drillWhenAsked(run);
      }
      if (run.onDuty && run.drill == Drill.DUE) {
        plugin
            .getSignallerService()
            .flatMap(
                service ->
                    service.drillCall(
                        entry.getKey(), bound -> ThreadLocalRandom.current().nextInt(bound)))
            .ifPresent(train -> run.drill = Drill.OUT);
      }
    }
  }

  /** 练习讲到“回复呼叫”那一步时安排一次模拟呼叫（先前已回过真的呼叫，这一步当场就完成了，不会走到这里）。 */
  private static void drillWhenAsked(Run run) {
    if (run.practice
        && run.drill == Drill.NONE
        && run.tutorial.current() == SignallerTutorial.Step.ANSWER_CALL) {
      run.drill = Drill.DUE;
    }
  }

  // ---- 了结 ----

  /** 考试判定：及格发证，不及格进入冷却，不计成绩的可以马上重考。正在值守时这一班随之结束（下一拍下岗），下岗时收回临时权限。 */
  private void finishExam(
      UUID id, Player player, Run run, LicenseClass license, ExamEvaluation.Result result) {
    run.finished = true;
    Map<String, String> values = new LinkedHashMap<>(result.values());
    values.putAll(licenses.passValues(license, values.getOrDefault("points", "")));
    values.putAll(progress(license, run));
    switch (result.verdict()) {
      case PASSED -> {
        String name =
            player != null
                ? player.getName()
                : Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("");
        licenses.grant(id, name, license, "exam");
      }
      case FAILED -> {
        licenses.cooldown(id, license.id());
        values.put("minutes", String.valueOf(licenses.config().retryCooldownMinutes()));
      }
      case VOID -> {}
    }
    if (player != null) {
      LocaleManager locale = plugin.getLocaleManager();
      TagResolver.Builder resolver =
          TagResolver.builder()
              .resolver(
                  Placeholder.component(
                      "reason",
                      locale.component(
                          "drive.license.exam.signaller.reason." + result.reason(), values)));
      values.forEach((key, value) -> resolver.resolver(Placeholder.unparsed(key, value)));
      player.sendMessage(
          locale.component(
              "drive.license.exam.signaller." + result.verdict().name().toLowerCase(Locale.ROOT),
              resolver.build()));
      switch (result.verdict()) {
        case PASSED -> {
          licenses.tell(player, "drive.license.granted-desc", values);
          licenses.suggestNext(player);
        }
        case FAILED -> {
          licenses.tell(player, "drive.license.exam.signaller.tip." + result.reason(), values);
          licenses.tell(player, "drive.license.exam.retry", values);
        }
        case VOID -> licenses.tell(player, "drive.license.exam.retry-now", values);
      }
    }
    if (!run.onDuty) {
      licenses.closeExam(id);
      return;
    }
    endShiftLater(id, run);
  }

  /**
   * 不在考官的回调里下岗：下一拍以 {@code EXAM} 结束这一班，下岗时（按考试结算之后）收回临时权限。到那时这次考试已经了结（玩家先下了岗）就什么也不做，免得结束的是重新上岗的另一班。
   */
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
                          .getSignallerService()
                          .map(service -> service.endDuty(id, SignallerShift.EndReason.EXAM))
                          .orElse(false);
              if (!ended && runs.get(id) == run) {
                licenses.closeExam(id);
              }
            });
  }

  /** 练习讲评：做完全部步骤并下岗计一次练习；说明还差哪几步，按考试标准折算这一班的得分。 */
  private void finishPractice(UUID id, Player player, Run run, LicenseClass license) {
    run.finished = true;
    boolean done = run.tutorial.finished();
    int runsDone = licenses.trainingRuns(id, license.id()) + (done ? 1 : 0);
    if (done) {
      licenses.countPractice(id, player, license.id(), runsDone);
    }
    licenses.closeExam(id);
    if (player == null) {
      return;
    }
    Map<String, String> values = new LinkedHashMap<>(licenses.passValues(license, ""));
    values.putAll(progress(license, run));
    values.put("done", String.valueOf(runsDone));
    values.put("required", String.valueOf(license.trainingRuns()));
    licenses.tell(player, "drive.license.practice.signaller.review.header", values);
    licenses.tell(player, "drive.license.practice.signaller.review.counts", values);
    if (!done) {
      LocaleManager locale = plugin.getLocaleManager();
      List<String> left = new ArrayList<>();
      for (SignallerTutorial.Step step : run.tutorial.remaining()) {
        left.add(locale.text("drive.license.practice.signaller.steps." + step.key() + ".name"));
      }
      values.put(
          "steps",
          String.join(locale.text("drive.license.practice.signaller.review.separator"), left));
      licenses.tell(player, "drive.license.practice.signaller.review.not-counted", values);
    }
    String next;
    if (licenses.holds(id, license.id())) {
      next = "drive.license.practice.review.again";
    } else if (runsDone >= license.trainingRuns()) {
      next = "drive.license.practice.signaller.review.ready";
    } else {
      next = "drive.license.practice.signaller.review.more";
    }
    licenses.tell(player, next, values);
  }

  // ---- 呈现 ----

  /** 带练的推进：讲新的一步（聊天带进度，副标题一句短提示），跳过的步骤说一声，久未完成的再提示副标题。 */
  private void render(Player player, List<SignallerTutorial.Change> changes) {
    LocaleManager locale = plugin.getLocaleManager();
    for (SignallerTutorial.Change change : changes) {
      if (change instanceof SignallerTutorial.Change.Started started) {
        String base = "drive.license.practice.signaller.steps." + started.step().key();
        player.sendMessage(
            locale.component(
                started.total() > 0
                    ? "drive.license.practice.signaller.step"
                    : "drive.license.practice.signaller.step-first",
                TagResolver.resolver(
                    Placeholder.unparsed("index", String.valueOf(started.index())),
                    Placeholder.unparsed("total", String.valueOf(started.total())),
                    Placeholder.component("text", locale.component(base + ".chat")))));
        DriveTutorials.subtitle(player, locale.component(base + ".subtitle"));
      } else if (change instanceof SignallerTutorial.Change.Skipped skipped) {
        player.sendMessage(
            locale.component(
                "drive.license.practice.signaller.skipped",
                Map.of(
                    "step",
                    locale.text(
                        "drive.license.practice.signaller.steps."
                            + skipped.step().key()
                            + ".name"))));
      } else if (change instanceof SignallerTutorial.Change.Reminder reminder) {
        DriveTutorials.subtitle(
            player,
            locale.component(
                "drive.license.practice.signaller.steps." + reminder.step().key() + ".subtitle"));
      } else if (change instanceof SignallerTutorial.Change.Finished) {
        player.sendMessage(locale.component("drive.license.practice.signaller.all-done"));
      }
    }
  }

  /** 练习中的情境提示（每次练习每种一次）：来了呼叫、被代排、漏接、转回了自动。 */
  private void tip(Player player, Run run, Event event) {
    String key =
        switch (event) {
          case CALL_INCOMING -> "call";
          case ENTERED_AUTO_ROUTED -> "auto-routed";
          case CALL_MISSED, DRILL_MISSED -> "call-missed";
          case AUTO -> "to-auto";
          case IDLE_AUTO -> "idle-auto";
          default -> null;
        };
    if (key == null || !run.tips.add(key)) {
      return;
    }
    licenses.tell(player, "drive.license.practice.signaller.tips." + key, Map.of());
  }

  /** 考试进度：亲手排了几列、要排几列、代排与漏接各几次、此刻的得分。 */
  private static Map<String, String> progress(LicenseClass license, Run run) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("zone", run.zone);
    values.put("manual", String.valueOf(run.manual));
    values.put("trains", String.valueOf(license.examStops()));
    values.put("auto", String.valueOf(run.autoRouted));
    values.put("missed", String.valueOf(run.callsMissed));
    values.put("wrong", String.valueOf(run.callsWrong));
    values.put(
        "points",
        String.valueOf(SignallerExam.points(run.autoRouted, run.callsMissed, run.callsWrong)));
    values.put("penalty", String.valueOf(SignallerExam.PENALTY));
    values.put("min", String.valueOf(license.minPoints()));
    return values;
  }

  /** 这名玩家登记着的信号员考试或练习；考试记录已了结（例如换了别的考试）时为空。 */
  private Run current(UUID id) {
    Run run = runs.get(id);
    if (run == null) {
      return null;
    }
    boolean registered =
        licenses
            .exam(id)
            .filter(exam -> exam.kind() == LicenseClass.Exam.SIGNALLER)
            .filter(exam -> exam.classId().equals(run.classId))
            .isPresent();
    return registered ? run : null;
  }
}
