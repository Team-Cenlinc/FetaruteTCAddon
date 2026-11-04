package org.fetarute.fetaruteTCAddon.stationmaster;

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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StationMasterGate;
import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.drive.crew.CallResult;
import org.fetarute.fetaruteTCAddon.drive.crew.CallerRole;
import org.fetarute.fetaruteTCAddon.drive.crew.CrewDuty;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerLink;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterScore;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterShift;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 站长：守在车站的站台上，管所辖股道上列车的发车信号、扣车与催关。
 *
 * <p>站长是可选的增强：没人值守的股道照常自动办理。
 *
 * <p>一次只办一列：系统按站牌给站长排“当前这一列”，从接车、停站、发车信号一直办到出站监视结束，办完至少歇 {@link
 * StationMasterConfig.Job#restSeconds} 秒才排下一列，几条股道之间尽量交替。没排给站长的车按自动办理，不等信号、不记分，也不向站长提要求。
 * 当前这一列关好车门、出站放行后要等站长的发车信号；站长迟迟不发（超过 {@link StationMasterConfig#signalTimeoutSeconds}）由系统代发并记一笔。
 *
 * <p>分股道：第一名站长管全站；后来的人按时刻表的工作量从没被分过的人手里分走相连的一段（见 {@link
 * TrackSplitter}），已分出去的那份不重切。下岗时分走的股道交还给分出它的人，那人已下岗时转为自动。
 *
 * <p>只在服务器主线程使用；一切只在内存里，重启与重载即下岗。
 */
public final class StationMasterService implements StationMasterGate {

  /** 站长权限。 */
  public static final String PERMISSION = "fetarute.stationmaster";

  /** 上岗的结果。 */
  public enum DutyStart {
    STARTED,
    DISABLED,
    NO_PERMISSION,
    ALREADY_ON_DUTY,
    BUSY_DRIVER,
    BUSY_GUARD,
    BUSY_SIGNALLER,
    NOT_AT_STATION,
    NO_TRACKS,
    STATION_COVERED
  }

  /**
   * 一名站长的值守概况。
   *
   * @param player 站长名
   * @param station 车站名
   * @param tracks 所辖股道号
   * @param signals 亲手发的发车信号数
   * @param autoSignals 超时由系统代发的数
   * @param holds 扣车次数
   */
  public record DutyStatus(
      String player,
      String station,
      List<Integer> tracks,
      int signals,
      int autoSignals,
      int holds) {
    public DutyStatus {
      tracks = List.copyOf(tracks);
    }
  }

  private final StationMasterWorld world;
  private final Supplier<StationMasterConfig> config;
  private final Supplier<Instant> clock;
  private final StationMasterMessenger messenger;

  private Function<UUID, Optional<CrewDuty.Role>> crewBusy = player -> Optional.empty();

  /** 在岗的站长，按上岗先后。 */
  private final Map<UUID, Duty> duties = new LinkedHashMap<>();

  /** 停在站台上的车（站台登记的），按列车名（小写）。 */
  private final Map<String, Stop> stops = new HashMap<>();

  /** 站长扣着的车，按列车名（小写）。 */
  private final Map<String, Hold> holds = new HashMap<>();

  /** 站台紧急停车停住后扣着的车，按列车名（小写）。 */
  private final Map<String, Hold> emergencies = new HashMap<>();

  /** 按站牌预计接下来要停到所辖股道上、还没开到股道附近的车，按列车名（小写）。 */
  private final Map<String, Inbound> inbound = new HashMap<>();

  /** 站牌往后看多久的来车。 */
  static final Duration INBOUND_HORIZON = Duration.ofMinutes(3);

  /** 交替的窗口：另一条股道的车比最早的那一列晚这么久以内，就排另一条股道的。 */
  static final Duration ALTERNATE_WINDOW = Duration.ofSeconds(30);

  /** 当前这一列停站这么久还没到等发车信号（长时间折返、按表扣着）：交回自动办理，站长去接下一列。 */
  static final Duration LONG_DWELL = Duration.ofSeconds(60);

  /** 当前这一列从站牌上消失、也没开到股道附近，过这么久就不等了。 */
  static final Duration JOB_LOST_AFTER = Duration.ofSeconds(45);

  /** 按过一次下岗键的站长 → 到几点前再按一次才下岗。 */
  private final Map<UUID, Instant> offPressed = new HashMap<>();

  /** 站长上次呼叫信号员的时刻。 */
  private final Map<UUID, Instant> lastCall = new HashMap<>();

  private SignallerLink signallerLink = SignallerLink.NONE;
  private StationMasterShift.Settlement settlement = StationMasterShift.Settlement.NONE;
  private StationMasterExaminer examiner = StationMasterExaminer.NONE;

  /**
   * 模拟事件的随机数；插件类加载器下找不到 {@code RandomGenerator.getDefault()} 的服务实现，直接用 {@link SplittableRandom}。
   */
  private RandomGenerator random = new SplittableRandom();

  /** 站长此刻要处置的模拟事件。 */
  private final Map<UUID, Sim> sims = new HashMap<>();

  /** 站长下一道模拟事件的时刻。 */
  private final Map<UUID, Instant> nextEventAt = new HashMap<>();

  /** 上岗后多久才出第一道模拟事件。 */
  static final Duration EVENT_WARMUP = Duration.ofSeconds(60);

  /** 到了出题时刻却没有合适的车时，隔多久再看。 */
  static final Duration EVENT_RETRY = Duration.ofSeconds(5);

  /** 模拟事件的种类与该怎么处置。 */
  enum SimKind {
    /** 乘客越过站台安全线：进站的车要站台紧急停车。 */
    TRESPASS(Answer.EMERGENCY),
    /** 车门夹人夹物：扣住车，请乘务员重新开门处置。 */
    TRAPPED(Answer.HOLD),
    /** 乘客奔跑赶车：扣住几秒让乘客上车。 */
    RUNNING(Answer.HOLD);

    final Answer answer;

    SimKind(Answer answer) {
      this.answer = answer;
    }

    String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** 站长对模拟事件的处置：按下的按钮。 */
  enum Answer {
    SIGNAL,
    HOLD,
    CLOSE,
    EMERGENCY;

    String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** 一道模拟事件。 */
  private record Sim(SimKind kind, String trainName, NodeId node, Instant since) {}

  private DutyListener dutyListener = DutyListener.NONE;

  /** 上岗、下岗时通知外壳（装上、撤下快捷栏与侧边栏）。 */
  interface DutyListener {
    DutyListener NONE = new DutyListener() {};

    default void started(UUID player) {}

    default void ended(UUID player) {}
  }

  /** 呼叫信号员的冷却：同一名站长两次呼叫至少隔这么久。 */
  static final Duration CALL_COOLDOWN = Duration.ofSeconds(5);

  /** 下岗键按两次之间的最长间隔。 */
  static final Duration OFF_CONFIRM = Duration.ofSeconds(3);

  StationMasterService(
      StationMasterWorld world,
      Supplier<StationMasterConfig> config,
      Supplier<Instant> clock,
      StationMasterMessenger messenger) {
    this.world = Objects.requireNonNull(world, "world");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.messenger = Objects.requireNonNull(messenger, "messenger");
  }

  /** 玩家此刻在担任的其他角色（驾驶员、车掌、信号员）；担任着时不能上岗。 */
  public void setCrewBusy(Function<UUID, Optional<CrewDuty.Role>> busy) {
    this.crewBusy = busy == null ? player -> Optional.empty() : busy;
  }

  /** 呼叫信号员的去处；{@code null} 为没有信号员模块。 */
  public void setSignallerLink(SignallerLink link) {
    this.signallerLink = link == null ? SignallerLink.NONE : link;
  }

  /** 下岗结算（成绩、记录、奖励）；{@code null} 为不结算。 */
  public void setSettlement(StationMasterShift.Settlement next) {
    this.settlement = next == null ? StationMasterShift.Settlement.NONE : next;
  }

  /** 操作许可证考官；{@code null} 为没有。 */
  public void setExaminer(StationMasterExaminer next) {
    this.examiner = next == null ? StationMasterExaminer.NONE : next;
  }

  /** 模拟事件的随机数（测试固定种子用）；{@code null} 换回默认。 */
  void setRandom(RandomGenerator next) {
    this.random = next == null ? new SplittableRandom() : next;
  }

  /** 上岗、下岗的通知（外壳装上、撤下快捷栏与侧边栏）；{@code null} 为不通知。 */
  void setDutyListener(DutyListener listener) {
    this.dutyListener = listener == null ? DutyListener.NONE : listener;
  }

  /** 在岗的站长。 */
  List<UUID> onDutyPlayers() {
    return List.copyOf(duties.keySet());
  }

  /** 玩家是否在岗。 */
  public boolean onDuty(UUID player) {
    return player != null && duties.containsKey(player);
  }

  /** 任务板：有站台股道的车站，还没人值守的排前面。 */
  List<StationMasterBoard.Entry> board() {
    return world.stations().stream()
        .map(
            station ->
                new StationMasterBoard.Entry(
                    station,
                    world.tracks(station).size(),
                    (int)
                        duties.values().stream()
                            .filter(duty -> duty.station.same(station))
                            .count()))
        .filter(entry -> entry.tracks() > 0)
        .sorted(
            Comparator.comparing((StationMasterBoard.Entry entry) -> entry.masters() > 0)
                .thenComparing(entry -> entry.station().code()))
        .toList();
  }

  /** 任务板上点了一个车站：人在这一站就上岗，否则告诉他去哪。 */
  void boardClicked(
      UUID player, String name, boolean permitted, StationMasterWorld.Station target) {
    if (world.nearestStation(player).filter(target::same).isEmpty()) {
      messenger.send(player, "stationmaster.board.go-there", Map.of("station", target.name()));
      return;
    }
    startDuty(player, name, permitted);
  }

  // ---- 上岗与下岗 ----

  /**
   * 在玩家附近最近的车站上岗；结果直接告诉玩家。
   *
   * @param player 玩家
   * @param name 玩家名
   * @param permitted 玩家有站长权限
   */
  public DutyStart startDuty(UUID player, String name, boolean permitted) {
    DutyStart result = tryStart(player, name, permitted);
    if (result != DutyStart.STARTED) {
      messenger.send(player, "stationmaster.duty.start." + key(result), Map.of());
    }
    return result;
  }

  private DutyStart tryStart(UUID player, String name, boolean permitted) {
    if (!config.get().enabled()) {
      return DutyStart.DISABLED;
    }
    if (!permitted) {
      return DutyStart.NO_PERMISSION;
    }
    if (duties.containsKey(player)) {
      return DutyStart.ALREADY_ON_DUTY;
    }
    Optional<CrewDuty.Role> busy = crewBusy.apply(player);
    if (busy.isPresent()) {
      return switch (busy.get()) {
        case DRIVER -> DutyStart.BUSY_DRIVER;
        case GUARD -> DutyStart.BUSY_GUARD;
        default -> DutyStart.BUSY_SIGNALLER;
      };
    }
    Optional<StationMasterWorld.Station> station = world.nearestStation(player);
    if (station.isEmpty()) {
      return DutyStart.NOT_AT_STATION;
    }
    List<StationMasterWorld.Track> tracks = world.tracks(station.get());
    if (tracks.isEmpty()) {
      return DutyStart.NO_TRACKS;
    }
    List<Duty> colleagues =
        duties.values().stream().filter(duty -> duty.station.same(station.get())).toList();
    Set<NodeId> free = new LinkedHashSet<>();
    tracks.forEach(track -> free.add(track.node()));
    colleagues.forEach(duty -> free.removeAll(duty.tracks));
    Duty duty = new Duty(player, name, station.get(), tracks, clock.get());
    if (!free.isEmpty()) {
      duty.tracks.addAll(free);
    } else {
      Optional<Duty> donor = carve(duty, colleagues);
      if (donor.isEmpty()) {
        return DutyStart.STATION_COVERED;
      }
      duty.donor = Optional.of(donor.get().player);
      messenger.send(
          donor.get().player,
          "stationmaster.duty.handed",
          Map.of(
              "name", name,
              "tracks", trackList(duty, duty.tracks),
              "remaining", trackList(donor.get(), donor.get().tracks)));
    }
    duties.put(player, duty);
    dutyListener.started(player);
    examiner.onDutyStarted(player, station.get().name(), duty.since);
    messenger.tip(player, StationMasterMessenger.Tip.ON_DUTY);
    messenger.alert(
        player,
        "stationmaster.duty.started",
        Map.of("station", station.get().name(), "tracks", trackList(duty, duty.tracks)));
    return DutyStart.STARTED;
  }

  /** 给 {@code duty} 切一段相连的股道：只从没被分过的人（第一名站长，或分出他那份的人已下岗）剩下的股道里切，管得最多的先切；已分出去的那份不重切。切不出来时为空。 */
  private Optional<Duty> carve(Duty duty, List<Duty> colleagues) {
    TrackWorkload workload =
        TrackWorkload.of(
            duty.stationTracks,
            duty.station,
            world.timetables(),
            clock.get(),
            config.get().loadWindow());
    double target = workload.total() / (colleagues.size() + 1);
    List<Duty> roots =
        colleagues.stream()
            .filter(colleague -> colleague.donor.isEmpty())
            .sorted(
                Comparator.comparingDouble((Duty colleague) -> load(colleague, workload))
                    .reversed())
            .toList();
    for (Duty donor : roots) {
      Optional<List<Integer>> piece =
          TrackSplitter.split(indicesOf(donor, donor.tracks), workload, target);
      if (piece.isEmpty()) {
        continue;
      }
      for (int index : piece.get()) {
        NodeId node = duty.stationTracks.get(index).node();
        donor.tracks.remove(node);
        duty.tracks.add(node);
      }
      return Optional.of(donor);
    }
    return Optional.empty();
  }

  private static double load(Duty duty, TrackWorkload workload) {
    double total = 0.0;
    for (int index : indicesOf(duty, duty.tracks)) {
      total += workload.load(index);
    }
    return total;
  }

  /** 股道在全站股道表里的下标，按股道号排。 */
  private static List<Integer> indicesOf(Duty duty, Set<NodeId> nodes) {
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < duty.stationTracks.size(); i++) {
      if (nodes.contains(duty.stationTracks.get(i).node())) {
        indices.add(i);
      }
    }
    return indices;
  }

  /**
   * 下岗：所辖股道交还给分出它的人（那人已下岗时转为自动），扣着的车放行，在等发车信号的车随即按自动办理。
   *
   * @return 玩家原本在岗时为 true
   */
  public boolean endDuty(UUID player, StationMasterShift.EndReason reason) {
    Duty duty = player == null ? null : duties.remove(player);
    if (duty == null) {
      return false;
    }
    Optional<Duty> donor = duty.donor.map(duties::get);
    if (donor.isPresent()) {
      donor.get().tracks.addAll(duty.tracks);
      messenger.send(
          donor.get().player,
          "stationmaster.duty.returned",
          Map.of(
              "name", duty.name,
              "tracks", trackList(duty, duty.tracks),
              "remaining", trackList(donor.get(), donor.get().tracks)));
    }
    for (Duty other : duties.values()) {
      if (other.donor.filter(player::equals).isPresent()) {
        other.donor = Optional.empty();
      }
    }
    List<Hold> released = holds.values().stream().filter(hold -> hold.by.equals(player)).toList();
    for (Hold hold : released) {
      holds.remove(trainKey(hold.trainName));
      world.reevaluate(hold.trainName);
    }
    List<Hold> braked =
        emergencies.values().stream().filter(hold -> hold.by.equals(player)).toList();
    for (Hold hold : braked) {
      emergencies.remove(trainKey(hold.trainName));
      world.refreshSignal(hold.trainName);
    }
    inbound.values().removeIf(next -> next.owner().equals(player));
    offPressed.remove(player);
    lastCall.remove(player);
    sims.remove(player);
    nextEventAt.remove(player);
    dutyListener.ended(player);
    boolean examined = examiner.examining(player);
    boolean practiced = examiner.practicing(player);
    settlement.settle(
        new StationMasterShift(
            player,
            duty.name,
            duty.station.operator(),
            duty.station.code(),
            duty.since,
            clock.get(),
            duty.counts(),
            reason,
            examined,
            practiced));
    examiner.onDutyEnded(player, reason);
    messenger.send(
        player,
        "stationmaster.duty.ended." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-'),
        Map.of());
    return true;
  }

  /** 全部下岗（插件关闭、重载）。 */
  public void shutdown() {
    for (UUID player : List.copyOf(duties.keySet())) {
      endDuty(player, StationMasterShift.EndReason.SHUTDOWN);
    }
    stops.clear();
  }

  /** 玩家下线。 */
  public void playerQuit(UUID player) {
    endDuty(player, StationMasterShift.EndReason.QUIT);
  }

  /** 玩家死亡或改了游戏模式。 */
  public void playerIncapacitated(UUID player) {
    endDuty(player, StationMasterShift.EndReason.INCAPACITATED);
  }

  /** 各站长的值守概况。 */
  public List<DutyStatus> status() {
    return duties.values().stream()
        .map(
            duty ->
                new DutyStatus(
                    duty.name,
                    duty.station.name(),
                    numbers(duty, duty.tracks),
                    duty.signals,
                    duty.autoSignals,
                    duty.holdCount))
        .toList();
  }

  // ---- 站台登记与发车信号 ----

  @Override
  public void stopOpened(
      String trainName, NodeId node, Optional<Duration> closeOpensIn, boolean crewDoors) {
    if (trainName == null || node == null || duties.isEmpty()) {
      return;
    }
    Instant now = clock.get();
    String key = trainKey(trainName);
    Stop previous = stops.get(key);
    Stop stop =
        previous != null && previous.node.equals(node) ? previous : newStop(trainName, node);
    stop.closeOpensAt = closeOpensIn.map(now::plus);
    stop.crewDoors = crewDoors;
    stops.put(key, stop);
    inbound.remove(key);
    handlerAt(key, node)
        .ifPresent(
            duty -> {
              Job job = duty.job;
              job.node = node;
              job.arrival.ifPresent(watch -> finishWatch(watch, true));
              job.arrival = Optional.empty();
              if (!job.arrived) {
                job.arrivedAt = now;
              }
              job.arrived = true;
              clearing(duty, stop, config.get(), now);
            });
  }

  private Stop newStop(String trainName, NodeId node) {
    Stop stop = new Stop(trainName, node);
    stop.lastPassengerStop = world.lastPassengerStop(trainName, node);
    return stop;
  }

  @Override
  public void stopEnded(String trainName, NodeId node) {
    if (trainName == null) {
      return;
    }
    String key = trainKey(trainName);
    Stop stop = stops.get(key);
    if (stop != null && stop.node.equals(node)) {
      stops.remove(key);
      holds.remove(key);
      // 没放行就结束的停站（会话被接管、转入待命）车并没有开走：不算出站，这一列不用办了
      handlerAt(key, node)
          .ifPresent(
              duty -> {
                if (stop.released) {
                  startDeparture(duty, duty.job);
                } else {
                  dropJob(duty, duty.job);
                }
              });
    }
  }

  @Override
  public boolean holdsDeparture(String trainName, NodeId node) {
    if (trainName == null || node == null || duties.isEmpty()) {
      return false;
    }
    String key = trainKey(trainName);
    Stop stop = stops.get(key);
    if (stop == null || !stop.node.equals(node)) {
      stop = newStop(trainName, node);
      stops.put(key, stop);
    }
    if (stop.released) {
      return false;
    }
    Optional<Duty> handler = handlerAt(key, node);
    if (handler.isEmpty()) {
      return false;
    }
    Duty duty = handler.get();
    StationMasterConfig settings = config.get();
    Instant now = clock.get();
    if (clearing(duty, stop, settings, now)) {
      return true;
    }
    if (stop.presignalledAt.isPresent()) {
      stop.released = true;
      return false;
    }
    if (stop.waitingSince.isEmpty()) {
      stop.waitingSince = Optional.of(now);
      messenger.tip(duty.player, StationMasterMessenger.Tip.SIGNAL);
      messenger.notice(
          duty.player,
          near(duty, trainName, settings)
              ? "stationmaster.signal.waiting"
              : "stationmaster.signal.waiting-away",
          trainValues(duty, stop, settings.signalTimeoutSeconds()));
      return true;
    }
    if (!now.isBefore(stop.waitingSince.get().plus(settings.signalTimeout()))) {
      stop.released = true;
      duty.autoSignals++;
      examiner.onEvent(duty.player, StationMasterExaminer.Event.AUTO_SIGNAL);
      messenger.alert(duty.player, "stationmaster.signal.timeout", trainValues(duty, stop, 0));
      return false;
    }
    return true;
  }

  @Override
  public void departureBlocked(String trainName, NodeId node) {
    Stop stop = trainName == null ? null : stops.get(trainKey(trainName));
    if (stop != null && stop.node.equals(node) && !stop.released) {
      stop.waitingSince = Optional.empty();
    }
  }

  @Override
  public void doorsClosed(String trainName, NodeId node, boolean closed) {
    Stop stop = trainName == null ? null : stops.get(trainKey(trainName));
    if (stop != null && stop.node.equals(node)) {
      stop.doorsClosed = closed;
    }
  }

  /**
   * 站长发发车信号：给身旁的当前这一列。车门归乘务员的车到了提前关门的窗口，门还开着也能发（站台作业完毕），按得早乘务员就不用等；歇着时这一按提前结束休息。
   *
   * @param player 站长
   */
  public void signal(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      messenger.send(player, "stationmaster.command.not-on-duty", Map.of());
      return;
    }
    StationMasterConfig settings = config.get();
    List<Stop> nearby = nearbyStops(duty, settings);
    Optional<Stop> waiting =
        nearby.stream().filter(stop -> stop.waitingSince.isPresent()).findFirst();
    // 身旁有真车在等信号时这一按给真车，不拿去答题
    if (waiting.isEmpty() && answer(player, Answer.SIGNAL)) {
      return;
    }
    if (waiting.isPresent()) {
      waiting.get().released = true;
      signalGiven(duty, waiting.get());
      return;
    }
    Instant now = clock.get();
    if (endRest(duty, now)) {
      return;
    }
    Optional<Stop> ready =
        nearby.stream().filter(stop -> signalWindowOpen(stop, settings, now)).findFirst();
    if (ready.isPresent()) {
      ready.get().presignalledAt = Optional.of(now);
      signalGiven(duty, ready.get());
      return;
    }
    if (nearby.isEmpty()) {
      messenger.send(player, "stationmaster.signal.no-train", Map.of());
      return;
    }
    denySignal(duty, nearby.get(0), settings, now);
  }

  private void signalGiven(Duty duty, Stop stop) {
    duty.signals++;
    examiner.onEvent(duty.player, StationMasterExaminer.Event.SIGNAL);
    Map<String, String> values = trainValues(duty, stop, 0);
    messenger.send(duty.player, "stationmaster.signal.given", values);
    for (UUID crew : world.crew(stop.trainName)) {
      messenger.notice(crew, "stationmaster.crew.signal", values);
    }
  }

  private void denySignal(Duty duty, Stop stop, StationMasterConfig settings, Instant now) {
    if (stop.presignalledAt.isPresent()) {
      messenger.send(duty.player, "stationmaster.signal.already", trainValues(duty, stop, 0));
      return;
    }
    if (!clearingDone(stop, settings)) {
      messenger.send(duty.player, "stationmaster.signal.clearing", trainValues(duty, stop, 0));
      return;
    }
    Optional<Instant> opensAt = stop.closeOpensAt.filter(now::isBefore);
    if (stop.crewDoors && opensAt.isPresent()) {
      long seconds = (Duration.between(now, opensAt.get()).toMillis() + 999L) / 1000L;
      messenger.send(
          duty.player, "stationmaster.signal.too-early", trainValues(duty, stop, (int) seconds));
      return;
    }
    messenger.send(duty.player, "stationmaster.signal.not-ready", trainValues(duty, stop, 0));
  }

  /** 车门归乘务员、到了提前关门的窗口、清完客、还没发过信号：门还开着也能发。 */
  private boolean signalWindowOpen(Stop stop, StationMasterConfig settings, Instant now) {
    return stop.crewDoors
        && !stop.released
        && stop.presignalledAt.isEmpty()
        && stop.waitingSince.isEmpty()
        && stop.closeOpensAt.filter(opensAt -> !now.isBefore(opensAt)).isPresent()
        && clearingDone(stop, settings);
  }

  private static boolean clearingDone(Stop stop, StationMasterConfig settings) {
    return !settings.clearing().enabled() || !stop.lastPassengerStop || stop.cleared;
  }

  /**
   * 站长提前结束休息：下一拍就排下一列。
   *
   * @param player 站长
   */
  public void next(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      messenger.send(player, "stationmaster.command.not-on-duty", Map.of());
      return;
    }
    if (!endRest(duty, clock.get())) {
      messenger.send(player, "stationmaster.job.not-resting", Map.of());
    }
  }

  private boolean endRest(Duty duty, Instant now) {
    if (duty.job != null || !now.isBefore(duty.restUntil)) {
      return false;
    }
    duty.restUntil = now;
    messenger.notice(duty.player, "stationmaster.job.rest-ended", Map.of());
    return true;
  }

  // ---- 扣车 ----

  @Override
  public Optional<Instant> holdTarget(String trainName) {
    Hold hold = trainName == null ? null : holds.get(trainKey(trainName));
    if (hold == null || !duties.containsKey(hold.by)) {
      return Optional.empty();
    }
    return Optional.of(hold.since.plus(config.get().maxHold()));
  }

  /**
   * 扣住身旁的当前这一列，到扣车上限自动放行。
   *
   * @param player 站长
   */
  public void hold(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      messenger.send(player, "stationmaster.command.not-on-duty", Map.of());
      return;
    }
    if (answer(player, Answer.HOLD)) {
      return;
    }
    StationMasterConfig settings = config.get();
    Optional<Stop> target =
        nearbyStops(duty, settings).stream()
            .filter(stop -> !holds.containsKey(trainKey(stop.trainName)))
            .findFirst();
    if (target.isEmpty()) {
      messenger.send(player, "stationmaster.hold.no-train", Map.of());
      return;
    }
    Stop stop = target.get();
    holds.put(trainKey(stop.trainName), new Hold(stop.trainName, player, clock.get()));
    duty.holdCount++;
    messenger.send(
        player, "stationmaster.hold.held", trainValues(duty, stop, settings.maxHoldSeconds()));
  }

  /**
   * 放行自己扣着的车：身旁的优先，身旁没有时放行最早扣的那一列。
   *
   * @param player 站长
   */
  public void release(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      messenger.send(player, "stationmaster.command.not-on-duty", Map.of());
      return;
    }
    List<Hold> mine =
        holds.values().stream()
            .filter(hold -> hold.by.equals(player) && !simOn(player, hold.trainName))
            .toList();
    // 手上有别的车扣着时这一按给真车，不拿去答题
    if (mine.isEmpty() && answer(player, Answer.HOLD)) {
      return;
    }
    StationMasterConfig settings = config.get();
    Optional<Hold> target =
        mine.stream()
            .filter(hold -> near(duty, hold.trainName, settings))
            .findFirst()
            .or(() -> mine.stream().min(Comparator.comparing(hold -> hold.since)));
    if (target.isEmpty()) {
      messenger.send(player, "stationmaster.hold.not-held", Map.of());
      return;
    }
    holds.remove(trainKey(target.get().trainName));
    world.reevaluate(target.get().trainName);
    messenger.send(
        player,
        "stationmaster.hold.released",
        Map.of("train", world.trainLabel(target.get().trainName)));
  }

  // ---- 催关 ----

  /**
   * 催身旁的当前这一列关门：车门归乘务员时提醒车上的驾驶员与车掌（窗口未到时只告诉站长还要几秒）；车门归站台时到点自动关，不必催。
   *
   * @param player 站长
   */
  public void urgeClose(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      messenger.send(player, "stationmaster.command.not-on-duty", Map.of());
      return;
    }
    if (answer(player, Answer.CLOSE)) {
      return;
    }
    StationMasterConfig settings = config.get();
    Optional<Stop> target =
        nearbyStops(duty, settings).stream()
            .filter(stop -> stop.waitingSince.isEmpty())
            .findFirst();
    if (target.isEmpty()) {
      messenger.send(player, "stationmaster.close.no-train", Map.of());
      return;
    }
    Stop stop = target.get();
    if (!stop.crewDoors) {
      messenger.send(player, "stationmaster.close.automatic", trainValues(duty, stop, 0));
      return;
    }
    Instant now = clock.get();
    Optional<Instant> opensAt = stop.closeOpensAt.filter(now::isBefore);
    if (opensAt.isPresent()) {
      long seconds = (Duration.between(now, opensAt.get()).toMillis() + 999L) / 1000L;
      messenger.send(
          player, "stationmaster.close.too-early", trainValues(duty, stop, (int) seconds));
      return;
    }
    Map<String, String> values = trainValues(duty, stop, 0);
    for (UUID crew : world.crew(stop.trainName)) {
      messenger.alert(crew, "stationmaster.crew.close-request", values);
    }
    messenger.send(player, "stationmaster.close.urged", values);
  }

  // ---- 清客 ----

  /**
   * 清客：本站之后不再载客的车，车上还有乘客时先车内广播请下车，到时限把仍在车上的送到本站站台。
   *
   * @return 还在清客（发车信号要等清完）时为 true
   */
  private boolean clearing(Duty duty, Stop stop, StationMasterConfig settings, Instant now) {
    if (!settings.clearing().enabled() || !stop.lastPassengerStop || stop.cleared) {
      return false;
    }
    List<UUID> riders = world.riders(stop.trainName);
    if (riders.isEmpty()) {
      stop.cleared = true;
      if (stop.clearingSince.isPresent()) {
        messenger.send(duty.player, "stationmaster.clearing.done", trainValues(duty, stop, 0));
      }
      return false;
    }
    if (stop.clearingSince.isEmpty()) {
      stop.clearingSince = Optional.of(now);
      world.announceClearing(stop.trainName);
      messenger.tip(duty.player, StationMasterMessenger.Tip.CLEARING);
      messenger.alert(
          duty.player,
          "stationmaster.clearing.started",
          withCount(trainValues(duty, stop, settings.clearing().seconds()), riders.size()));
      return true;
    }
    if (now.isBefore(stop.clearingSince.get().plusSeconds(settings.clearing().seconds()))) {
      return true;
    }
    for (UUID rider : riders) {
      world.sendToPlatform(rider, stop.node);
    }
    stop.cleared = true;
    duty.clearedRiders += riders.size();
    messenger.alert(
        duty.player,
        "stationmaster.clearing.sent",
        withCount(trainValues(duty, stop, 0), riders.size()));
    return false;
  }

  private static Map<String, String> withCount(Map<String, String> values, int count) {
    Map<String, String> out = new HashMap<>(values);
    out.put("count", String.valueOf(count));
    return out;
  }

  // ---- 接车与出站监视 ----

  /**
   * 每 5 tick 一次：当前这一列开进股道附近算接车，放行之后算出站，各采一次站长有没有在车身范围内看着车，并在动作栏实时告诉站长。
   *
   * <p>列车停在站台上时不采样。车离开股道附近（或出站监视到时限）即结束，告诉站长合不合格，这一列办完。
   */
  public void sample() {
    StationMasterConfig settings = config.get();
    Instant now = clock.get();
    for (Duty duty : List.copyOf(duties.values())) {
      if (duty.job != null) {
        sampleJob(duty, duty.job, settings, now);
      }
    }
  }

  private void sampleJob(Duty duty, Job job, StationMasterConfig root, Instant now) {
    Optional<StationMasterWorld.TrainNear> found = world.train(job.trainName);
    if (found.isEmpty()) {
      dropJob(duty, job);
      return;
    }
    StationMasterConfig.Watch settings = root.watch();
    StationMasterWorld.TrainNear train = found.get();
    Stop stop = stops.get(trainKey(job.trainName));
    boolean atThisStation = stop != null && duty.track(stop.node).isPresent();
    if (atThisStation && stop.released && train.moving()) {
      startDeparture(duty, job);
    }
    boolean close = isClose(duty, job, train, settings);
    Optional<Watching> watch;
    if (job.departure.isPresent()) {
      Watching departure = job.departure.get();
      boolean expired = departure.deadline.filter(deadline -> !now.isBefore(deadline)).isPresent();
      if (!close || expired) {
        finishWatch(departure, false);
        finishJob(duty, job, root, now);
        return;
      }
      watch = job.departure;
    } else if (atThisStation || !train.moving() || job.arrived) {
      return;
    } else if (job.arrival.isPresent()) {
      if (!close) {
        // 没停站就开过去了
        finishWatch(job.arrival.get(), true);
        finishJob(duty, job, root, now);
        return;
      }
      watch = job.arrival;
    } else if (close) {
      job.arrival =
          Optional.of(new Watching(job.trainName, job.node, duty.player, Optional.empty()));
      messenger.tip(duty.player, StationMasterMessenger.Tip.APPROACHING);
      watch = job.arrival;
    } else {
      return;
    }
    Optional<StationMasterWorld.PlayerSpot> spot = world.player(duty.player);
    boolean present = spot.isPresent() && inPlace(duty, job, train, spot.get(), settings);
    boolean watched =
        present
            && train.cars().stream()
                .anyMatch(car -> spot.get().looksAt(car, settings.viewAngleDegrees()));
    watch.get().sample(present, watched);
    messenger.cue(
        duty.player,
        watched
            ? "stationmaster.cue.watching"
            : present ? "stationmaster.cue.look" : "stationmaster.cue.stand",
        jobValues(duty, job, now));
  }

  /** 这列车任一节车厢在当前股道的车站牌子附近。 */
  private boolean isClose(
      Duty duty, Job job, StationMasterWorld.TrainNear train, StationMasterConfig.Watch settings) {
    return duty.track(job.node)
        .map(StationMasterWorld.Track::sign)
        .filter(
            sign ->
                train.cars().stream()
                    .anyMatch(car -> car.near(sign, settings.approachRangeBlocks())))
        .isPresent();
  }

  /** 站长站到位了：这列车停稳后的车身范围内，或就在它某节车厢旁（弯道站台上车尾已离开车头处的切线，量不出车长的单节车车身范围是 0）；还量不出停车位置时按离车站牌子的距离算。 */
  private boolean inPlace(
      Duty duty,
      Job job,
      StationMasterWorld.TrainNear train,
      StationMasterWorld.PlayerSpot spot,
      StationMasterConfig.Watch settings) {
    if (train.cars().stream().anyMatch(car -> spot.spot().near(car, settings.sideRangeBlocks()))) {
      return true;
    }
    if (job.stand.isPresent()) {
      return job.stand.get().beside(spot.spot(), settings.sideRangeBlocks());
    }
    return duty.track(job.node)
        .filter(track -> spot.spot().near(track.sign(), settings.sideRangeBlocks()))
        .isPresent();
  }

  private void startDeparture(Duty duty, Job job) {
    if (job.departure.isPresent()) {
      return;
    }
    job.departure =
        Optional.of(
            new Watching(
                job.trainName,
                job.node,
                duty.player,
                Optional.of(
                    clock.get().plusSeconds(config.get().watch().departureWatchMaxSeconds()))));
  }

  private static double distanceSquared(StationMasterWorld.Spot a, StationMasterWorld.Spot b) {
    double dx = a.x() - b.x();
    double dz = a.z() - b.z();
    return dx * dx + dz * dz;
  }

  /** 一次监视结束：记进站长的成绩并告诉他；一次也没采到样的不判。 */
  private void finishWatch(Watching watch, boolean arrival) {
    Duty duty = duties.get(watch.owner);
    Optional<Boolean> passed = watch.passed(config.get().watch().watchRatio());
    if (duty == null || passed.isEmpty()) {
      return;
    }
    // 接车时站长一次也没站到那条股道的站台上：记接车不在岗，不另记监视不合格
    boolean absent = arrival && !passed.get() && watch.neverPresent();
    if (arrival) {
      if (passed.get()) {
        duty.arrivalOk++;
      } else if (absent) {
        duty.arrivalAbsent++;
      } else {
        duty.arrivalMissed++;
      }
    } else if (passed.get()) {
      duty.departureOk++;
    } else {
      duty.departureMissed++;
    }
    if (!passed.get()) {
      examiner.onEvent(duty.player, StationMasterExaminer.Event.WATCH_MISSED);
    }
    String kind = arrival ? "arrival" : "departure";
    String verdict = passed.get() ? "-ok" : absent ? "-absent" : "-missed";
    messenger.cue(
        duty.player,
        "stationmaster.watch." + kind + verdict,
        Map.of(
            "train",
            world.trainLabel(watch.trainName),
            "track",
            duty.number(watch.node).map(String::valueOf).orElse(watch.node.value())));
  }

  // ---- 站台紧急停车 ----

  /**
   * 站台紧急停车：身旁有车停着、是自己按紧急停车扣住的，这一按解除；否则对身旁进出所辖股道、还在动的车（离得最近的，不限于当前这一列）紧急停车。自动运行（含
   * ATO）的车按紧急制动减速度刹停、停住后扣着； 人工驾驶的车拨到 EB，由驾驶员停稳后缓解。车上的驾驶员与车掌都收到提醒。
   *
   * @param player 站长
   */
  public void emergency(UUID player) {
    Duty duty = duties.get(player);
    Optional<StationMasterWorld.PlayerSpot> spot = world.player(player);
    if (duty == null || spot.isEmpty()) {
      messenger.send(player, "stationmaster.command.not-on-duty", Map.of());
      return;
    }
    List<StationMasterWorld.TrainNear> nearby =
        world.trainsNear(spot.get().spot(), config.get().trainRangeBlocks());
    Optional<StationMasterWorld.TrainNear> held =
        nearby.stream()
            .filter(train -> !train.moving())
            .filter(
                train ->
                    Optional.ofNullable(emergencies.get(trainKey(train.trainName())))
                        .filter(hold -> hold.by.equals(player))
                        .isPresent())
            .findFirst();
    Optional<StationMasterWorld.TrainNear> target =
        nearby.stream()
            .filter(StationMasterWorld.TrainNear::moving)
            .filter(train -> onOwnTracks(duty, train))
            .filter(train -> !simOn(player, train.trainName()))
            .min(
                Comparator.comparingDouble(
                    train ->
                        train.cars().stream()
                            .mapToDouble(car -> distanceSquared(spot.get().spot(), car))
                            .min()
                            .orElse(Double.MAX_VALUE)));
    // 身旁有别的真车要办时这一按给真车；出题用的那列车只算答题，不真的刹车
    if (held.isEmpty() && target.isEmpty() && answer(player, Answer.EMERGENCY)) {
      return;
    }
    if (held.isPresent()) {
      releaseEmergency(duty, held.get().trainName(), "stationmaster.emergency.released");
      return;
    }
    if (target.isEmpty()) {
      messenger.send(player, "stationmaster.emergency.no-train", Map.of());
      return;
    }
    String trainName = target.get().trainName();
    Map<String, String> values = Map.of("train", world.trainLabel(trainName));
    switch (world.emergencyStop(trainName)) {
      case BRAKING -> {
        emergencies.put(trainKey(trainName), new Hold(trainName, player, clock.get()));
        duty.emergencyCount++;
        messenger.alert(player, "stationmaster.emergency.pulled", values);
        alertCrewOfEmergency(trainName, values);
      }
      case DRIVER -> {
        duty.emergencyCount++;
        messenger.alert(player, "stationmaster.emergency.pulled-driver", values);
        alertCrewOfEmergency(trainName, values);
      }
      case NOT_MOVING -> messenger.send(player, "stationmaster.emergency.no-train", Map.of());
      case UNAVAILABLE -> messenger.send(player, "stationmaster.emergency.unavailable", values);
    }
  }

  private void alertCrewOfEmergency(String trainName, Map<String, String> values) {
    for (UUID crew : world.crew(trainName)) {
      messenger.alert(crew, "stationmaster.crew.emergency", values);
    }
  }

  /** 列车此刻是否被站台紧急停车扣着（停住后调度不替它起步）。 */
  public boolean emergencyHeld(String trainName) {
    Hold hold = trainName == null ? null : emergencies.get(trainKey(trainName));
    return hold != null && duties.containsKey(hold.by);
  }

  private void releaseEmergency(Duty duty, String trainName, String key) {
    emergencies.remove(trainKey(trainName));
    world.refreshSignal(trainName);
    messenger.alert(duty.player, key, Map.of("train", world.trainLabel(trainName)));
  }

  /** 列车正进出这名站长所辖的股道：离它最近的那条股道归他，而且就在那条股道的车站牌子附近。紧急停车不限于当前这一列。 */
  private boolean onOwnTracks(Duty duty, StationMasterWorld.TrainNear train) {
    double range = config.get().watch().approachRangeBlocks();
    return duty.stationTracks.stream()
        .min(
            Comparator.comparingDouble(
                track ->
                    train.cars().stream()
                        .mapToDouble(car -> distanceSquared(track.sign(), car))
                        .min()
                        .orElse(Double.MAX_VALUE)))
        .filter(track -> duty.tracks.contains(track.node()))
        .filter(track -> train.cars().stream().anyMatch(car -> car.near(track.sign(), range)))
        .isPresent();
  }

  // ---- 呼叫信号员、下岗键、扣车键 ----

  /** 站长此刻要呼叫信号员时说的是哪列车：身旁的当前这一列优先，其次身旁的车；都没有时为空。 */
  public Optional<String> callTarget(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      return Optional.empty();
    }
    StationMasterConfig settings = config.get();
    Optional<String> stopped =
        nearbyStops(duty, settings).stream().map(stop -> stop.trainName).findFirst();
    if (stopped.isPresent()) {
      return stopped;
    }
    return world
        .player(player)
        .flatMap(
            spot ->
                world.trainsNear(spot.spot(), settings.trainRangeBlocks()).stream()
                    .map(StationMasterWorld.TrainNear::trainName)
                    .findFirst());
  }

  /** 站长此刻所在的车站名（呼叫对话框标题用）；不在岗时为空。 */
  public Optional<String> stationName(UUID player) {
    return Optional.ofNullable(duties.get(player)).map(duty -> duty.station.name());
  }

  /**
   * 就一列车呼叫它所在（或正接近）的信号楼，结果告诉站长。同一名站长两次呼叫至少隔 {@link #CALL_COOLDOWN}。
   *
   * @param player 站长
   * @param trainName 说的是哪列车
   * @param reason 原因
   */
  public void call(UUID player, String trainName, CallReason reason) {
    if (!duties.containsKey(player)) {
      messenger.send(player, "stationmaster.command.not-on-duty", Map.of());
      return;
    }
    Instant now = clock.get();
    Instant last = lastCall.get(player);
    if (last != null && now.isBefore(last.plus(CALL_COOLDOWN))) {
      messenger.send(player, "stationmaster.call.cooldown", Map.of());
      return;
    }
    lastCall.put(player, now);
    CallResult result = signallerLink.call(player, CallerRole.STATION_MASTER, trainName, reason);
    Map<String, String> values = new HashMap<>(result.values());
    values.put("train", world.trainLabel(trainName));
    messenger.send(player, result.key("stationmaster.call"), values);
  }

  /** 下岗键：按一次等确认，{@link #OFF_CONFIRM} 内再按一次才下岗，免得误按。 */
  public void pressOff(UUID player) {
    if (!duties.containsKey(player)) {
      return;
    }
    Instant now = clock.get();
    Instant until = offPressed.remove(player);
    if (until != null && now.isBefore(until)) {
      endDuty(player, StationMasterShift.EndReason.OFF_DUTY);
      return;
    }
    offPressed.put(player, now.plus(OFF_CONFIRM));
    messenger.cue(player, "stationmaster.duty.off-confirm", Map.of());
  }

  /** 扣车键：身旁有自己扣着的车就放行，否则扣住身旁停站的车。 */
  public void toggleHold(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      messenger.send(player, "stationmaster.command.not-on-duty", Map.of());
      return;
    }
    StationMasterConfig settings = config.get();
    boolean holdingNearby =
        holds.values().stream()
            .anyMatch(
                hold ->
                    hold.by.equals(player)
                        && !simOn(player, hold.trainName)
                        && near(duty, hold.trainName, settings));
    if (holdingNearby) {
      release(player);
    } else {
      hold(player);
    }
  }

  // ---- 快捷栏与侧边栏 ----

  /** 快捷栏按钮此刻的样子；不在岗时为空。 */
  Optional<StationMasterDisplay.View> view(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      return Optional.empty();
    }
    Instant now = clock.get();
    boolean waiting = signalWanted(duty, config.get(), now);
    boolean holding = holds.values().stream().anyMatch(hold -> hold.by.equals(player));
    boolean braking = emergencies.values().stream().anyMatch(hold -> hold.by.equals(player));
    boolean offConfirm =
        Optional.ofNullable(offPressed.get(player)).filter(now::isBefore).isPresent();
    return Optional.of(new StationMasterDisplay.View(waiting, holding, braking, offConfirm));
  }

  /** 这一按此刻就算数：当前这一列在等发车信号，或到了可以提前发的窗口。 */
  private boolean signalWanted(Duty duty, StationMasterConfig settings, Instant now) {
    return jobStop(duty)
        .filter(
            stop ->
                (stop.waitingSince.isPresent() && !stop.released)
                    || signalWindowOpen(stop, settings, now))
        .isPresent();
  }

  /** 侧边栏的内容；不在岗时为空。 */
  Optional<StationMasterDisplay.Snapshot> snapshot(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null) {
      return Optional.empty();
    }
    StationMasterConfig settings = config.get();
    Instant now = clock.get();
    Optional<StationMasterDisplay.TrainRow> current =
        Optional.ofNullable(duty.job).map(job -> jobRow(duty, job, settings, now));
    ScoreRules.Result score = StationMasterScore.evaluate(duty.counts());
    return Optional.of(
        new StationMasterDisplay.Snapshot(
            duty.station.name(),
            StationMasterDisplay.compactTracks(
                numbers(duty, duty.tracks), messenger.text("stationmaster.track-separator")),
            current,
            jobStop(duty).map(stop -> stop.doorsClosed),
            Optional.ofNullable(duty.job).map(job -> world.crewOn(job.trainName)),
            current.isEmpty()
                ? Math.max(0L, Duration.between(now, duty.restUntil).getSeconds())
                : 0L,
            settings.job().restSeconds(),
            duty.signals,
            duty.autoSignals,
            duty.arrivalMissed + duty.arrivalAbsent + duty.departureMissed,
            score.points(),
            score.grade().name()));
  }

  /** 当前这一列此刻到了哪一步。 */
  private StationMasterDisplay.TrainRow jobRow(
      Duty duty, Job job, StationMasterConfig settings, Instant now) {
    String train = world.trainLabel(job.trainName);
    String track = duty.number(job.node).map(String::valueOf).orElse(job.node.value());
    if (job.departure.isPresent()) {
      return new StationMasterDisplay.TrainRow(
          train, track, StationMasterDisplay.State.DEPARTING, -1);
    }
    Optional<Stop> stop = jobStop(duty);
    if (stop.isPresent()) {
      return stopRow(duty, stop.get(), settings, now);
    }
    if (emergencies.containsKey(trainKey(job.trainName))) {
      return new StationMasterDisplay.TrainRow(
          train, track, StationMasterDisplay.State.EMERGENCY, -1);
    }
    if (job.arrival.isPresent()) {
      return new StationMasterDisplay.TrainRow(
          train, track, StationMasterDisplay.State.APPROACHING, -1);
    }
    return new StationMasterDisplay.TrainRow(
        train,
        track,
        StationMasterDisplay.State.INBOUND,
        Math.max(0L, Duration.between(now, job.eta).getSeconds()));
  }

  private StationMasterDisplay.TrainRow stopRow(
      Duty duty, Stop stop, StationMasterConfig settings, Instant now) {
    String train = world.trainLabel(stop.trainName);
    String track = duty.number(stop.node).map(String::valueOf).orElse(stop.node.value());
    Hold braked = emergencies.get(trainKey(stop.trainName));
    if (braked != null) {
      return new StationMasterDisplay.TrainRow(
          train, track, StationMasterDisplay.State.EMERGENCY, -1);
    }
    Hold hold = holds.get(trainKey(stop.trainName));
    if (hold != null) {
      return new StationMasterDisplay.TrainRow(
          train, track, StationMasterDisplay.State.HELD, left(hold.since, settings.maxHold(), now));
    }
    if (stop.clearingSince.isPresent() && !stop.cleared) {
      return new StationMasterDisplay.TrainRow(
          train,
          track,
          StationMasterDisplay.State.CLEARING,
          left(stop.clearingSince.get(), Duration.ofSeconds(settings.clearing().seconds()), now));
    }
    if (stop.waitingSince.isPresent() && !stop.released) {
      return new StationMasterDisplay.TrainRow(
          train,
          track,
          StationMasterDisplay.State.WAITING,
          left(stop.waitingSince.get(), settings.signalTimeout(), now));
    }
    if (stop.presignalledAt.isPresent() || stop.released) {
      return new StationMasterDisplay.TrainRow(
          train, track, StationMasterDisplay.State.SIGNALLED, -1);
    }
    return new StationMasterDisplay.TrainRow(
        train,
        track,
        signalWindowOpen(stop, settings, now)
            ? StationMasterDisplay.State.READY
            : StationMasterDisplay.State.DWELLING,
        -1);
  }

  private static long left(Instant since, Duration limit, Instant now) {
    return Math.max(0L, Duration.between(now, since.plus(limit)).getSeconds());
  }

  // ---- 模拟事件 ----

  /**
   * 考官要的一道模拟事件：此刻出得了（所辖股道上有进站或停站的车、没有车在等发车信号、没有别的题在等处置）就出。
   *
   * @return 出了题
   */
  public boolean drill(UUID player) {
    Duty duty = duties.get(player);
    if (duty == null || sims.containsKey(player) || !eventReady(duty)) {
      return false;
    }
    Optional<Sim> sim = pickSim(duty, clock.get());
    sim.ifPresent(next -> startSim(duty, next));
    return sim.isPresent();
  }

  /** 值守中的模拟事件：隔一阵（指数分布）以所辖股道上一列车的名义出一道题，只考判断，不改变列车运行——处置时按下的按钮只算答题，不真的扣车或紧急停车。考试与练习中只出考官要的那一道。 */
  private void tickEvents(Duty duty, StationMasterConfig settings, Instant now) {
    Sim sim = sims.get(duty.player);
    if (sim != null) {
      if (world.cars(sim.trainName()).isEmpty()) {
        sims.remove(duty.player);
        examiner.onEvent(duty.player, StationMasterExaminer.Event.SIM_CANCELLED);
      } else if (!now.isBefore(sim.since().plusSeconds(settings.events().answerSeconds()))) {
        sims.remove(duty.player);
        duty.simMissed++;
        examiner.onEvent(duty.player, StationMasterExaminer.Event.SIM_MISSED);
        messenger.alert(
            duty.player,
            "stationmaster.event.missed",
            simValues(duty, sim, settings.events().answerSeconds()));
      }
      return;
    }
    if (!settings.events().enabled() || examiner.examining(duty.player)) {
      return;
    }
    Instant next =
        nextEventAt.computeIfAbsent(
            duty.player, ignored -> duty.since.plus(EVENT_WARMUP).plus(gap(settings, false)));
    if (now.isBefore(next)) {
      return;
    }
    Optional<Sim> picked = eventReady(duty) ? pickSim(duty, now) : Optional.empty();
    if (picked.isEmpty()) {
      nextEventAt.put(duty.player, now.plus(EVENT_RETRY));
      return;
    }
    startSim(duty, picked.get());
    nextEventAt.put(duty.player, now.plus(gap(settings, true)));
  }

  /** 两题之间隔多久：按平均间隔取指数分布，第一题之后不短于最小间隔。 */
  private Duration gap(StationMasterConfig settings, boolean afterFirst) {
    double u = Math.min(random.nextDouble(), 0.999_999);
    long seconds = Math.round(-settings.events().meanIntervalSeconds() * Math.log(1.0 - u));
    return Duration.ofSeconds(
        afterFirst ? Math.max(settings.events().minGapSeconds(), seconds) : seconds);
  }

  /** 此刻能出题：人在车站，当前这一列没有在等发车信号、也没被扣着（免得答题耽误真车）。 */
  private boolean eventReady(Duty duty) {
    return duty.awaySince.isEmpty()
        && jobStop(duty)
            .filter(
                stop ->
                    stop.waitingSince.isPresent() || holds.containsKey(trainKey(stop.trainName)))
            .isEmpty();
  }

  /** 挑一道题：当前这一列进站时出越线，停站（还没关门等信号、没在清客）时出夹人夹物或赶车。 */
  private Optional<Sim> pickSim(Duty duty, Instant now) {
    List<Sim> candidates = new ArrayList<>();
    Job job = duty.job;
    if (job == null) {
      return Optional.empty();
    }
    if (job.arrival.isPresent()) {
      candidates.add(new Sim(SimKind.TRESPASS, job.trainName, job.node, now));
    }
    jobStop(duty)
        .filter(
            stop -> !stop.released && stop.waitingSince.isEmpty() && stop.clearingSince.isEmpty())
        .ifPresent(
            stop ->
                candidates.add(
                    new Sim(
                        random.nextBoolean() ? SimKind.TRAPPED : SimKind.RUNNING,
                        stop.trainName,
                        stop.node,
                        now)));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(candidates.get(random.nextInt(candidates.size())));
  }

  private void startSim(Duty duty, Sim sim) {
    sims.put(duty.player, sim);
    messenger.tip(duty.player, StationMasterMessenger.Tip.EVENT);
    messenger.alert(
        duty.player,
        "stationmaster.event." + sim.kind().key(),
        simValues(duty, sim, config.get().events().answerSeconds()));
    if (examiner.practicing(duty.player)) {
      messenger.send(duty.player, "stationmaster.coach.sim-" + sim.kind().key(), Map.of());
    }
  }

  /**
   * 有模拟事件在等处置时，按下的按钮算答题（不真的发信号、扣车或紧急停车）；调用方先看身旁有没有别的真车要办。
   *
   * @return 这一按用来答题了
   */
  private boolean answer(UUID player, Answer answer) {
    Sim sim = sims.remove(player);
    Duty duty = duties.get(player);
    if (sim == null || duty == null) {
      return false;
    }
    Map<String, String> values = new HashMap<>(simValues(duty, sim, 0));
    values.put("answer", messenger.text("stationmaster.event.answer." + sim.kind().answer.key()));
    if (sim.kind().answer == answer) {
      duty.simRight++;
      examiner.onEvent(player, StationMasterExaminer.Event.SIM_RIGHT);
      messenger.alert(player, "stationmaster.event.right", values);
    } else {
      duty.simWrong++;
      examiner.onEvent(player, StationMasterExaminer.Event.SIM_WRONG);
      messenger.alert(player, "stationmaster.event.wrong", values);
    }
    return true;
  }

  /** 这名站长此刻的模拟事件出在这列车上。 */
  private boolean simOn(UUID player, String trainName) {
    Sim sim = sims.get(player);
    return sim != null && trainKey(sim.trainName()).equals(trainKey(trainName));
  }

  private Map<String, String> simValues(Duty duty, Sim sim, int seconds) {
    return Map.of(
        "train",
        world.trainLabel(sim.trainName()),
        "track",
        duty.number(sim.node()).map(String::valueOf).orElse(sim.node().value()),
        "seconds",
        String.valueOf(seconds));
  }

  // ---- 每秒巡检 ----

  /** 每秒一次：在岗判定、扣车到限、清掉已不在的车、动作栏提示等发车信号的车。 */
  public void tick() {
    StationMasterConfig settings = config.get();
    Instant now = clock.get();
    for (Duty duty : List.copyOf(duties.values())) {
      checkDuty(duty, settings, now);
    }
    tickHolds(now, settings);
    for (Hold hold : List.copyOf(emergencies.values())) {
      Duty duty = duties.get(hold.by);
      if (duty != null && !now.isBefore(hold.since.plus(settings.emergencyHold()))) {
        releaseEmergency(duty, hold.trainName, "stationmaster.emergency.expired");
      }
    }
    offPressed.values().removeIf(until -> !now.isBefore(until));
    stops.values().removeIf(stop -> !world.trainExists(stop.trainName));
    for (Stop stop : List.copyOf(stops.values())) {
      handlerAt(trainKey(stop.trainName), stop.node)
          .ifPresent(duty -> clearing(duty, stop, settings, now));
    }
    refreshInbound(now);
    for (Duty duty : List.copyOf(duties.values())) {
      refreshJob(duty, now);
      assignJob(duty, settings, now);
      coach(duty, settings, now);
      cueWaiting(duty, settings, now);
      tickEvents(duty, settings, now);
    }
  }

  /** 练习中的教练：当前这一列每到一步说一句该做什么，办完说一句可以歇；同一步不重复说。 */
  private void coach(Duty duty, StationMasterConfig settings, Instant now) {
    if (!examiner.practicing(duty.player)) {
      return;
    }
    if (duty.job != null) {
      StationMasterDisplay.TrainRow row = jobRow(duty, duty.job, settings, now);
      coachStep(
          duty,
          row.state().key() + ":" + duty.job.trainName,
          row.state().key(),
          Map.of(
              "train", row.train(),
              "track", row.track(),
              "seconds", String.valueOf(Math.max(0L, row.seconds()))));
    } else if (now.isBefore(duty.restUntil)) {
      coachStep(
          duty,
          "rest",
          "rest",
          Map.of("seconds", String.valueOf(Duration.between(now, duty.restUntil).getSeconds())));
    } else if (duty.coached == null) {
      coachStep(duty, "idle", "idle", Map.of());
    }
  }

  private void coachStep(Duty duty, String step, String key, Map<String, String> values) {
    if (!step.equals(duty.coached)) {
      duty.coached = step;
      messenger.send(duty.player, "stationmaster.coach." + key, values);
    }
  }

  /** 按站牌更新所辖股道的来车：还没在站台登记停站的车记下预计到站时刻，排车时从这里挑。 */
  private void refreshInbound(Instant now) {
    Map<String, Inbound> next = new HashMap<>();
    Set<String> asked = new HashSet<>();
    for (Duty duty : duties.values()) {
      String station =
          (duty.station.operator() + ":" + duty.station.code()).toLowerCase(Locale.ROOT);
      if (!asked.add(station)) {
        continue;
      }
      for (StationMasterWorld.Arrival arrival : world.arrivals(duty.station, INBOUND_HORIZON)) {
        String key = trainKey(arrival.trainName());
        // 在前一站停站的车照样是来车；只有已经停在本站的才不算
        Stop stopped = stops.get(key);
        if ((stopped != null && duty.track(stopped.node).isPresent()) || next.containsKey(key)) {
          continue;
        }
        Optional<NodeId> node =
            duty.stationTracks.stream()
                .filter(track -> track.number() == arrival.track())
                .map(StationMasterWorld.Track::node)
                .findFirst();
        node.flatMap(this::ownerOf)
            .ifPresent(
                owner ->
                    next.put(
                        key,
                        new Inbound(arrival.trainName(), node.get(), owner.player, arrival.eta())));
      }
    }
    inbound.clear();
    inbound.putAll(next);
  }

  /** 跟上站牌的变化：当前这一列改了股道、晚了点都照办；从站牌上消失又没开到股道附近，过一阵就不等了。 */
  private void refreshJob(Duty duty, Instant now) {
    Job job = duty.job;
    if (job == null) {
      return;
    }
    if (world.train(job.trainName).isEmpty()) {
      dropJob(duty, job);
      return;
    }
    if (dwellingTooLong(duty, job, now)) {
      duty.lastTrack = Optional.of(job.node);
      dropJob(duty, job);
      return;
    }
    job.stand = world.standPoint(job.trainName, job.node).or(() -> job.stand);
    if (job.arrived || job.arrival.isPresent() || job.departure.isPresent()) {
      job.lastSeen = now;
      return;
    }
    Inbound next = inbound.get(trainKey(job.trainName));
    if (next != null && duty.track(next.node()).isPresent()) {
      job.node = next.node();
      job.eta = next.eta();
      job.lastSeen = now;
      return;
    }
    if (!now.isBefore(job.lastSeen.plus(JOB_LOST_AFTER))) {
      dropJob(duty, job);
    }
  }

  /** 停站太久还没轮到站长动手；站长扣着的、正在清客的不算，提前发过信号的从发信号起再等一个 {@link #LONG_DWELL}。 */
  private boolean dwellingTooLong(Duty duty, Job job, Instant now) {
    return job.arrived
        && job.departure.isEmpty()
        && !now.isBefore(job.arrivedAt.plus(LONG_DWELL))
        && !holds.containsKey(trainKey(job.trainName))
        && jobStop(duty)
            .filter(
                stop ->
                    stop.waitingSince.isEmpty()
                        && stop.presignalledAt
                            .filter(at -> now.isBefore(at.plus(LONG_DWELL)))
                            .isEmpty()
                        && (stop.clearingSince.isEmpty() || stop.cleared))
            .isPresent();
  }

  /** 歇够了、手上没车时排下一列：来得及走到的车里挑最早的，比它晚一点点的车在另一条股道上就排那一列，几条股道轮着办。 */
  private void assignJob(Duty duty, StationMasterConfig settings, Instant now) {
    if (duty.job != null || duty.awaySince.isPresent() || now.isBefore(duty.restUntil)) {
      return;
    }
    Optional<StationMasterWorld.PlayerSpot> spot = world.player(duty.player);
    if (spot.isEmpty()) {
      return;
    }
    List<Inbound> reachable =
        inbound.values().stream()
            .filter(next -> next.owner().equals(duty.player))
            .filter(next -> !takenByAnyone(trainKey(next.trainName())))
            .filter(next -> reachable(duty, next, spot.get(), settings, now))
            .sorted(Comparator.comparing(Inbound::eta))
            .toList();
    if (reachable.isEmpty()) {
      return;
    }
    Instant window = reachable.get(0).eta().plus(ALTERNATE_WINDOW);
    Inbound chosen =
        reachable.stream()
            .filter(next -> !next.eta().isAfter(window))
            .filter(next -> duty.lastTrack.filter(next.node()::equals).isEmpty())
            .findFirst()
            .orElse(reachable.get(0));
    Job job = new Job(chosen.trainName(), chosen.node(), chosen.eta(), now);
    job.stand = world.standPoint(job.trainName, job.node);
    duty.job = job;
    messenger.notice(duty.player, "stationmaster.job.assigned", jobValues(duty, job, now));
  }

  /** 这列车已排给某名站长（股道分走了或站牌改了股道时，别人的来车表里也可能有它）。 */
  private boolean takenByAnyone(String key) {
    return duties.values().stream()
        .anyMatch(other -> other.job != null && trainKey(other.job.trainName).equals(key));
  }

  /** 按步速走到那条股道来得及。 */
  private boolean reachable(
      Duty duty,
      Inbound next,
      StationMasterWorld.PlayerSpot spot,
      StationMasterConfig settings,
      Instant now) {
    return duty.track(next.node())
        .map(
            track -> {
              double blocks = Math.sqrt(distanceSquared(spot.spot(), track.sign()));
              long seconds = (long) Math.ceil(blocks / settings.job().walkBlocksPerSecond());
              return !next.eta().isBefore(now.plusSeconds(seconds));
            })
        .orElse(false);
  }

  /** 这一列办完：歇一会儿再排下一列。 */
  private void finishJob(Duty duty, Job job, StationMasterConfig settings, Instant now) {
    duty.job = null;
    duty.lastTrack = Optional.of(job.node);
    duty.restUntil = now.plus(settings.job().rest());
  }

  /** 这一列不用办了（车不在了、改去别的站台）：不记分，也不算办过，不必歇。 */
  private void dropJob(Duty duty, Job job) {
    duty.job = null;
    holds.remove(trainKey(job.trainName));
    messenger.cue(
        duty.player, "stationmaster.job.dropped", Map.of("train", world.trainLabel(job.trainName)));
  }

  private Map<String, String> jobValues(Duty duty, Job job, Instant now) {
    return Map.of(
        "train",
        world.trainLabel(job.trainName),
        "track",
        duty.number(job.node).map(String::valueOf).orElse(job.node.value()),
        "seconds",
        String.valueOf(Math.max(0L, Duration.between(now, job.eta).getSeconds())));
  }

  /** 列车站长此刻该站哪、这列车要不要闪给站长看；手上没车时为空。 */
  Optional<StationMasterDisplay.Marks> marks(UUID player) {
    Duty duty = duties.get(player);
    Job job = duty == null ? null : duty.job;
    if (job == null || job.stand.isEmpty()) {
      return Optional.empty();
    }
    boolean flashing = job.arrival.isPresent() || signalWanted(duty, config.get(), clock.get());
    return Optional.of(new StationMasterDisplay.Marks(job.trainName, job.stand.get(), flashing));
  }

  private void checkDuty(Duty duty, StationMasterConfig settings, Instant now) {
    if (!settings.enabled()) {
      endDuty(duty.player, StationMasterShift.EndReason.SHUTDOWN);
      return;
    }
    Optional<StationMasterWorld.PlayerSpot> spot = world.player(duty.player);
    if (spot.isEmpty()) {
      endDuty(duty.player, StationMasterShift.EndReason.QUIT);
      return;
    }
    if (!spot.get().fit()) {
      endDuty(duty.player, StationMasterShift.EndReason.INCAPACITATED);
      return;
    }
    boolean atStation =
        duty.stationTracks.stream()
            .anyMatch(track -> spot.get().spot().near(track.sign(), settings.stationRangeBlocks()));
    if (atStation) {
      if (duty.awaySince.isPresent()) {
        duty.awaySince = Optional.empty();
        messenger.alert(duty.player, "stationmaster.duty.back", Map.of());
      }
      return;
    }
    if (duty.awaySince.isEmpty()) {
      duty.awaySince = Optional.of(now);
      messenger.alert(
          duty.player,
          "stationmaster.duty.away",
          Map.of("grace", String.valueOf(settings.graceSeconds())));
      return;
    }
    if (!now.isBefore(duty.awaySince.get().plus(settings.grace()))) {
      endDuty(duty.player, StationMasterShift.EndReason.LEFT_STATION);
    }
  }

  private void tickHolds(Instant now, StationMasterConfig settings) {
    for (Hold hold : List.copyOf(holds.values())) {
      if (now.isBefore(hold.since.plus(settings.maxHold()))) {
        continue;
      }
      holds.remove(trainKey(hold.trainName));
      world.reevaluate(hold.trainName);
      Optional.ofNullable(duties.get(hold.by)).ifPresent(duty -> duty.holdsExpired++);
      messenger.alert(
          hold.by, "stationmaster.hold.expired", Map.of("train", world.trainLabel(hold.trainName)));
    }
  }

  /** 动作栏倒数当前这一列等发车信号的剩余秒数。 */
  private void cueWaiting(Duty duty, StationMasterConfig settings, Instant now) {
    jobStop(duty)
        .filter(stop -> !stop.released && stop.waitingSince.isPresent())
        .ifPresent(
            stop -> {
              long left =
                  Math.max(
                      0L,
                      settings.signalTimeoutSeconds()
                          - Duration.between(stop.waitingSince.get(), now).getSeconds());
              messenger.cue(
                  duty.player, "stationmaster.cue.waiting", trainValues(duty, stop, (int) left));
            });
  }

  // ---- 内部 ----

  private Optional<Duty> ownerOf(NodeId node) {
    return duties.values().stream().filter(duty -> duty.tracks.contains(node)).findFirst();
  }

  /** 正在办这列车、这个节点又在他车站上的站长；列车在别的站停站时不算。 */
  private Optional<Duty> handlerAt(String key, NodeId node) {
    return duties.values().stream()
        .filter(duty -> duty.job != null && trainKey(duty.job.trainName).equals(key))
        .filter(duty -> duty.track(node).isPresent())
        .findFirst();
  }

  /** 当前这一列在这个车站的停站；还没到或已开走时为空。 */
  private Optional<Stop> jobStop(Duty duty) {
    if (duty.job == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(stops.get(trainKey(duty.job.trainName)))
        .filter(stop -> duty.track(stop.node).isPresent());
  }

  /** 站长此刻在这列车旁（任一节车厢的水平距离在范围内）。 */
  private boolean near(Duty duty, String trainName, StationMasterConfig settings) {
    Optional<StationMasterWorld.PlayerSpot> spot = world.player(duty.player);
    if (spot.isEmpty()) {
      return false;
    }
    return world.cars(trainName).stream()
        .anyMatch(car -> spot.get().spot().near(car, settings.trainRangeBlocks()));
  }

  /** 身旁的当前这一列（停在站台上、还没放行）。 */
  private List<Stop> nearbyStops(Duty duty, StationMasterConfig settings) {
    return jobStop(duty)
        .filter(stop -> !stop.released && near(duty, stop.trainName, settings))
        .stream()
        .toList();
  }

  private Map<String, String> trainValues(Duty duty, Stop stop, int seconds) {
    return Map.of(
        "train",
        world.trainLabel(stop.trainName),
        "track",
        duty.number(stop.node).map(String::valueOf).orElse(stop.node.value()),
        "seconds",
        String.valueOf(seconds));
  }

  private List<Integer> numbers(Duty duty, Set<NodeId> nodes) {
    return indicesOf(duty, nodes).stream()
        .map(index -> duty.stationTracks.get(index).number())
        .toList();
  }

  private String trackList(Duty duty, Set<NodeId> nodes) {
    return numbers(duty, nodes).stream()
        .map(String::valueOf)
        .collect(Collectors.joining(messenger.text("stationmaster.track-separator")));
  }

  private static String key(DutyStart result) {
    return result.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static String trainKey(String trainName) {
    return trainName.trim().toLowerCase(Locale.ROOT);
  }

  /** 一名站长的值守。 */
  private static final class Duty {
    final UUID player;
    final String name;
    final StationMasterWorld.Station station;

    /** 全站股道，按股道号排。 */
    final List<StationMasterWorld.Track> stationTracks;

    /** 所辖股道。 */
    final Set<NodeId> tracks = new LinkedHashSet<>();

    /** 上岗时刻。 */
    final Instant since;

    /** 从谁手里分出来的；下岗时交还给他。 */
    Optional<UUID> donor = Optional.empty();

    Optional<Instant> awaySince = Optional.empty();

    /** 系统排给他的当前这一列；歇着时为空。 */
    Job job;

    /** 歇到什么时候才排下一列。 */
    Instant restUntil;

    /** 练习中教练上一次说到哪一步；还没说过时为空。 */
    String coached;

    /** 上一列办的是哪条股道：下一列尽量换一条。 */
    Optional<NodeId> lastTrack = Optional.empty();

    int signals;
    int autoSignals;
    int holdCount;
    int arrivalOk;
    int arrivalMissed;
    int arrivalAbsent;
    int departureOk;
    int departureMissed;
    int emergencyCount;
    int clearedRiders;
    int holdsExpired;
    int simRight;
    int simWrong;
    int simMissed;

    Duty(
        UUID player,
        String name,
        StationMasterWorld.Station station,
        List<StationMasterWorld.Track> stationTracks,
        Instant since) {
      this.player = player;
      this.name = name;
      this.station = station;
      this.stationTracks = List.copyOf(stationTracks);
      this.since = since;
      this.restUntil = since;
    }

    /** 这一班的作业计数。 */
    StationMasterShift.Counts counts() {
      return new StationMasterShift.Counts(
          signals,
          autoSignals,
          holdCount,
          holdsExpired,
          arrivalOk,
          arrivalMissed,
          arrivalAbsent,
          departureOk,
          departureMissed,
          emergencyCount,
          clearedRiders,
          simRight,
          simWrong,
          simMissed);
    }

    Optional<Integer> number(NodeId node) {
      return track(node).map(StationMasterWorld.Track::number);
    }

    Optional<StationMasterWorld.Track> track(NodeId node) {
      return stationTracks.stream().filter(track -> track.node().equals(node)).findFirst();
    }
  }

  /** 系统排给站长的当前这一列。 */
  private static final class Job {
    final String trainName;

    /** 停哪条股道；站牌改了股道时跟着改。 */
    NodeId node;

    /** 预计到站时刻。 */
    Instant eta;

    /** 最后一次确认这列车还要来的时刻。 */
    Instant lastSeen;

    /** 站长该站的地方；还量不出时为空。 */
    Optional<StationMasterWorld.StandPoint> stand = Optional.empty();

    /** 接车监视；车还没开到股道附近或已停站时为空。 */
    Optional<Watching> arrival = Optional.empty();

    /** 出站监视；车还没放行时为空。 */
    Optional<Watching> departure = Optional.empty();

    /** 已到站（接车监视已结束）。 */
    boolean arrived;

    /** 到站的时刻；还没到时无意义。 */
    Instant arrivedAt;

    Job(String trainName, NodeId node, Instant eta, Instant now) {
      this.trainName = trainName;
      this.node = node;
      this.eta = eta;
      this.lastSeen = now;
    }
  }

  /** 一次停站。 */
  private static final class Stop {
    final String trainName;
    final NodeId node;

    /** 乘务员最早能关门的时刻（列车没绑车次时就是停站结束）；车门不归乘务员时为空。 */
    Optional<Instant> closeOpensAt = Optional.empty();

    boolean crewDoors;

    /** 开始等发车信号的时刻；还没问过时为空。 */
    Optional<Instant> waitingSince = Optional.empty();

    /** 已放行（站长发了信号、超时代发、或按自动办理）。 */
    boolean released;

    /** 站长在车门关好之前发信号的时刻：等站台来问时直接放行；没提前发时为空。 */
    Optional<Instant> presignalledAt = Optional.empty();

    /** 车门全关着（站台报来）。 */
    boolean doorsClosed;

    /** 本站之后不再载客：发车信号前先清客。 */
    boolean lastPassengerStop;

    /** 开始清客（车内广播请下车）的时刻；还没开始时为空。 */
    Optional<Instant> clearingSince = Optional.empty();

    /** 已清完（车上没人了，或到时限送走了）。 */
    boolean cleared;

    Stop(String trainName, NodeId node) {
      this.trainName = trainName;
      this.node = node;
    }
  }

  /** 一次扣车。 */
  private record Hold(String trainName, UUID by, Instant since) {}

  /** 一列预计要停到所辖股道上的来车。 */
  private record Inbound(String trainName, NodeId node, UUID owner, Instant eta) {}

  /** 一次接车或出站监视：采了几次样、几次在看。 */
  private static final class Watching {
    final String trainName;
    final NodeId node;
    final UUID owner;

    /** 出站监视的时限；接车监视没有（车停住或开走即结束）。 */
    final Optional<Instant> deadline;

    int samples;
    int present;
    int watched;

    Watching(String trainName, NodeId node, UUID owner, Optional<Instant> deadline) {
      this.trainName = trainName;
      this.node = node;
      this.owner = owner;
      this.deadline = deadline;
    }

    void sample(boolean onPlatform, boolean watching) {
      samples++;
      if (onPlatform) {
        present++;
      }
      if (watching) {
        watched++;
      }
    }

    /** 采到的样里站长一次也没站到那条股道的站台上。 */
    boolean neverPresent() {
      return samples > 0 && present == 0;
    }

    /** 合格的采样不少于 {@code ratio}；一次也没采到时为空。 */
    Optional<Boolean> passed(double ratio) {
      return samples == 0
          ? Optional.empty()
          : Optional.of(watched >= Math.ceil(samples * ratio - 1e-9));
    }
  }
}
