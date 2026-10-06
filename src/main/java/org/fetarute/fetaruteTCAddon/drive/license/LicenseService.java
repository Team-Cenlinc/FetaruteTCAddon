package org.fetarute.fetaruteTCAddon.drive.license;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.fetarute.fetaruteTCAddon.api.event.DriverStopScoredEvent;
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskFinishedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardStations;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.drive.tutorial.DriveTutorials;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 驾驶证：玩家自己考试拿驾驶权限，不必等管理员授权。
 *
 * <p>持有哪些等级记在插件的数据库里（多服共用一个库时一起生效）；玩家在线时，插件按持有的等级替他挂上配置里的权限节点（Bukkit 权限附件，子节点随之生效），
 * 不改任何权限插件的数据。考试期间临时挂上所考那一级的节点，考完撤下。
 *
 * <p>两种考试：完整做完新手教程（练习步骤不能跳过）；路考——给考生派一段调度列车的区间任务，按终态与成绩判定。考过发一本驾驶证（成书，把记录印出来，只是凭证），丢了可以补发。只在服务器主线程调用（数据库读写在异步线程，结果回到主线程）。
 */
public final class LicenseService implements Listener {

  /** 每隔多少 tick 查看一次路考中的列车（防护紧急制动、转 ATO）。 */
  private static final long MONITOR_TICKS = 10L;

  /** 每隔多少 tick 检查一次考试是否超时。 */
  private static final long EXPIRY_CHECK_TICKS = 20L * 20L;

  /** 路考的兜底时限：考试任务自己会作废或结束，这只防万一。 */
  private static final Duration DISPATCH_EXAM_FALLBACK = Duration.ofHours(3);

  /** 路考挑车次时最多看几班。 */
  private static final int EXAM_CANDIDATES = 64;

  /** 路考与练习只派这么久以后才从接班站发车的车次：考生要看完考前说明、前往接班站、坐进驾驶室接班。 */
  static final Duration EXAM_BOARDING_LEAD = Duration.ofSeconds(60);

  /**
   * 一次进行中的考试或路考练习。
   *
   * @param classId 考（练）的等级
   * @param kind 考试方式
   * @param deadline 截止时刻
   * @param training 是路考练习（不发证、有教练与应急演练）
   */
  public record Exam(String classId, LicenseClass.Exam kind, Instant deadline, boolean training) {}

  /**
   * 给玩家的回复。
   *
   * @param key 语言键
   * @param values 占位符
   */
  public record Reply(String key, Map<String, String> values) {
    public Reply {
      values = values == null ? Map.of() : Map.copyOf(values);
    }

    static Reply of(String key) {
      return new Reply(key, Map.of());
    }
  }

  private final FetaruteTCAddon plugin;
  private final Supplier<DriveSessionManager> drive;
  private final LicenseCardItem cards;
  private LicenseConfig config;

  /** 在线玩家持有的等级；还没从数据库读到时没有这一项。 */
  private final Map<UUID, Map<String, LicenseRecord>> held = new HashMap<>();

  private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
  private final Map<UUID, Set<String>> attached = new HashMap<>();
  private final Map<UUID, Exam> exams = new HashMap<>();

  /** 路考不及格后的冷却：键为“玩家:等级”。 */
  private final Map<String, Instant> retryAfter = new HashMap<>();

  private final Map<UUID, Instant> reissueAfter = new HashMap<>();

  /** 路考中已点评过的停站数。 */
  private final Map<UUID, Integer> examStopsDone = new HashMap<>();

  /** 路考中已经提醒过的不及格项（每项只提醒一次）。 */
  private final Map<UUID, Set<String>> examWarned = new HashMap<>();

  /** 在线玩家各级的练习次数；还没读到时没有这一项。 */
  private final Map<UUID, Map<String, TrainingRecord>> training = new HashMap<>();

  private final DriverHandbook handbook;
  private final TrainingCoach coach;
  private BukkitTask expiryTask;
  private BukkitTask monitorTask;

  public LicenseService(
      FetaruteTCAddon plugin, Supplier<DriveSessionManager> drive, LicenseConfig config) {
    this.plugin = plugin;
    this.drive = drive;
    this.cards = new LicenseCardItem(plugin);
    this.handbook = new DriverHandbook(plugin);
    this.coach = new TrainingCoach(plugin::getLocaleManager, () -> this.config, drive);
    this.config = config == null ? LicenseConfig.defaults() : config;
  }

  // ---- 生命周期 ----

  /** 读入在线玩家的驾驶证（插件启用、重载时不会有加入事件），开始检查考试超时。 */
  public void start() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      load(player);
    }
    expiryTask =
        Bukkit.getScheduler()
            .runTaskTimer(plugin, this::expireExams, EXPIRY_CHECK_TICKS, EXPIRY_CHECK_TICKS);
    monitorTask =
        Bukkit.getScheduler()
            .runTaskTimer(plugin, this::monitorExams, MONITOR_TICKS, MONITOR_TICKS);
  }

  /** 撤下全部权限附件、停止检查。 */
  public void shutdown() {
    if (expiryTask != null) {
      expiryTask.cancel();
      expiryTask = null;
    }
    if (monitorTask != null) {
      monitorTask.cancel();
      monitorTask = null;
    }
    for (PermissionAttachment attachment : attachments.values()) {
      attachment.remove();
    }
    attachments.clear();
    attached.clear();
  }

  /** 换上新配置并按它重新挂权限。 */
  public void reload(LicenseConfig value) {
    this.config = value == null ? LicenseConfig.defaults() : value;
    for (Player player : Bukkit.getOnlinePlayers()) {
      apply(player);
    }
  }

  public LicenseConfig config() {
    return config;
  }

  public LicenseCardItem cardItems() {
    return cards;
  }

  public DriverHandbook handbook() {
    return handbook;
  }

  // ---- 查询 ----

  /** 在线玩家持有的等级，按发证先后；还没读到时为空。 */
  public List<LicenseRecord> held(UUID playerId) {
    Map<String, LicenseRecord> mine = held.get(playerId);
    if (mine == null) {
      return List.of();
    }
    List<LicenseRecord> records = new ArrayList<>(mine.values());
    records.sort(Comparator.comparing(LicenseRecord::grantedAt));
    return records;
  }

  /** 在线玩家的驾驶证是否已从数据库读到。 */
  public boolean loaded(UUID playerId) {
    return held.containsKey(playerId);
  }

  public boolean holds(UUID playerId, String classId) {
    Map<String, LicenseRecord> mine = held.get(playerId);
    return mine != null && mine.containsKey(classId);
  }

  public Optional<Exam> exam(UUID playerId) {
    return Optional.ofNullable(exams.get(playerId));
  }

  /** 等级的显示名；配置里已没有这一级时用标识。 */
  public String nameOf(String classId) {
    return config.find(classId).map(LicenseClass::name).orElse(classId);
  }

  /** 带级别的显示名：“第 N 级 · 名称”；配置里已没有这一级时只写名称。 */
  public String levelName(String classId) {
    int level = config.levelOf(classId);
    if (level <= 0) {
      return nameOf(classId);
    }
    return plugin
        .getLocaleManager()
        .text("drive.license.level-name")
        .replace("<level>", String.valueOf(level))
        .replace("<name>", nameOf(classId));
  }

  // ---- 考试 ----

  /**
   * 报名考一级驾驶证。正在考这一级的教程时再报名，是从第一步重做教程。
   *
   * @param stationArg 路考的接班站；为空时取玩家附近的车站
   */
  public Reply startExam(Player player, String classId, Optional<String> stationArg) {
    UUID id = player.getUniqueId();
    if (!config.enabled()) {
      return Reply.of("drive.license.disabled");
    }
    Optional<LicenseClass> found = config.find(classId).filter(LicenseClass::enabled);
    if (found.isEmpty()) {
      return new Reply(
          "drive.license.exam.unknown-class", Map.of("class", String.valueOf(classId)));
    }
    LicenseClass license = found.get();
    if (!loaded(id)) {
      return Reply.of("drive.license.loading");
    }
    if (holds(id, license.id())) {
      return new Reply("drive.license.exam.already-held", Map.of("name", license.name()));
    }
    List<String> missing = new ArrayList<>();
    for (String required : license.requires()) {
      if (!holds(id, required)) {
        missing.add(nameOf(required));
      }
    }
    if (!missing.isEmpty()) {
      return new Reply(
          "drive.license.exam.requires",
          Map.of("name", license.name(), "required", String.join("、", missing)));
    }
    Exam running = exams.get(id);
    if (running != null
        && running.kind() == LicenseClass.Exam.TUTORIAL
        && running.classId().equals(license.id())) {
      return restartTutorialExam(player, license, running);
    }
    if (running != null) {
      return new Reply("drive.license.exam.in-progress", Map.of("name", nameOf(running.classId())));
    }
    Instant now = Instant.now();
    Instant until = retryAfter.get(retryKey(id, license.id()));
    if (until != null && now.isBefore(until)) {
      return new Reply(
          "drive.license.exam.cooldown",
          Map.of("name", license.name(), "minutes", String.valueOf(minutesUntil(now, until))));
    }
    if (license.exam() == LicenseClass.Exam.DISPATCH
        && trainingRuns(id, license.id()) < license.trainingRuns()) {
      return new Reply(
          "drive.license.exam.need-training",
          Map.of(
              "name",
              license.name(),
              "class",
              license.id(),
              "done",
              String.valueOf(trainingRuns(id, license.id())),
              "required",
              String.valueOf(license.trainingRuns())));
    }
    return switch (license.exam()) {
      case TUTORIAL -> startTutorialExam(player, license, now);
      case DISPATCH -> startDispatchRun(player, license, stationArg, now, false);
    };
  }

  /**
   * 报名路考练习：和路考一样派一段区间任务，有教练提示与一次应急演练，不发证、不冷却、不记入驾驶记录。完整开完（到下车站）计一次练习。
   *
   * @param stationArg 接班站；为空时取玩家附近的车站
   */
  public Reply startPractice(Player player, String classId, Optional<String> stationArg) {
    UUID id = player.getUniqueId();
    if (!config.enabled()) {
      return Reply.of("drive.license.disabled");
    }
    Optional<LicenseClass> found = config.find(classId).filter(LicenseClass::enabled);
    if (found.isEmpty()) {
      return new Reply(
          "drive.license.exam.unknown-class", Map.of("class", String.valueOf(classId)));
    }
    LicenseClass license = found.get();
    if (license.exam() != LicenseClass.Exam.DISPATCH) {
      return new Reply("drive.license.practice.not-road", Map.of("name", license.name()));
    }
    if (!loaded(id)) {
      return Reply.of("drive.license.loading");
    }
    List<String> missing = new ArrayList<>();
    for (String required : license.requires()) {
      if (!holds(id, required)) {
        missing.add(nameOf(required));
      }
    }
    if (!missing.isEmpty()) {
      return new Reply(
          "drive.license.exam.requires",
          Map.of("name", license.name(), "required", String.join("、", missing)));
    }
    Exam running = exams.get(id);
    if (running != null) {
      return new Reply("drive.license.exam.in-progress", Map.of("name", nameOf(running.classId())));
    }
    return startDispatchRun(player, license, stationArg, Instant.now(), true);
  }

  /** 这名在线玩家这一级完整开完了几次练习。 */
  public int trainingRuns(UUID playerId, String classId) {
    Map<String, TrainingRecord> mine = training.get(playerId);
    TrainingRecord record = mine == null ? null : mine.get(classId);
    return record == null ? 0 : record.runs();
  }

  private Reply startTutorialExam(Player player, LicenseClass license, Instant now) {
    DriveSessionManager manager = drive.get();
    if (manager == null) {
      return Reply.of("drive.command.unavailable");
    }
    exams.put(
        player.getUniqueId(),
        new Exam(
            license.id(),
            LicenseClass.Exam.TUTORIAL,
            now.plus(Duration.ofMinutes(config.examWindowMinutes())),
            false));
    apply(player);
    // 正在驾驶时马上开始教程；不在驾驶时下一次开始驾驶时开始（考试期间临时有开车与教程的权限）。
    manager.startTutorial(player);
    Map<String, String> values =
        Map.of(
            "level",
            String.valueOf(config.levelOf(license.id())),
            "name",
            license.name(),
            "minutes",
            String.valueOf(config.examWindowMinutes()));
    for (String line : List.of("header", "content", "how", "rule")) {
      tell(player, "drive.license.exam.brief.tutorial." + line, values);
    }
    return new Reply("drive.license.exam.brief.tutorial.perm", values);
  }

  /** 正在考教程时再报名同一级：放弃这一次教程，从第一步重来；考试截止时刻不变。 */
  private Reply restartTutorialExam(Player player, LicenseClass license, Exam exam) {
    DriveSessionManager manager = drive.get();
    if (manager == null) {
      return Reply.of("drive.command.unavailable");
    }
    String key = manager.restartTutorial(player);
    if (key != null) {
      tell(player, key, Map.of());
    }
    return new Reply(
        "drive.license.exam.tutorial-restarted",
        Map.of(
            "name",
            license.name(),
            "minutes",
            String.valueOf(minutesUntil(Instant.now(), exam.deadline()))));
  }

  private Reply startDispatchRun(
      Player player,
      LicenseClass license,
      Optional<String> stationArg,
      Instant now,
      boolean practice) {
    UUID id = player.getUniqueId();
    DriveSessionManager manager = drive.get();
    if (manager == null) {
      return Reply.of("drive.command.unavailable");
    }
    if (manager.sessionOf(id).isPresent()) {
      return Reply.of("drive.license.exam.driving");
    }
    if (manager.tasks().activeTaskOf(id).isPresent()) {
      return Reply.of("drive.license.exam.has-task");
    }
    DriveConfig current = manager.config();
    if (!current.enabled() || !current.driver().enabled()) {
      return Reply.of("drive.task.claim.disabled");
    }
    if (manager.tasks().breaker().open(now)) {
      // 线路拥堵熔断中：不再往正式车次上放考生。
      return Reply.of("drive.task.claim.breaker-open");
    }
    Optional<TaskBoardSource.Station> station;
    if (stationArg.filter(arg -> !arg.isBlank()).isPresent()) {
      String code = stationArg.get().trim();
      TaskBoardStations.Lookup lookup =
          TaskBoardStations.find(TaskBoardSource.stations(plugin), code);
      if (lookup.outcome() == TaskBoardStations.Outcome.AMBIGUOUS) {
        return new Reply(
            "drive.task.board.station-ambiguous",
            Map.of("station", code, "candidates", String.join(", ", lookup.candidates())));
      }
      if (lookup.outcome() == TaskBoardStations.Outcome.NOT_FOUND) {
        return new Reply("drive.task.board.station-not-found", Map.of("station", code));
      }
      station = lookup.station();
    } else {
      station = TaskBoardSource.nearestStation(plugin, player.getLocation());
    }
    if (station.isEmpty()) {
      return Reply.of("drive.task.board.no-station");
    }
    Optional<TimetableService> timetables = plugin.getTimetableService();
    if (timetables.isEmpty()) {
      return new Reply("drive.license.exam.no-trip", Map.of("station", station.get().name()));
    }
    List<TaskBoardEntries.Row> rows =
        TaskBoardSource.departures(
            plugin, station.get(), now, current.driver().recovery().taskWindowMinutes());
    TrainingConfig trainingConfig = config.training();
    for (TaskBoardEntries.Row row :
        TaskBoardEntries.select(rows, manager.tasks().takenKeys(), now, EXAM_CANDIDATES)) {
      if (practice && !trainingConfig.allowsRoute(row.routeCode())) {
        continue;
      }
      // 马上就要开走的车（多半正在停站）赶不上：任务会在考生到站前作废。
      if (row.plannedDeparture().isBefore(now.plus(EXAM_BOARDING_LEAD))) {
        continue;
      }
      // 已经晚点的车不派给考生：练习、路考都可能再慢一些，调度会救不过来。
      if (lateTrain(row.trainName(), trainingConfig.drillMaxDelaySeconds())) {
        continue;
      }
      Optional<DriverTaskManager.TaskSpec> spec =
          TaskBoardSource.examSpec(
              plugin,
              timetables.get(),
              row,
              station.get(),
              license.examStops(),
              practice ? DriverTask.SOURCE_TRAINING : DriverTask.SOURCE_EXAM,
              Map.of("license", license.id()));
      if (spec.isEmpty()) {
        continue;
      }
      // 先挂上所考那一级的权限：领取通知里的接班、开车命令要能用。
      exams.put(
          id,
          new Exam(
              license.id(),
              LicenseClass.Exam.DISPATCH,
              now.plus(DISPATCH_EXAM_FALLBACK),
              practice));
      coach.begin(id, practice);
      apply(player);
      DriverTaskManager.ClaimOutcome outcome =
          manager.assignTask(player, spec.get(), DrivingMode.MANUAL, false);
      if (outcome != DriverTaskManager.ClaimOutcome.CLAIMED) {
        endExam(id);
        apply(player);
        return new Reply("drive.license.exam.assign-failed", Map.of("reason", outcome.name()));
      }
      briefDispatch(player, license, spec.get(), practice);
      return Reply.of(
          practice
              ? "drive.license.practice.brief.feedback"
              : "drive.license.exam.brief.dispatch.feedback");
    }
    if (practice && !trainingConfig.routes().isEmpty()) {
      return new Reply(
          "drive.license.practice.no-trip-routes",
          Map.of(
              "station",
              station.get().name(),
              "routes",
              String.join("、", trainingConfig.routes())));
    }
    return new Reply("drive.license.exam.no-trip", Map.of("station", station.get().name()));
  }

  /** 这列车此刻晚点是否超过阈值；查不到（还没出车、没有时刻表）时不算。 */
  private static boolean lateTrain(String trainName, int maxDelaySeconds) {
    java.util.OptionalLong delay = TrainingCoach.delayOf(trainName);
    return delay.isPresent() && delay.getAsLong() > maxDelaySeconds;
  }

  /**
   * 教程做完：在考教程的玩家完整做完（没有跳过练习步骤）即发证；跳过了练习步骤不算，考试照旧有效、可以重做。
   *
   * @param complete 是否完整做完
   */
  public void onTutorialFinished(Player player, boolean complete) {
    UUID id = player.getUniqueId();
    Exam exam = exams.get(id);
    if (exam == null || exam.kind() != LicenseClass.Exam.TUTORIAL) {
      return;
    }
    if (!complete) {
      player.sendMessage(
          plugin
              .getLocaleManager()
              .component(
                  "drive.license.exam.tutorial-skipped", Map.of("name", nameOf(exam.classId()))));
      return;
    }
    endExam(id);
    Optional<LicenseClass> license = config.find(exam.classId());
    if (license.isEmpty()) {
      apply(player);
      return;
    }
    grant(id, player.getName(), license.get(), "exam");
    tell(player, "drive.license.exam.tutorial-passed", passValues(license.get(), ""));
    tell(player, "drive.license.granted-desc", passValues(license.get(), ""));
    suggestNext(player);
  }

  /**
   * 教程考试中这一次教程不能再算完整做完（跳过了练习步骤或退出了教程）：马上告诉考生考试仍然有效，可以从第一步重做。
   *
   * @param reason 原因
   */
  public void onTutorialForfeit(Player player, DriveTutorials.Forfeit reason) {
    Exam exam = exams.get(player.getUniqueId());
    if (exam == null || exam.kind() != LicenseClass.Exam.TUTORIAL) {
      return;
    }
    tell(
        player,
        reason == DriveTutorials.Forfeit.EXITED
            ? "drive.license.exam.tutorial-exited"
            : "drive.license.exam.tutorial-step-skipped",
        Map.of(
            "name",
            nameOf(exam.classId()),
            "class",
            exam.classId(),
            "minutes",
            String.valueOf(minutesUntil(Instant.now(), exam.deadline()))));
  }

  /** 路考任务结束（含没开车就作废）：按终态与成绩判定，及格发证，不及格进入冷却，不算成绩的可以马上重考。练习任务结束则讲评并计次。 */
  @EventHandler
  public void onTaskFinished(DriverTaskFinishedEvent event) {
    DriveApi.TaskView task = event.getTask();
    boolean practice = DriverTask.SOURCE_TRAINING.equals(task.source());
    if (!practice && !DriverTask.SOURCE_EXAM.equals(task.source())) {
      return;
    }
    UUID id = task.playerId();
    Exam exam = exams.get(id);
    if (exam == null || exam.kind() != LicenseClass.Exam.DISPATCH || exam.training() != practice) {
      return;
    }
    Optional<TrainingCoach.DrillOutcome> drill = endExam(id);
    if (practice) {
      finishPractice(task, event.getScore(), exam, drill);
      return;
    }
    Player player = Bukkit.getPlayer(id);
    Optional<LicenseClass> license = config.find(exam.classId());
    if (license.isEmpty()) {
      if (player != null) {
        apply(player);
      }
      return;
    }
    ExamEvaluation.Result result = ExamEvaluation.dispatch(license.get(), task, event.getScore());
    Map<String, String> values = new LinkedHashMap<>(result.values());
    values.putAll(passValues(license.get(), values.getOrDefault("points", "")));
    switch (result.verdict()) {
      case PASSED -> {
        String name =
            player != null
                ? player.getName()
                : Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("");
        grant(id, name, license.get(), "exam");
      }
      case FAILED -> {
        Instant until = Instant.now().plus(Duration.ofMinutes(config.retryCooldownMinutes()));
        retryAfter.put(retryKey(id, license.get().id()), until);
        values.put("minutes", String.valueOf(config.retryCooldownMinutes()));
      }
      case VOID -> {}
    }
    if (player == null) {
      return;
    }
    apply(player);
    LocaleManager locale = plugin.getLocaleManager();
    TagResolver.Builder resolver =
        TagResolver.builder()
            .resolver(
                Placeholder.component(
                    "reason",
                    locale.component("drive.license.exam.reason." + result.reason(), values)));
    values.forEach((key, value) -> resolver.resolver(Placeholder.unparsed(key, value)));
    player.sendMessage(
        locale.component(
            "drive.license.exam." + result.verdict().name().toLowerCase(Locale.ROOT),
            resolver.build()));
    switch (result.verdict()) {
      case PASSED -> {
        tell(player, "drive.license.granted-desc", values);
        suggestNext(player);
      }
      case FAILED -> {
        tell(player, "drive.license.exam.tip." + result.reason(), values);
        tell(player, "drive.license.exam.retry", values);
      }
      case VOID -> tell(player, "drive.license.exam.retry-now", values);
    }
  }

  /** 路考与练习每停一站点评一次：停车结果、车门，以及第几站；出现不及格项当场提醒。 */
  @EventHandler
  public void onStopScored(DriverStopScoredEvent event) {
    Optional<DriveApi.TaskView> task = event.getTask();
    if (task.isEmpty()
        || !(DriverTask.SOURCE_EXAM.equals(task.get().source())
            || DriverTask.SOURCE_TRAINING.equals(task.get().source()))) {
      return;
    }
    UUID id = event.getPlayerId();
    Exam exam = exams.get(id);
    Player player = Bukkit.getPlayer(id);
    Optional<LicenseClass> license = exam == null ? Optional.empty() : config.find(exam.classId());
    if (player == null || license.isEmpty() || exam.kind() != LicenseClass.Exam.DISPATCH) {
      return;
    }
    DriveApi.StopResult stop = event.getStop();
    int done = examStopsDone.merge(id, 1, Integer::sum);
    LocaleManager locale = plugin.getLocaleManager();
    String window = stop.window().name().toLowerCase(Locale.ROOT);
    String doors = stop.wrongDoor() ? "wrong" : stop.doorsTakenOver() ? "taken-over" : "ok";
    TagResolver resolver =
        TagResolver.builder()
            .resolver(
                Placeholder.component(
                    "window", locale.component("drive.license.exam.stop.window." + window)))
            .resolver(
                Placeholder.component(
                    "doors", locale.component("drive.license.exam.stop.doors." + doors)))
            .resolver(Placeholder.unparsed("done", String.valueOf(done)))
            .resolver(Placeholder.unparsed("total", String.valueOf(license.get().examStops())))
            .resolver(Placeholder.unparsed("station", stop.station()))
            .build();
    player.sendMessage(
        locale.component(
            exam.training() ? "drive.license.practice.stop.line" : "drive.license.exam.stop.line",
            resolver));
    if (!license.get().allowOverrun()
        && (stop.window() == DriveApi.StopWindow.OVERRUN
            || stop.window() == DriveApi.StopWindow.SKIPPED)) {
      warn(player, "overrun", Map.of("station", stop.station()));
    }
    if (!license.get().allowWrongDoor() && stop.wrongDoor()) {
      warn(player, "wrong-door", Map.of("station", stop.station()));
    }
  }

  /** 路考中的列车：触发防护紧急制动、转为 ATO 时当场提醒（每项一次）。 */
  private void monitorExams() {
    DriveSessionManager manager = drive.get();
    if (manager == null) {
      return;
    }
    // 交还自动运行会同步结束任务、移出 exams，所以遍历副本。
    for (Map.Entry<UUID, Exam> entry : List.copyOf(exams.entrySet())) {
      if (entry.getValue().kind() != LicenseClass.Exam.DISPATCH
          || exams.get(entry.getKey()) != entry.getValue()) {
        continue;
      }
      Player player = Bukkit.getPlayer(entry.getKey());
      Optional<LicenseClass> license = config.find(entry.getValue().classId());
      if (player == null || license.isEmpty()) {
        continue;
      }
      long nowTick = Bukkit.getCurrentTick();
      manager
          .sessionOf(entry.getKey())
          .filter(session -> session.driverLink() != null)
          .ifPresent(
              session -> {
                if (session.isAto()) {
                  warn(player, "ato", Map.of());
                } else if (!license.get().allowEmergency()
                    && session.driverLink().emergencyInterventions() > 0) {
                  warn(player, "emergency", Map.of());
                }
                coach.tick(player, session, examStopsDone.getOrDefault(entry.getKey(), 0), nowTick);
              });
    }
  }

  /** 不及格项（或不计成绩）当场提醒一次，并说明可以开完练习或放弃。 */
  private void warn(Player player, String what, Map<String, String> values) {
    if (!examWarned.computeIfAbsent(player.getUniqueId(), id -> new LinkedHashSet<>()).add(what)) {
      return;
    }
    Exam exam = exams.get(player.getUniqueId());
    String prefix =
        exam != null && exam.training() ? "drive.license.practice" : "drive.license.exam";
    tell(player, prefix + ".warn." + what, values);
    tell(player, prefix + ".warn.hint", values);
  }

  /** 考过后提示下一级（带报名按钮）；已到最高一级时说一声。 */
  private void suggestNext(Player player) {
    UUID id = player.getUniqueId();
    boolean any = false;
    for (LicenseClass license : config.classes()) {
      if (!license.enabled() || holds(id, license.id())) {
        continue;
      }
      if (license.requires().stream().allMatch(required -> holds(id, required))) {
        tell(player, "drive.license.next", passValues(license, ""));
        any = true;
      }
    }
    if (!any
        && config.classes().stream()
            .filter(LicenseClass::enabled)
            .allMatch(license -> holds(id, license.id()))) {
      tell(player, "drive.license.top", Map.of());
    }
  }

  /** 路考开考说明：考哪一趟、及格要求、怎么开始，并发一本驾驶员手册。 */
  private void briefDispatch(
      Player player, LicenseClass license, DriverTaskManager.TaskSpec spec, boolean practice) {
    Map<String, String> values = new LinkedHashMap<>(passValues(license, ""));
    values.put("trip", spec.key().tripCode());
    values.put("board", spec.stationName());
    values.put("alight", spec.alightStationName());
    values.put("stops", String.valueOf(license.examStops()));
    values.put("min", String.valueOf(license.minPoints()));
    String brief =
        practice ? "drive.license.practice.brief." : "drive.license.exam.brief.dispatch.";
    tell(player, brief + "header", values);
    tell(player, brief + "route", values);
    if (practice) {
      tell(player, "drive.license.practice.brief.what", values);
      if (config.training().drill()) {
        tell(player, "drive.license.practice.brief.drill", values);
      }
    }
    tell(player, brief + "pass", values);
    if (!license.allowEmergency()) {
      tell(player, "drive.license.exam.brief.dispatch.no-emergency", values);
    }
    if (!license.allowOverrun()) {
      tell(player, "drive.license.exam.brief.dispatch.no-overrun", values);
    }
    if (!license.allowWrongDoor()) {
      tell(player, "drive.license.exam.brief.dispatch.no-wrong-door", values);
    }
    tell(player, "drive.license.exam.brief.dispatch.how", values);
    handbook.give(player, plugin.getLocaleManager(), true);
    tell(player, brief + "handbook", values);
  }

  /** 发证、提示里常用的占位符：级别、名称、标识、持证后能做什么、得分。 */
  private Map<String, String> passValues(LicenseClass license, String points) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("level", String.valueOf(config.levelOf(license.id())));
    values.put("name", license.name());
    values.put("class", license.id());
    values.put("description", license.description());
    values.put("points", points);
    values.put("stops", String.valueOf(license.examStops()));
    values.put("min", String.valueOf(license.minPoints()));
    values.put("minutes", String.valueOf(config.retryCooldownMinutes()));
    return values;
  }

  private void tell(Player player, String key, Map<String, String> values) {
    player.sendMessage(plugin.getLocaleManager().component(key, values));
  }

  /** 结束一次考试的记录（不动权限，调用方随后 {@link #apply}）。 */
  private Optional<TrainingCoach.DrillOutcome> endExam(UUID playerId) {
    exams.remove(playerId);
    examStopsDone.remove(playerId);
    examWarned.remove(playerId);
    return coach.end(playerId);
  }

  /** 练习结束：按路考标准讲评、讲评应急处置；开到下车站计一次练习，告诉玩家还差几次或可以报名路考。 */
  private void finishPractice(
      DriveApi.TaskView task,
      Optional<DriveApi.TaskScore> score,
      Exam exam,
      Optional<TrainingCoach.DrillOutcome> drill) {
    UUID id = task.playerId();
    Player player = Bukkit.getPlayer(id);
    Optional<LicenseClass> license = config.find(exam.classId());
    if (player != null) {
      apply(player);
    }
    if (license.isEmpty()) {
      return;
    }
    boolean completed = task.state() == DriveApi.TaskState.COMPLETED;
    int runs = trainingRuns(id, license.get().id()) + (completed ? 1 : 0);
    if (completed && training.containsKey(id)) {
      // 还没读到练习次数时不写：按 0 次加一会覆盖库里的累计。
      recordTraining(id, player, license.get().id(), runs);
    }
    if (player == null) {
      return;
    }
    ExamEvaluation.Result result = ExamEvaluation.dispatch(license.get(), task, score);
    Map<String, String> values = new LinkedHashMap<>(result.values());
    values.putAll(passValues(license.get(), values.getOrDefault("points", "")));
    values.put("done", String.valueOf(runs));
    values.put("required", String.valueOf(license.get().trainingRuns()));
    LocaleManager locale = plugin.getLocaleManager();
    TagResolver.Builder resolver =
        TagResolver.builder()
            .resolver(
                Placeholder.component(
                    "reason",
                    locale.component("drive.license.exam.reason." + result.reason(), values)));
    values.forEach((key, value) -> resolver.resolver(Placeholder.unparsed(key, value)));
    tell(player, "drive.license.practice.review.header", values);
    player.sendMessage(
        locale.component(
            "drive.license.practice.review." + result.verdict().name().toLowerCase(Locale.ROOT),
            resolver.build()));
    if (result.verdict() == ExamEvaluation.Verdict.FAILED) {
      tell(player, "drive.license.exam.tip." + result.reason(), values);
    }
    if (drill.isPresent()) {
      coach.review(player, drill.get());
    } else if (config.training().drill() && !"not-started".equals(result.reason())) {
      // 没接班就作废的练习谈不上安排演练，只在练习确实开过时说明这一次没有演练。
      tell(player, "drive.license.practice.review.no-drill", values);
    }
    if (!completed) {
      tell(player, "drive.license.practice.review.not-counted", values);
    }
    String next;
    if (holds(id, license.get().id())) {
      next = "drive.license.practice.review.again";
    } else if (runs >= license.get().trainingRuns()) {
      next = "drive.license.practice.review.ready";
    } else {
      next = "drive.license.practice.review.more";
    }
    tell(player, next, values);
  }

  /** 记下练习次数：先改缓存，再异步写库。 */
  private void recordTraining(UUID playerId, Player player, String classId, int runs) {
    String name =
        player != null
            ? player.getName()
            : Optional.ofNullable(Bukkit.getOfflinePlayer(playerId).getName()).orElse("");
    TrainingRecord record = new TrainingRecord(playerId, name, classId, runs, Instant.now());
    Map<String, TrainingRecord> mine = training.get(playerId);
    if (mine != null) {
      mine.put(classId, record);
    }
    async(
        provider -> {
          provider.licenses().saveTraining(record);
          return Boolean.TRUE;
        },
        ok -> {},
        failure -> plugin.getLogger().warning("练习次数写入失败（" + name + "）: " + failure));
  }

  /** 教程考试过了截止时刻（且没在驾驶）就作废；路考按兜底时限作废。 */
  private void expireExams() {
    Instant now = Instant.now();
    DriveSessionManager manager = drive.get();
    for (Map.Entry<UUID, Exam> entry : new ArrayList<>(exams.entrySet())) {
      Exam exam = entry.getValue();
      UUID id = entry.getKey();
      if (now.isBefore(exam.deadline())) {
        continue;
      }
      boolean driving = manager != null && manager.sessionOf(id).isPresent();
      if (exam.kind() == LicenseClass.Exam.TUTORIAL && driving) {
        // 正在开车做教程：等这次驾驶结束再说，不中途收走开车的权限。
        continue;
      }
      endExam(id);
      Player player = Bukkit.getPlayer(id);
      if (player != null) {
        apply(player);
        player.sendMessage(
            plugin
                .getLocaleManager()
                .component(
                    exam.training()
                        ? "drive.license.practice.expired"
                        : "drive.license.exam.expired",
                    Map.of("name", nameOf(exam.classId()))));
      }
    }
  }

  // ---- 发证、吊销、补发 ----

  /**
   * 发一级驾驶证。玩家在线时马上生效，并把印着新等级的驾驶证放进背包（换掉背包里的旧证）；不在线时只记进数据库，上线后生效，驾驶证用补发领取。
   *
   * @param grantedBy {@code exam} 或管理员名字
   */
  public void grant(UUID playerId, String playerName, LicenseClass license, String grantedBy) {
    LicenseRecord record =
        new LicenseRecord(playerId, playerName, license.id(), Instant.now(), grantedBy);
    Player player = Bukkit.getPlayer(playerId);
    Map<String, LicenseRecord> mine = held.get(playerId);
    if (mine != null) {
      mine.put(license.id(), record);
    }
    if (player != null && mine != null) {
      apply(player);
      giveCard(player);
    }
    async(
        provider -> {
          provider.licenses().grant(record);
          return Boolean.TRUE;
        },
        ok -> {},
        failure ->
            plugin
                .getLogger()
                .warning(
                    "驾驶证写入失败（"
                        + playerName
                        + " "
                        + license.id()
                        + "），本次在线期间有效，重新登录后会丢失: "
                        + failure));
  }

  /**
   * 吊销一级驾驶证。
   *
   * @param done 主线程回调：原来是否持有；存储不可用时为空
   */
  public void revoke(UUID playerId, String classId, Consumer<Optional<Boolean>> done) {
    async(
        provider -> provider.licenses().revoke(playerId, classId),
        removed -> {
          Map<String, LicenseRecord> mine = held.get(playerId);
          if (mine != null) {
            mine.remove(classId);
          }
          Player player = Bukkit.getPlayer(playerId);
          if (player != null) {
            apply(player);
          }
          done.accept(Optional.of(removed));
        },
        failure -> done.accept(Optional.empty()));
  }

  /**
   * 查一名玩家（可以不在线）持有的等级。
   *
   * @param done 主线程回调；存储不可用时为空
   */
  public void list(UUID playerId, Consumer<Optional<List<LicenseRecord>>> done) {
    async(
        provider -> provider.licenses().listByPlayer(playerId),
        records -> done.accept(Optional.of(records)),
        failure -> done.accept(Optional.empty()));
  }

  /** 补发驾驶证：按记录再印一本放进背包。 */
  public Reply reissue(Player player) {
    UUID id = player.getUniqueId();
    if (!config.enabled()) {
      return Reply.of("drive.license.disabled");
    }
    if (!loaded(id)) {
      return Reply.of("drive.license.loading");
    }
    if (held(id).isEmpty()) {
      return Reply.of("drive.license.reissue.none");
    }
    Instant now = Instant.now();
    Instant until = reissueAfter.get(id);
    if (until != null && now.isBefore(until)) {
      return new Reply(
          "drive.license.reissue.cooldown",
          Map.of("minutes", String.valueOf(minutesUntil(now, until))));
    }
    reissueAfter.put(id, now.plus(Duration.ofMinutes(config.reissueCooldownMinutes())));
    giveCard(player);
    return new Reply("drive.license.reissue.done", Map.of("serial", LicenseCardItem.number(id)));
  }

  /** 按记录印一本驾驶证放进背包：先收走背包里这名玩家的旧证，放不下的掉在脚边。 */
  private void giveCard(Player player) {
    List<LicenseCardItem.Entry> entries = new ArrayList<>();
    for (LicenseRecord record : held(player.getUniqueId())) {
      entries.add(new LicenseCardItem.Entry(levelName(record.classId()), record.grantedAt()));
    }
    PlayerInventory inventory = player.getInventory();
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      if (cards
          .holderOf(inventory.getItem(slot))
          .filter(player.getUniqueId()::equals)
          .isPresent()) {
        inventory.setItem(slot, null);
      }
    }
    ItemStack item =
        cards.create(plugin.getLocaleManager(), player.getUniqueId(), player.getName(), entries);
    for (ItemStack left : inventory.addItem(item).values()) {
      player.getWorld().dropItemNaturally(player.getLocation(), left);
    }
  }

  // ---- 权限 ----

  /** 按持有的等级与进行中的考试，替玩家挂上（或撤下）权限节点。 */
  void apply(Player player) {
    UUID id = player.getUniqueId();
    Set<String> nodes = grantedNodes(id);
    Set<String> previous = attached.getOrDefault(id, Set.of());
    if (nodes.equals(previous)) {
      return;
    }
    PermissionAttachment attachment = attachments.get(id);
    if (nodes.isEmpty()) {
      if (attachment != null) {
        attachment.remove();
      }
      attachments.remove(id);
      attached.remove(id);
    } else {
      if (attachment == null) {
        attachment = player.addAttachment(plugin);
        attachments.put(id, attachment);
      }
      for (String node : previous) {
        if (!nodes.contains(node)) {
          attachment.unsetPermission(node);
        }
      }
      for (String node : nodes) {
        attachment.setPermission(node, true);
      }
      attached.put(id, Set.copyOf(nodes));
    }
    // 命令补全按权限过滤：权限变了要重发命令表。
    player.updateCommands();
  }

  /** 这名玩家此刻应挂上的节点。 */
  Set<String> grantedNodes(UUID playerId) {
    Set<String> nodes = new LinkedHashSet<>();
    if (!config.enabled()) {
      return nodes;
    }
    for (LicenseRecord record : held(playerId)) {
      config.find(record.classId()).ifPresent(license -> nodes.addAll(license.grants()));
    }
    Exam exam = exams.get(playerId);
    if (exam != null) {
      config.find(exam.classId()).ifPresent(license -> nodes.addAll(license.grants()));
    }
    return nodes;
  }

  // ---- 事件 ----

  /** 加入服务器：从数据库读入驾驶证并挂上权限。 */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    load(event.getPlayer());
  }

  /** 离开服务器：权限附件随玩家对象一起失效，只清掉缓存（进行中的考试按截止时刻保留）。 */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    held.remove(id);
    training.remove(id);
    attachments.remove(id);
    attached.remove(id);
  }

  private void load(Player player) {
    UUID id = player.getUniqueId();
    async(
        provider ->
            new Loaded(
                provider.licenses().listByPlayer(id), provider.licenses().trainingByPlayer(id)),
        loaded -> {
          if (!player.isOnline()) {
            return;
          }
          Map<String, LicenseRecord> mine = new LinkedHashMap<>();
          for (LicenseRecord record : loaded.records()) {
            mine.put(record.classId(), record);
          }
          held.put(id, mine);
          Map<String, TrainingRecord> runs = new HashMap<>();
          for (TrainingRecord record : loaded.training()) {
            runs.put(record.classId(), record);
          }
          training.put(id, runs);
          apply(player);
        },
        failure -> plugin.getLogger().warning("读取驾驶证失败（" + player.getName() + "）: " + failure));
  }

  private record Loaded(List<LicenseRecord> records, List<TrainingRecord> training) {}

  // ---- 工具 ----

  /** 在异步线程读写数据库，结果回到主线程；存储不可用时直接走失败回调。 */
  private <T> void async(
      Function<StorageProvider, T> work, Consumer<T> then, Consumer<RuntimeException> failed) {
    StorageManager storage = plugin.getStorageManager();
    Optional<StorageProvider> provider =
        storage != null && storage.isReady() ? storage.provider() : Optional.empty();
    if (provider.isEmpty()) {
      failed.accept(new IllegalStateException("存储未就绪"));
      return;
    }
    if (!plugin.isEnabled()) {
      try {
        then.accept(work.apply(provider.get()));
      } catch (RuntimeException ex) {
        failed.accept(ex);
      }
      return;
    }
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              T result;
              try {
                result = work.apply(provider.get());
              } catch (RuntimeException ex) {
                main(() -> failed.accept(ex));
                return;
              }
              main(() -> then.accept(result));
            });
  }

  private void main(Runnable body) {
    if (plugin.isEnabled()) {
      Bukkit.getScheduler().runTask(plugin, body);
    }
  }

  private static String retryKey(UUID playerId, String classId) {
    return playerId + ":" + classId;
  }

  private static long minutesUntil(Instant now, Instant until) {
    long seconds = Duration.between(now, until).getSeconds();
    return Math.max(1L, (seconds + 59L) / 60L);
  }
}
