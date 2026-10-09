package org.fetarute.fetaruteTCAddon.drive.license;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;
import org.fetarute.fetaruteTCAddon.api.event.DriverStopScoredEvent;
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskFinishedEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardTaskFinishedEvent;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardStations;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardExaminer;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardScore;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSession;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSessionManager;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardTask;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardTasks;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatLocator;
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
 * <p>三种考试：完整做完新手教程（练习步骤不能跳过）；路考——给考生派一段调度列车的区间任务，按终态与成绩判定；车掌——给考生派一段调度列车的车掌区间任务，逐站讲评，做满站数后按车掌成绩判定。
 * 路考与车掌考试前要先练习：练习不派车，玩家自己坐上要练的那列调度列车再报名。考过发一本驾驶证（成书，把记录印出来，只是凭证），丢了可以补发。只在服务器主线程调用（数据库读写在异步线程，结果回到主线程）。
 */
public final class LicenseService implements Listener, GuardExaminer {

  /** 每隔多少 tick 查看一次路考中的列车（防护紧急制动、转 ATO）。 */
  private static final long MONITOR_TICKS = 10L;

  /** 每隔多少 tick 检查一次考试是否超时。 */
  private static final long EXPIRY_CHECK_TICKS = 20L * 20L;

  /** 路考的兜底时限：考试任务自己会作废或结束，这只防万一。 */
  private static final Duration ROAD_TEST_FALLBACK = Duration.ofHours(3);

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

  /** 车掌考试中做完的站。 */
  private final Map<UUID, List<GuardScore.Stop>> guardExamStops = new HashMap<>();

  /** 车掌考试的夹人夹物演练：排在第几站（从 1 起，0 为不演练）、是否已开始。 */
  private record GuardDrillPlan(int stop, boolean started) {}

  private final Map<UUID, GuardDrillPlan> guardDrills = new HashMap<>();

  /** 车掌练习已做满站数、讲评过，还在值乘到交班站：练习的临时权限留到这一班结束。 */
  private final Set<UUID> guardPracticeJudged = new HashSet<>();

  /** 路考中已经提醒过的不及格项（每项只提醒一次）。 */
  private final Map<UUID, Set<String>> examWarned = new HashMap<>();

  /** 在线玩家各级的练习次数；还没读到时没有这一项。 */
  private final Map<UUID, Map<String, TrainingRecord>> training = new HashMap<>();

  /** 正在从数据库读驾驶证的玩家：读完（成败都算）才移出，避免重复读。 */
  private final Set<UUID> loadingNow = new HashSet<>();

  /** 读驾驶证失败的时刻：此后一段时间内命令不再重读，存储故障时不刷屏。 */
  private final Map<UUID, Instant> loadFailedAt = new HashMap<>();

  /** 读驾驶证失败后，命令触发重读前至少等这么久。 */
  private static final Duration LOAD_RETRY_BACKOFF = Duration.ofSeconds(30);

  private final DriverHandbook handbook;
  private final DriverHandbook guardHandbook;
  private final TrainingCoach coach;
  private BukkitTask expiryTask;
  private BukkitTask monitorTask;

  public LicenseService(
      FetaruteTCAddon plugin, Supplier<DriveSessionManager> drive, LicenseConfig config) {
    this.plugin = plugin;
    this.drive = drive;
    this.cards = new LicenseCardItem(plugin);
    this.handbook = new DriverHandbook(plugin);
    this.guardHandbook = DriverHandbook.guard(plugin);
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

  /** 《FTCA 车掌手册》。 */
  public DriverHandbook guardHandbook() {
    return guardHandbook;
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

  /** 驾驶证已读到就返回 {@code true}；还没读到且没在读（例如加入时读失败了）时重新读一次，返回 {@code false}。 */
  public boolean ensureLoaded(Player player) {
    if (loaded(player.getUniqueId())) {
      return true;
    }
    Instant failed = loadFailedAt.get(player.getUniqueId());
    if (failed == null || Instant.now().isAfter(failed.plus(LOAD_RETRY_BACKOFF))) {
      load(player);
    }
    return false;
  }

  public boolean holds(UUID playerId, String classId) {
    Map<String, LicenseRecord> mine = held.get(playerId);
    return mine != null && mine.containsKey(classId);
  }

  public Optional<Exam> exam(UUID playerId) {
    return Optional.ofNullable(exams.get(playerId));
  }

  /** 写在提示句子里的名称；配置里已没有这一级时用标识。 */
  public String nameOf(String classId) {
    return config.find(classId).map(this::displayName).orElse(classId);
  }

  /** 写在提示句子里的名称：准驾等级就是它的名字，附注在名字后加“附注”（如“车掌附注”）。 */
  public String displayName(LicenseClass license) {
    return license.endorsement()
        ? plugin
            .getLocaleManager()
            .text("drive.license.endorsement-name")
            .replace("<name>", license.name())
        : license.name();
  }

  /** 带级别的显示名：“第 N 级 · 名称”；附注与配置里已没有的只写名称。 */
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
    if (!ensureLoaded(player)) {
      return Reply.of("drive.license.loading");
    }
    if (holds(id, license.id())) {
      return new Reply("drive.license.exam.already-held", Map.of("name", displayName(license)));
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
          Map.of("name", displayName(license), "required", String.join("、", missing)));
    }
    Exam running = exams.get(id);
    if (running != null
        && running.kind() == LicenseClass.Exam.TUTORIAL
        && running.classId().equals(license.id())) {
      return restartTutorialExam(player, license, running);
    }
    if (running != null) {
      return inProgress(running);
    }
    Instant now = Instant.now();
    Instant until = retryAfter.get(retryKey(id, license.id()));
    if (until != null && now.isBefore(until)) {
      return new Reply(
          "drive.license.exam.cooldown",
          Map.of(
              "name", displayName(license), "minutes", String.valueOf(minutesUntil(now, until))));
    }
    if (license.exam() != LicenseClass.Exam.TUTORIAL
        && trainingRuns(id, license.id()) < license.trainingRuns()) {
      return new Reply(
          license.exam() == LicenseClass.Exam.GUARD
              ? "drive.license.exam.need-training-guard"
              : "drive.license.exam.need-training",
          Map.of(
              "name",
              displayName(license),
              "class",
              license.id(),
              "done",
              String.valueOf(trainingRuns(id, license.id())),
              "required",
              String.valueOf(license.trainingRuns())));
    }
    return switch (license.exam()) {
      case TUTORIAL -> startTutorialExam(player, license, now);
      case ROAD_TEST -> startRoadTest(player, license, stationArg, now);
      case GUARD -> startGuardExam(player, license, stationArg, now);
    };
  }

  /**
   * 报名练习：不派车，玩家先坐上要练的那列调度列车（中途站停站中，或在终点站待命），再报名。驾驶员练习从这一站起开过几个停车站，有教练提示与一次应急演练；
   * 车掌练习从这一站起值乘几站，逐站讲评，考试中的夹人夹物演练也会安排。练习不发证、不冷却、不记入记录，做满站数计一次练习。
   */
  public Reply startPractice(Player player, String classId) {
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
    if (license.exam() == LicenseClass.Exam.TUTORIAL) {
      return new Reply("drive.license.practice.not-road", Map.of("name", displayName(license)));
    }
    if (!ensureLoaded(player)) {
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
          Map.of("name", displayName(license), "required", String.join("、", missing)));
    }
    Exam running = exams.get(id);
    if (running != null) {
      return inProgress(running);
    }
    DriveSessionManager manager = drive.get();
    if (manager == null) {
      return Reply.of("drive.command.unavailable");
    }
    boolean guard = license.exam() == LicenseClass.Exam.GUARD;
    Optional<GuardSessionManager> guards = manager.guards().filter(GuardSessionManager::available);
    if (guard && guards.isEmpty()) {
      return new Reply(
          "drive.license.exam.guard-unavailable", Map.of("name", displayName(license)));
    }
    Reply busy = crewBusy(manager, id);
    if (busy != null) {
      return busy;
    }
    Optional<TimetableService> timetables = plugin.getTimetableService();
    Optional<String> seated = SeatLocator.locate(player).map(SeatBinding::trainName);
    Map<String, String> values = new LinkedHashMap<>(passValues(license, ""));
    // 配置了练习线路时附一句说明，没配置时留空。
    values.put(
        "routes",
        config.training().routes().isEmpty()
            ? ""
            : plugin
                .getLocaleManager()
                .text("drive.license.practice.routes")
                .replace("<routes>", String.join("、", config.training().routes())));
    if (seated.isEmpty()) {
      return new Reply(
          guard ? "drive.license.practice.guard.choose" : "drive.license.practice.choose", values);
    }
    String train = seated.get();
    values.put("train", train);
    if (timetables.isEmpty()) {
      return new Reply("drive.license.practice.not-at-station", values);
    }
    Optional<ChosenTrain> chosen = chosenTrain(manager, train);
    if (chosen.isEmpty()) {
      return new Reply("drive.license.practice.not-at-station", values);
    }
    // 车掌在交班站停妥即交班、不做那一站的作业：要做满几站，交班站排在其后一站。
    int stops = guard ? license.examStops() + 1 : license.examStops();
    Optional<DriverTaskManager.TaskSpec> spec =
        TaskBoardSource.intervalSpec(
            plugin,
            timetables.get(),
            chosen.get().key(),
            chosen.get().fromSequence(),
            train,
            stops,
            DriverTask.SOURCE_TRAINING,
            Map.of("license", license.id()));
    if (spec.isEmpty()) {
      values.put("need", String.valueOf(stops));
      values.put(
          "left",
          String.valueOf(
              TaskBoardSource.stopsAhead(
                      timetables.get(), chosen.get().key(), chosen.get().fromSequence())
                  .orElse(0)));
      return new Reply(
          guard ? "drive.license.practice.guard.short" : "drive.license.practice.short", values);
    }
    if (!config.training().allowsRoute(spec.get().routeCode())) {
      return new Reply("drive.license.practice.route-not-allowed", values);
    }
    // 已经晚点的车不拿来练习：练习可能再慢一些，调度会救不过来。终点站待命车的晚点是到达的那一趟的，不代表要练的下一趟，不看。
    if (!chosen.get().layover() && lateTrain(train, config.training().drillMaxDelaySeconds())) {
      return new Reply("drive.license.practice.late", values);
    }
    Instant now = Instant.now();
    if (guard) {
      if (guards.get().tasks().taskForTrip(chosen.get().key()).isPresent()
          || guards.get().guardOfTrain(train).isPresent()) {
        return new Reply("drive.license.practice.guard.taken", values);
      }
      return startGuardTask(player, license, guards.get(), spec.get(), now, true);
    }
    DriveConfig current = manager.config();
    if (!current.enabled() || !current.driver().enabled()) {
      return Reply.of("drive.task.claim.disabled");
    }
    if (manager.tasks().congestionProtection().open(now)) {
      return Reply.of("drive.task.claim.protection-active");
    }
    if (manager.tasks().takenKeys().contains(chosen.get().key()) || manager.trainDriven(train)) {
      return new Reply("drive.license.practice.taken", values);
    }
    exams.put(
        id,
        new Exam(license.id(), LicenseClass.Exam.ROAD_TEST, now.plus(ROAD_TEST_FALLBACK), true));
    coach.begin(id, true);
    apply(player);
    DriverTaskManager.ClaimOutcome outcome =
        manager.assignTask(player, spec.get(), DrivingMode.MANUAL, false);
    if (outcome != DriverTaskManager.ClaimOutcome.CLAIMED) {
      endExam(id);
      apply(player);
      return new Reply("drive.license.exam.assign-failed", Map.of("reason", outcome.name()));
    }
    briefRoadTest(player, license, spec.get(), true);
    return Reply.of("drive.license.practice.brief.feedback");
  }

  /**
   * 练习选的车：从它此刻所在的车站起要跑的那一趟。
   *
   * @param key 车次
   * @param fromSequence 从哪个停靠序号起接班
   * @param layover 终点站待命车（跑的是它的下一趟）
   */
  private record ChosenTrain(TaskKey key, int fromSequence, boolean layover) {}

  /**
   * 玩家坐着的这列车能不能拿来练习、跑哪一趟：中途站停站中的车跑它此刻的车次，从这一站起；终点站待命的车跑它的下一趟，从始发站起。
   *
   * @return 车不在车站停着、或不按时刻表运行时为空
   */
  private Optional<ChosenTrain> chosenTrain(DriveSessionManager manager, String train) {
    Optional<TimetableApi.TrainAssignment> assignment =
        DriverTaskManager.timetables().flatMap(api -> api.getAssignment(train));
    if (assignment.isPresent()
        && assignment.get().nextStopSequence().isPresent()
        && assignment.get().lastStopSequence().isPresent()
        && manager.tasks().isDwelling(train)) {
      // 停在哪一站看绑定记下的最近一站；还没记下时不知道停在哪一站，不拿来练习。
      TimetableApi.TrainAssignment current = assignment.get();
      return Optional.of(
          new ChosenTrain(
              new TaskKey(current.timetableId(), current.tripCode(), current.serviceDate()),
              current.lastStopSequence().get(),
              false));
    }
    if (plugin.getLayoverRegistry().flatMap(layovers -> layovers.get(train)).isEmpty()) {
      return Optional.empty();
    }
    return plugin
        .getTimetableService()
        .flatMap(service -> service.nextDepartureOf(train))
        .map(
            next ->
                new ChosenTrain(
                    new TaskKey(next.timetable().id(), next.trip().tripCode(), next.serviceDate()),
                    0,
                    true));
  }

  /** 报名路考、车掌考试或练习前：不能正在驾驶、值乘，也不能领着驾驶或车掌任务。 */
  private static Reply crewBusy(DriveSessionManager manager, UUID id) {
    if (manager.sessionOf(id).isPresent()) {
      return Reply.of("drive.license.exam.driving");
    }
    if (manager.tasks().activeTaskOf(id).isPresent()) {
      return Reply.of("drive.license.exam.has-task");
    }
    Optional<GuardSessionManager> guards = manager.guards();
    if (guards.map(found -> found.isOnDuty(id)).orElse(false)) {
      return Reply.of("drive.license.exam.guard-on-duty");
    }
    if (guards.flatMap(found -> found.tasks().activeTaskOf(id)).isPresent()) {
      return Reply.of("drive.license.exam.has-guard-task");
    }
    return null;
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
    // 报名前已在做的教程不算：从第一步重来，之前跳过的练习步骤也不带进考试。
    manager.restartTutorial(player);
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

  /** 车掌考试：和路考一样在报名的车站派一段区间任务，列车到站后坐进车尾驾驶室即上岗，做满几站作业，逐站讲评，按车掌成绩判定。 */
  private Reply startGuardExam(
      Player player, LicenseClass license, Optional<String> stationArg, Instant now) {
    DriveSessionManager manager = drive.get();
    Optional<GuardSessionManager> guards =
        manager == null
            ? Optional.empty()
            : manager.guards().filter(GuardSessionManager::available);
    if (guards.isEmpty()) {
      // 车掌功能关着或不可用：报了名也上不了岗，不登记考试。
      return new Reply(
          "drive.license.exam.guard-unavailable", Map.of("name", displayName(license)));
    }
    Reply busy = crewBusy(manager, player.getUniqueId());
    if (busy != null) {
      return busy;
    }
    StationPick pick = examStation(player, stationArg);
    if (pick.problem() != null) {
      return pick.problem();
    }
    TaskBoardSource.Station station = pick.station();
    Optional<TimetableService> timetables = plugin.getTimetableService();
    if (timetables.isEmpty()) {
      return new Reply("drive.license.exam.no-trip", Map.of("station", station.name()));
    }
    GuardTasks tasks = guards.get().tasks();
    List<TaskBoardEntries.Row> rows =
        TaskBoardSource.departures(
            plugin, station, now, manager.config().driver().recovery().taskWindowMinutes());
    for (TaskBoardEntries.Row row :
        TaskBoardEntries.select(rows, tasks.claimants().keySet(), now, EXAM_CANDIDATES)) {
      if (row.plannedDeparture().isBefore(now.plus(EXAM_BOARDING_LEAD))
          || lateTrain(row.trainName(), config.training().drillMaxDelaySeconds())
          || (row.trainName() != null && guards.get().guardOfTrain(row.trainName()).isPresent())) {
        continue;
      }
      // 车掌在交班站停妥即交班、不做那一站的作业：要做满几站，交班站排在其后一站。
      Optional<DriverTaskManager.TaskSpec> spec =
          TaskBoardSource.examSpec(
              plugin,
              timetables.get(),
              row,
              station,
              license.examStops() + 1,
              DriverTask.SOURCE_EXAM,
              Map.of("license", license.id()));
      if (spec.isPresent()) {
        return startGuardTask(player, license, guards.get(), spec.get(), now, false);
      }
    }
    return new Reply("drive.license.exam.no-trip", Map.of("station", station.name()));
  }

  /** 登记车掌考试或练习、派出车掌任务，并说明考（练）哪一班、怎么上岗。 */
  private Reply startGuardTask(
      Player player,
      LicenseClass license,
      GuardSessionManager guards,
      DriverTaskManager.TaskSpec spec,
      Instant now,
      boolean practice) {
    UUID id = player.getUniqueId();
    exams.put(
        id,
        new Exam(license.id(), LicenseClass.Exam.GUARD, now.plus(ROAD_TEST_FALLBACK), practice));
    guardExamStops.remove(id);
    guardPracticeJudged.remove(id);
    boolean drill = !practice || config.training().drill();
    guardDrills.put(
        id,
        new GuardDrillPlan(
            drill
                ? GuardExam.drillStop(license, bound -> ThreadLocalRandom.current().nextInt(bound))
                : 0,
            false));
    // 考试与练习期间临时有车掌权限（考完、练完或作废后收回）。
    apply(player);
    GuardTasks.ClaimOutcome outcome = guards.assign(player, spec, false);
    if (outcome != GuardTasks.ClaimOutcome.CLAIMED) {
      endExam(id);
      apply(player);
      return new Reply("drive.license.exam.assign-failed", Map.of("reason", outcome.name()));
    }
    Map<String, String> values = new LinkedHashMap<>(passValues(license, ""));
    values.putAll(tripValues(spec));
    values.put("seconds", String.valueOf(license.drillSeconds()));
    String brief =
        practice ? "drive.license.practice.guard.brief." : "drive.license.exam.brief.guard.";
    tell(player, brief + "header", values);
    tell(player, brief + "route", values);
    if (practice) {
      tell(player, "drive.license.practice.guard.brief.what", values);
    }
    for (String line : List.of("duties", "watch", "pass")) {
      tell(player, "drive.license.exam.brief.guard." + line, values);
    }
    if (!license.allowWrongDoor()) {
      tell(player, "drive.license.exam.brief.guard.no-wrong-door", values);
    }
    if (drill && license.drillSeconds() > 0) {
      tell(player, "drive.license.exam.brief.guard.drill", values);
    }
    tell(player, brief + "how", values);
    guardHandbook.give(player, plugin.getLocaleManager(), true);
    return new Reply(brief + "handbook", values);
  }

  /** 考试与练习派的那一班车，给玩家看的说明：线路、开往、站台、发车时刻、接班站与交班站，车次号只作附注。 */
  private Map<String, String> tripValues(DriverTaskManager.TaskSpec spec) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("trip", spec.key().tripCode());
    values.put("train", spec.trainName() == null ? "" : spec.trainName());
    values.put("takeover", spec.stationName());
    values.put("handover", spec.handoverStationName());
    // 改名前的占位符：服务器上改过的旧文案照常显示。
    values.put("board", spec.stationName());
    values.put("alight", spec.handoverStationName());
    TaskBoardSource.TripLabel label =
        plugin
            .getTimetableService()
            .map(timetables -> TaskBoardSource.label(plugin, timetables, spec))
            .orElse(
                new TaskBoardSource.TripLabel(
                    "",
                    spec.handoverStationName() == null ? "" : spec.handoverStationName(),
                    "-",
                    ""));
    values.put("line", label.line());
    values.put("destination", label.destination());
    values.put(
        "platform",
        "-".equals(label.platform())
            ? ""
            : plugin
                .getLocaleManager()
                .text("drive.license.exam.platform")
                .replace("<platform>", label.platform()));
    values.put("time", label.time());
    return values;
  }

  /**
   * 路考与车掌考试的接班站：写了站码按站码找，否则取玩家附近的车站。
   *
   * @param station 找到的车站
   * @param problem 找不到时给玩家的回复
   */
  private record StationPick(TaskBoardSource.Station station, Reply problem) {}

  private StationPick examStation(Player player, Optional<String> stationArg) {
    if (stationArg.filter(arg -> !arg.isBlank()).isPresent()) {
      String code = stationArg.get().trim();
      TaskBoardStations.Lookup lookup =
          TaskBoardStations.find(TaskBoardSource.stations(plugin), code);
      if (lookup.outcome() == TaskBoardStations.Outcome.AMBIGUOUS) {
        return new StationPick(
            null,
            new Reply(
                "drive.task.board.station-ambiguous",
                Map.of("station", code, "candidates", String.join(", ", lookup.candidates()))));
      }
      if (lookup.outcome() == TaskBoardStations.Outcome.NOT_FOUND) {
        return new StationPick(
            null, new Reply("drive.task.board.station-not-found", Map.of("station", code)));
      }
      return lookup
          .station()
          .map(found -> new StationPick(found, null))
          .orElseGet(() -> new StationPick(null, Reply.of("drive.task.board.no-station")));
    }
    return TaskBoardSource.nearestStation(plugin, player.getLocation())
        .map(found -> new StationPick(found, null))
        .orElseGet(() -> new StationPick(null, Reply.of("drive.task.board.no-station")));
  }

  @Override
  public boolean examining(UUID playerId) {
    Exam exam = exams.get(playerId);
    return exam != null && exam.kind() == LicenseClass.Exam.GUARD && onExamDuty(playerId, exam);
  }

  @Override
  public boolean practicing(UUID playerId) {
    Exam exam = exams.get(playerId);
    return exam != null && exam.training() && examining(playerId);
  }

  /** 正在值乘车掌考试或练习派的那一班：考官只认这一班上做的站，玩家另上别的车做的不算。 */
  private boolean onExamDuty(UUID playerId, Exam exam) {
    String source = exam.training() ? DriverTask.SOURCE_TRAINING : DriverTask.SOURCE_EXAM;
    DriveSessionManager manager = drive.get();
    return manager != null
        && manager
            .guards()
            .flatMap(guards -> guards.tasks().activeTaskOf(playerId))
            .filter(task -> task.state() == GuardTask.State.ON_DUTY && source.equals(task.source()))
            .isPresent();
  }

  /** 车掌考试或练习中做完一站：讲评这一站。考试有不及格项或做满站数就判定；练习做满站数才按考试标准讲评并计次。 */
  @Override
  public void onStopWorked(Player player, GuardScore.Stop stop) {
    UUID id = player.getUniqueId();
    Exam exam = exams.get(id);
    if (exam == null
        || exam.kind() != LicenseClass.Exam.GUARD
        || guardPracticeJudged.contains(id)
        || !onExamDuty(id, exam)) {
      return;
    }
    Optional<LicenseClass> license = config.find(exam.classId());
    if (license.isEmpty()) {
      endExam(id);
      apply(player);
      return;
    }
    List<GuardScore.Stop> stops = guardExamStops.computeIfAbsent(id, key -> new ArrayList<>());
    stops.add(stop);
    Map<String, String> values = new LinkedHashMap<>(GuardExam.review(stop));
    values.put("done", String.valueOf(stops.size()));
    values.put("total", String.valueOf(license.get().examStops()));
    tell(
        player,
        exam.training() ? "drive.license.practice.guard.stop" : "drive.license.exam.guard.stop",
        values);
    if (exam.training()) {
      if (stops.size() >= license.get().examStops()) {
        GuardExam.judge(license.get(), stops)
            .ifPresent(result -> finishGuardPractice(id, player, license.get(), result, true));
      }
      return;
    }
    GuardExam.judge(license.get(), stops)
        .ifPresent(
            result -> {
              finishGuardExam(id, player, exam, license.get(), result);
              if (result.verdict() != ExamEvaluation.Verdict.PASSED) {
                endGuardDutyLater(id, exam);
              }
            });
  }

  /** 考试期间临时的车掌权限已收回：下一拍结束这次值乘（不在车掌会话的处理中途结束它）。 */
  private void endGuardDutyLater(UUID id, Exam exam) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              DriveSessionManager manager = drive.get();
              if (manager != null && !holds(id, exam.classId())) {
                manager.guards().ifPresent(guards -> guards.stop(id, GuardSession.EndReason.EXAM));
              }
            });
  }

  /** 车掌考试或练习中按下关门：到了排定的那一站、列车不太晚点就演练夹人夹物（只一次），晚点过大顺延到下一站。 */
  @Override
  public int drillDue(UUID playerId, String trainName) {
    Exam exam = exams.get(playerId);
    GuardDrillPlan plan = guardDrills.get(playerId);
    if (exam == null
        || exam.kind() != LicenseClass.Exam.GUARD
        || plan == null
        || plan.started()
        || guardPracticeJudged.contains(playerId)
        || !onExamDuty(playerId, exam)) {
      return 0;
    }
    Optional<LicenseClass> license = config.find(exam.classId());
    int worked = guardExamStops.getOrDefault(playerId, List.of()).size();
    if (license.isEmpty()
        || !GuardExam.drillDue(plan.stop(), worked)
        || lateTrain(trainName, config.training().drillMaxDelaySeconds())) {
      return 0;
    }
    guardDrills.put(playerId, new GuardDrillPlan(plan.stop(), true));
    return license.get().drillSeconds();
  }

  /** 演练结束：处置完成告诉考生用时；考试中没按时处置当场不及格，练习中只提醒。 */
  @Override
  public void onDrillDone(Player player, String station, boolean handled, double seconds) {
    UUID id = player.getUniqueId();
    Exam exam = exams.get(id);
    if (exam == null || exam.kind() != LicenseClass.Exam.GUARD) {
      return;
    }
    Map<String, String> values =
        Map.of("station", station, "seconds", String.format(Locale.ROOT, "%.1f", seconds));
    if (handled) {
      tell(
          player,
          exam.training()
              ? "drive.license.practice.guard.drill-ok"
              : "drive.license.exam.guard.drill-ok",
          values);
      return;
    }
    if (exam.training()) {
      tell(player, "drive.license.practice.guard.drill-missed", values);
      return;
    }
    Optional<LicenseClass> license = config.find(exam.classId());
    if (license.isEmpty()) {
      endExam(id);
      apply(player);
      return;
    }
    finishGuardExam(id, player, exam, license.get(), GuardExam.drillMissed(station));
    endGuardDutyLater(id, exam);
  }

  /** 车掌考试或练习派的那一班值乘结束（还没判定）：考试中连续超时、漏乘、换端没坐进车尾为不及格，其余不计成绩；练习中途结束不计次。练习讲评过、还在值乘到交班站的，这时收回临时权限。 */
  @Override
  public void onDutyEnded(UUID playerId, GuardSession.EndReason reason) {
    Exam exam = exams.get(playerId);
    if (exam == null
        || exam.kind() != LicenseClass.Exam.GUARD
        || reason == GuardSession.EndReason.EXAM
        || !onExamDuty(playerId, exam)) {
      return;
    }
    Optional<LicenseClass> license = config.find(exam.classId());
    Player player = Bukkit.getPlayer(playerId);
    if (license.isEmpty() || guardPracticeJudged.contains(playerId)) {
      endExam(playerId);
      if (player != null) {
        apply(player);
      }
      return;
    }
    if (exam.training()) {
      finishGuardPractice(playerId, player, license.get(), GuardExam.ended(reason), false);
      endExam(playerId);
      if (player != null) {
        apply(player);
      }
      return;
    }
    finishGuardExam(playerId, player, exam, license.get(), GuardExam.ended(reason));
  }

  /**
   * 车掌考试或练习派的任务了结时考试还登记着：没上岗就了结（列车没等到、已开走、放弃）时考试不计成绩、可以马上重考，练习不计次；
   * 上岗做过作业、却在值乘中先了结了（列车换了车次）时按值乘提前结束判。这一班值乘结束时，值乘结束已经了结了考试，这里不再管。玩家另在别的车上值乘不影响这里。
   */
  @EventHandler
  public void onGuardTaskFinished(GuardTaskFinishedEvent event) {
    GuardApi.TaskView task = event.getTask();
    boolean practice = DriverTask.SOURCE_TRAINING.equals(task.source());
    if (!practice && !DriverTask.SOURCE_EXAM.equals(task.source())) {
      return;
    }
    UUID id = task.playerId();
    Exam exam = exams.get(id);
    if (exam == null || exam.kind() != LicenseClass.Exam.GUARD || exam.training() != practice) {
      return;
    }
    boolean judged = guardPracticeJudged.contains(id);
    Player player = Bukkit.getPlayer(id);
    Optional<LicenseClass> license = config.find(exam.classId());
    if (task.points().isPresent() && !judged && license.isPresent()) {
      ExamEvaluation.Result ended =
          new ExamEvaluation.Result(ExamEvaluation.Verdict.VOID, "ended", Map.of());
      if (practice) {
        finishGuardPractice(id, player, license.get(), ended, false);
        endExam(id);
        if (player != null) {
          apply(player);
        }
      } else {
        finishGuardExam(id, player, exam, license.get(), ended);
      }
      return;
    }
    endExam(id);
    if (player == null) {
      return;
    }
    apply(player);
    if (judged) {
      return;
    }
    Map<String, String> values = new LinkedHashMap<>();
    values.put("name", nameOf(exam.classId()));
    values.put("class", exam.classId());
    values.put("trip", task.tripCode());
    tell(
        player,
        practice
            ? "drive.license.practice.guard.not-started"
            : "drive.license.exam.guard.not-started",
        values);
  }

  /** 车掌练习讲评：按考试标准评判；做满站数计一次练习，告诉玩家还差几次或可以报名考试。 */
  private void finishGuardPractice(
      UUID id, Player player, LicenseClass license, ExamEvaluation.Result result, boolean done) {
    int runs = trainingRuns(id, license.id()) + (done ? 1 : 0);
    if (done) {
      // 讲评过的练习还要值乘到交班站：临时权限留到这一班结束。
      guardPracticeJudged.add(id);
      if (training.containsKey(id)) {
        // 还没读到练习次数时不写：按 0 次加一会覆盖库里的累计。
        recordTraining(id, player, license.id(), runs);
      }
    }
    if (player == null) {
      return;
    }
    Map<String, String> values = new LinkedHashMap<>(result.values());
    values.putAll(passValues(license, values.getOrDefault("points", "")));
    values.put("done", String.valueOf(runs));
    values.put("required", String.valueOf(license.trainingRuns()));
    LocaleManager locale = plugin.getLocaleManager();
    TagResolver.Builder resolver =
        TagResolver.builder()
            .resolver(
                Placeholder.component(
                    "reason",
                    locale.component(
                        "drive.license.exam.guard.reason." + result.reason(), values)));
    values.forEach((key, value) -> resolver.resolver(Placeholder.unparsed(key, value)));
    tell(player, "drive.license.practice.guard.review.header", values);
    player.sendMessage(
        locale.component(
            "drive.license.practice.guard.review."
                + result.verdict().name().toLowerCase(Locale.ROOT),
            resolver.build()));
    if (result.verdict() == ExamEvaluation.Verdict.FAILED) {
      tell(player, "drive.license.exam.guard.tip." + result.reason(), values);
    }
    if (!done) {
      tell(player, "drive.license.practice.guard.review.not-counted", values);
    }
    String next;
    if (holds(id, license.id())) {
      next = "drive.license.practice.review.again";
    } else if (runs >= license.trainingRuns()) {
      next = "drive.license.practice.guard.review.ready";
    } else {
      next = "drive.license.practice.guard.review.more";
    }
    tell(player, next, values);
  }

  /** 车掌考试判定：及格发证，不及格进入冷却，不计成绩的可以马上重考。 */
  private void finishGuardExam(
      UUID id, Player player, Exam exam, LicenseClass license, ExamEvaluation.Result result) {
    endExam(id);
    Map<String, String> values = new LinkedHashMap<>(result.values());
    values.putAll(passValues(license, values.getOrDefault("points", "")));
    switch (result.verdict()) {
      case PASSED -> {
        String name =
            player != null
                ? player.getName()
                : Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("");
        grant(id, name, license, "exam");
      }
      case FAILED -> {
        Instant until = Instant.now().plus(Duration.ofMinutes(config.retryCooldownMinutes()));
        retryAfter.put(retryKey(id, license.id()), until);
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
                    locale.component(
                        "drive.license.exam.guard.reason." + result.reason(), values)));
    values.forEach((key, value) -> resolver.resolver(Placeholder.unparsed(key, value)));
    player.sendMessage(
        locale.component(
            "drive.license.exam.guard." + result.verdict().name().toLowerCase(Locale.ROOT),
            resolver.build()));
    switch (result.verdict()) {
      case PASSED -> {
        tell(player, "drive.license.granted-desc", values);
        suggestNext(player);
      }
      case FAILED -> {
        tell(player, "drive.license.exam.guard.tip." + result.reason(), values);
        tell(player, "drive.license.exam.retry", values);
      }
      case VOID -> tell(player, "drive.license.exam.retry-now", values);
    }
  }

  /** 已有进行中的考试或练习时的回复：路考、车掌考试与练习可以放弃任务了结，提示里带放弃按钮。 */
  private Reply inProgress(Exam running) {
    String key =
        switch (running.kind()) {
          case TUTORIAL -> "drive.license.exam.in-progress";
          case ROAD_TEST -> running.training()
              ? "drive.license.practice.in-progress"
              : "drive.license.exam.in-progress-road-test";
          case GUARD -> running.training()
              ? "drive.license.practice.guard.in-progress"
              : "drive.license.exam.in-progress-guard";
        };
    return new Reply(key, Map.of("name", nameOf(running.classId())));
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
    // 不在驾驶时教程要等下次开车才从第一步开始，回复不能说“已重新开始”。
    return new Reply(
        key == null
            ? "drive.license.exam.tutorial-restarted"
            : "drive.license.exam.tutorial-restart-pending",
        Map.of(
            "name",
            license.name(),
            "minutes",
            String.valueOf(minutesUntil(Instant.now(), exam.deadline()))));
  }

  /** 路考：在报名的车站派一段区间任务，列车到站后坐进车头驾驶室接班，按终态与成绩判定。 */
  private Reply startRoadTest(
      Player player, LicenseClass license, Optional<String> stationArg, Instant now) {
    UUID id = player.getUniqueId();
    DriveSessionManager manager = drive.get();
    if (manager == null) {
      return Reply.of("drive.command.unavailable");
    }
    Reply busy = crewBusy(manager, id);
    if (busy != null) {
      return busy;
    }
    DriveConfig current = manager.config();
    if (!current.enabled() || !current.driver().enabled()) {
      return Reply.of("drive.task.claim.disabled");
    }
    if (manager.tasks().congestionProtection().open(now)) {
      // 拥堵保护中：不再往正式车次上放考生。
      return Reply.of("drive.task.claim.protection-active");
    }
    StationPick pick = examStation(player, stationArg);
    if (pick.problem() != null) {
      return pick.problem();
    }
    TaskBoardSource.Station station = pick.station();
    Optional<TimetableService> timetables = plugin.getTimetableService();
    if (timetables.isEmpty()) {
      return new Reply("drive.license.exam.no-trip", Map.of("station", station.name()));
    }
    List<TaskBoardEntries.Row> rows =
        TaskBoardSource.departures(
            plugin, station, now, current.driver().recovery().taskWindowMinutes());
    for (TaskBoardEntries.Row row :
        TaskBoardEntries.select(rows, manager.tasks().takenKeys(), now, EXAM_CANDIDATES)) {
      // 马上就要开走的车（多半正在停站）赶不上：任务会在考生到站前作废。
      if (row.plannedDeparture().isBefore(now.plus(EXAM_BOARDING_LEAD))) {
        continue;
      }
      // 已经晚点的车不派给考生：路考可能再慢一些，调度会救不过来。
      if (lateTrain(row.trainName(), config.training().drillMaxDelaySeconds())) {
        continue;
      }
      Optional<DriverTaskManager.TaskSpec> spec =
          TaskBoardSource.examSpec(
              plugin,
              timetables.get(),
              row,
              station,
              license.examStops(),
              DriverTask.SOURCE_EXAM,
              Map.of("license", license.id()));
      if (spec.isEmpty()) {
        continue;
      }
      // 先挂上所考那一级的权限：领取通知里的接班、开车命令要能用。
      exams.put(
          id,
          new Exam(license.id(), LicenseClass.Exam.ROAD_TEST, now.plus(ROAD_TEST_FALLBACK), false));
      coach.begin(id, false);
      apply(player);
      DriverTaskManager.ClaimOutcome outcome =
          manager.assignTask(player, spec.get(), DrivingMode.MANUAL, false);
      if (outcome != DriverTaskManager.ClaimOutcome.CLAIMED) {
        endExam(id);
        apply(player);
        return new Reply("drive.license.exam.assign-failed", Map.of("reason", outcome.name()));
      }
      briefRoadTest(player, license, spec.get(), false);
      return Reply.of("drive.license.exam.brief.road-test.feedback");
    }
    return new Reply("drive.license.exam.no-trip", Map.of("station", station.name()));
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
    if (exam == null || exam.kind() != LicenseClass.Exam.ROAD_TEST || exam.training() != practice) {
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
    ExamEvaluation.Result result = ExamEvaluation.roadTest(license.get(), task, event.getScore());
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
    if (player == null || license.isEmpty() || exam.kind() != LicenseClass.Exam.ROAD_TEST) {
      return;
    }
    DriveApi.StopResult stop = event.getStop();
    int done = examStopsDone.merge(id, 1, Integer::sum);
    LocaleManager locale = plugin.getLocaleManager();
    String outcome = stop.outcome().name().toLowerCase(Locale.ROOT);
    String doors = stop.wrongDoor() ? "wrong" : stop.doorsTakenOver() ? "taken-over" : "ok";
    TagResolver resolver =
        TagResolver.builder()
            .resolver(
                Placeholder.component(
                    "outcome", locale.component("drive.license.exam.stop.outcome." + outcome)))
            // 改名前的占位符：服务器上改过的旧文案照常显示。
            .resolver(
                Placeholder.component(
                    "window", locale.component("drive.license.exam.stop.outcome." + outcome)))
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
        && (stop.outcome() == DriveApi.StopOutcome.OVERRUN
            || stop.outcome() == DriveApi.StopOutcome.SKIPPED)) {
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
      if (entry.getValue().kind() != LicenseClass.Exam.ROAD_TEST
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
        tell(
            player,
            license.endorsement() ? "drive.license.next-endorsement" : "drive.license.next",
            passValues(license, ""));
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

  /** 路考与练习的说明：考（练）哪一班车、及格要求、怎么开始，并发一本驾驶员手册。 */
  private void briefRoadTest(
      Player player, LicenseClass license, DriverTaskManager.TaskSpec spec, boolean practice) {
    Map<String, String> values = new LinkedHashMap<>(passValues(license, ""));
    values.putAll(tripValues(spec));
    values.put("stops", String.valueOf(license.examStops()));
    values.put("min", String.valueOf(license.minPoints()));
    String brief =
        practice ? "drive.license.practice.brief." : "drive.license.exam.brief.road-test.";
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
      tell(player, "drive.license.exam.brief.road-test.no-emergency", values);
    }
    if (!license.allowOverrun()) {
      tell(player, "drive.license.exam.brief.road-test.no-overrun", values);
    }
    if (!license.allowWrongDoor()) {
      tell(player, "drive.license.exam.brief.road-test.no-wrong-door", values);
    }
    tell(
        player,
        practice ? "drive.license.practice.brief.how" : "drive.license.exam.brief.road-test.how",
        values);
    handbook.give(player, plugin.getLocaleManager(), true);
    tell(player, brief + "handbook", values);
  }

  /** 发证、提示里常用的占位符：级别、名称、标识、持证后能做什么、得分。 */
  private Map<String, String> passValues(LicenseClass license, String points) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("level", String.valueOf(config.levelOf(license.id())));
    values.put("name", displayName(license));
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
    guardExamStops.remove(playerId);
    guardDrills.remove(playerId);
    guardPracticeJudged.remove(playerId);
    examWarned.remove(playerId);
    return coach.end(playerId);
  }

  /** 练习结束：按路考标准讲评、讲评应急处置；开到交班站计一次练习，告诉玩家还差几次或可以报名路考。 */
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
    ExamEvaluation.Result result = ExamEvaluation.roadTest(license.get(), task, score);
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
      if (exam.kind() == LicenseClass.Exam.GUARD && onExamDuty(id, exam)) {
        // 正在值乘考试或练习派的那一班：等做满站数或值乘结束再判定。
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
    if (!ensureLoaded(player)) {
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
    List<LicenseCardItem.Entry> endorsements = new ArrayList<>();
    for (LicenseRecord record : held(player.getUniqueId())) {
      Optional<LicenseClass> license = config.find(record.classId());
      if (license.filter(LicenseClass::endorsement).isPresent()) {
        // 印在“附注”一栏下，只写名字（如“车掌”）。
        endorsements.add(new LicenseCardItem.Entry(license.get().name(), record.grantedAt()));
      } else {
        entries.add(new LicenseCardItem.Entry(levelName(record.classId()), record.grantedAt()));
      }
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
        cards.create(
            plugin.getLocaleManager(),
            player.getUniqueId(),
            player.getName(),
            entries,
            endorsements);
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
    loadFailedAt.remove(id);
    attachments.remove(id);
    attached.remove(id);
  }

  private void load(Player player) {
    UUID id = player.getUniqueId();
    if (!loadingNow.add(id)) {
      return;
    }
    async(
        provider ->
            new Loaded(
                provider.licenses().listByPlayer(id), provider.licenses().trainingByPlayer(id)),
        loaded -> {
          loadingNow.remove(id);
          loadFailedAt.remove(id);
          // 按 UUID 取此刻在线的玩家：读的途中下线又上线时，加入时那次读被并入这一次，结果要给新的玩家对象。
          Player online = Bukkit.getPlayer(id);
          if (online == null) {
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
          apply(online);
        },
        failure -> {
          loadingNow.remove(id);
          loadFailedAt.put(id, Instant.now());
          plugin.getLogger().warning("读取驾驶证失败（" + player.getName() + "）: " + failure);
        });
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
