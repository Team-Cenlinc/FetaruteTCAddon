package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerGate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerPlatformDesk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.drive.crew.CrewDuty;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerLink;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxConsole;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 车站信号员：控制区（信号楼）的值守、进路许可闸、扣车、改停股道与驾驶员呼叫。
 *
 * <ul>
 *   <li><b>闸</b>（{@link SignallerGate}）：控制区为人工模式时，要进入控制区的车先要有许可；没有许可的停在入口外等，不进控制区资源的排队。
 *       已在区内（持有控制区资源）的车一律放行。等满 {@code auto-route-seconds} 由系统代排；设了自动通过的进路来车即放。
 *   <li><b>许可</b>：排列中 → 车拿到控制区资源后锁闭（不能取消）→ 车不再占用控制区后作废。只在内存，重启与重载一律回到自动。
 *   <li><b>值守</b>：有权限的玩家右键控制台、确认后上岗，控制区转为人工；离控制台超过范围、过了宽限期，或下线、死亡、换游戏模式即下岗，
 *       控制区回到自动（“退回自动”而不是“全部停住”：内核本身是安全的）。
 *   <li><b>控制台</b>（{@link SignalboxConsole}）：站台屏按控制区取一帧数据，点击交回来。
 *   <li><b>呼叫</b>（{@link SignallerLink}）：驾驶员呼叫所在控制区的信号员，信号员在控制台上选一条固定回复。
 * </ul>
 *
 * <p>只在服务器主线程调用。
 */
public final class SignallerService implements SignallerGate, SignalboxConsole, SignallerLink {

  /** 当信号员的权限节点。 */
  public static final String PERMISSION = "fetarute.signal.signaller";

  /** 被闸拦下后多久不再来问闸，就当它已经不在入口等了。要比内核重评一列停着的车的间隔长得多：在这之内重新来问，沿用原来的开始时刻，代排计时不清零。 */
  static final Duration WAITING_STALE = Duration.ofSeconds(60);

  /** 排列中的许可留多久：车改道不来了、车库出车一直没出时作废；要盖过车库出车的重试间隔。 */
  static final Duration PENDING_PERMIT_TTL = Duration.ofMinutes(5);

  /** 巡检代排只给最近这么久内还来问过闸的车：更久没来问的多半被内核自己拦着。 */
  static final Duration AUTO_ROUTE_FRESH = Duration.ofSeconds(6);

  /** 点了控制台之后多久内确认上岗有效。 */
  static final Duration REQUEST_TTL = Duration.ofSeconds(60);

  /** 同一玩家两次上岗提示（或“已有人值守”）的最短间隔。 */
  static final Duration PROMPT_INTERVAL = Duration.ofSeconds(3);

  /** 同一列车两次呼叫的最短间隔。 */
  static final Duration CALL_COOLDOWN = Duration.ofSeconds(5);

  private final SignallerWorld world;
  private final Supplier<SignallerConfig> config;
  private final Supplier<Instant> clock;
  private final LongSupplier ticks;
  private final SignallerMessenger messenger;
  private final Consumer<String> debug;
  private final RailGraphPathFinder finder = new RailGraphPathFinder();

  private final Map<String, SignalBox> boxes = new LinkedHashMap<>();
  private final Map<UUID, DutyRequest> requests = new HashMap<>();
  private final Map<UUID, Instant> prompted = new HashMap<>();

  private volatile SignallerShift.Settlement settlement = SignallerShift.Settlement.NONE;
  private volatile Function<UUID, Optional<CrewDuty.Role>> crewBusy = player -> Optional.empty();

  private int manualBoxes;
  private long holdingsTick = Long.MIN_VALUE;
  private Map<String, Map<String, Presence>> holdings = Map.of();
  private final Map<String, CachedState> states = new HashMap<>();

  SignallerService(
      SignallerWorld world,
      Supplier<SignallerConfig> config,
      Supplier<Instant> clock,
      LongSupplier ticks,
      SignallerMessenger messenger,
      Consumer<String> debug) {
    this.world = Objects.requireNonNull(world, "world");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ticks = Objects.requireNonNull(ticks, "ticks");
    this.messenger = Objects.requireNonNull(messenger, "messenger");
    this.debug = debug == null ? message -> {} : debug;
  }

  /** 接上班次结算（驾驶模块）；{@code null} 表示不结算。 */
  public void setSettlement(SignallerShift.Settlement next) {
    this.settlement = next == null ? SignallerShift.Settlement.NONE : next;
  }

  /** 接上在岗登记的查询：玩家此刻是否在担任信号员以外的角色。 */
  public void setCrewBusy(Function<UUID, Optional<CrewDuty.Role>> next) {
    this.crewBusy = next == null ? player -> Optional.empty() : next;
  }

  // ─── 闸 ────────────────────────────────────────────────────────────────────

  @Override
  public Optional<Hold> admit(
      String trainName, Collection<OccupancyResource> requested, Instant now) {
    if (manualBoxes <= 0 || requested == null || requested.isEmpty()) {
      return Optional.empty();
    }
    String key = key(trainName);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    Instant at = now == null ? clock.get() : now;
    for (SignalBox box : boxes.values()) {
      if (!box.manual()) {
        continue;
      }
      ZoneMap map = map(box);
      if (map == null || !map.touches(requested) || box.permits.containsKey(key)) {
        continue;
      }
      if (presence(box, key).isPresent()) {
        // 已在区内：任何时候都不拦，免得停在道岔中间堵死咽喉
        continue;
      }
      SignalBox.Waiting waiting = box.waiting.get(key);
      if (waiting == null || stale(waiting, at)) {
        Optional<SignalBox.Route> route = routeOf(box, map, trainName, requested);
        if (route.isPresent() && box.autoPasses.contains(route.get())) {
          // 正在这列车自己的信号周期里：放行即可，不再另行唤醒
          record(box, trainName, SignalBox.Source.AUTO_PASS, at, Optional.empty(), route);
          debug.accept("SIGNALLER_AUTO_PASS box=" + box.label() + " train=" + trainName);
          continue;
        }
        waiting = new SignalBox.Waiting(trainName, at, route);
        box.waiting.put(key, waiting);
        invalidate(box);
        debug.accept("SIGNALLER_WAITING box=" + box.label() + " train=" + trainName);
      }
      waiting.lastSeen = at;
      Duration autoRoute = config.get().autoRoute();
      if (!autoRoute.isZero() && !at.isBefore(waiting.since.plus(autoRoute))) {
        record(
            box,
            trainName,
            SignalBox.Source.TIMEOUT,
            at,
            Optional.of(waiting.since),
            waiting.route);
        debug.accept("SIGNALLER_AUTO_ROUTED box=" + box.label() + " train=" + trainName);
        continue;
      }
      return Optional.of(new Hold(box.label(), waiting.since));
    }
    return Optional.empty();
  }

  @Override
  public boolean active() {
    return manualBoxes > 0;
  }

  @Override
  public boolean waiting(String trainName) {
    if (manualBoxes <= 0) {
      return false;
    }
    String key = key(trainName);
    Instant now = clock.get();
    for (SignalBox box : boxes.values()) {
      SignalBox.Waiting waiting = box.manual() ? box.waiting.get(key) : null;
      if (waiting != null && !stale(waiting, now) && !box.permits.containsKey(key)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean renamed(String from, String to) {
    String oldKey = key(from);
    String newKey = key(to);
    if (oldKey.isEmpty() || newKey.isEmpty() || oldKey.equals(newKey)) {
      return false;
    }
    boolean moved = false;
    // 调度传来的可能是规范化后的小写键：显示名尽量取进度表里的原名
    String display = to;
    for (RouteProgressRegistry.RouteProgressEntry entry : world.progress().values()) {
      if (newKey.equals(key(entry.trainName()))) {
        display = entry.trainName();
        break;
      }
    }
    for (SignalBox box : boxes.values()) {
      SignalBox.Permit permit = box.permits.remove(oldKey);
      if (permit != null) {
        permit.trainName = display;
        box.permits.put(newKey, permit);
        moved = true;
      }
      SignalBox.Waiting waiting = box.waiting.remove(oldKey);
      if (waiting != null) {
        waiting.trainName = display;
        box.waiting.put(newKey, waiting);
        moved = true;
      }
      SignalBox.Hold hold = box.holds.remove(oldKey);
      if (hold != null) {
        box.holds.put(newKey, new SignalBox.Hold(display, hold.since()));
        moved = true;
      }
      if (box.platformChoices.remove(oldKey)) {
        box.platformChoices.add(newKey);
      }
      if (oldKey.equals(box.selected)) {
        box.selected = newKey;
      }
      List<SignalBox.Call> calls = new ArrayList<>(box.calls);
      box.calls.clear();
      for (SignalBox.Call call : calls) {
        box.calls.add(
            oldKey.equals(key(call.trainName()))
                ? new SignalBox.Call(display, call.driver(), call.driverName(), call.since())
                : call);
      }
      invalidate(box);
    }
    return moved;
  }

  @Override
  public void forget(String trainName) {
    String key = key(trainName);
    if (key.isEmpty()) {
      return;
    }
    for (SignalBox box : boxes.values()) {
      boolean changed = box.permits.remove(key) != null;
      changed |= box.waiting.remove(key) != null;
      changed |= box.holds.remove(key) != null;
      box.platformChoices.remove(key);
      changed |= box.calls.removeIf(call -> key.equals(key(call.trainName())));
      if (key.equals(box.selected)) {
        box.selected = null;
        changed = true;
      }
      if (changed) {
        invalidate(box);
      }
    }
  }

  /**
   * 信号员扣车扣到几点（给出站门控）：扣着时为扣车开始加上限，放行后为空。
   *
   * @param trainName 列车名
   */
  public Optional<Instant> holdTarget(String trainName) {
    String key = key(trainName);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    Duration max = Duration.ofSeconds(config.get().maxHoldSeconds());
    for (SignalBox box : boxes.values()) {
      SignalBox.Hold hold = box.duty == null ? null : box.holds.get(key);
      if (hold != null) {
        return Optional.of(hold.since().plus(max));
      }
    }
    return Optional.empty();
  }

  /**
   * 资源属不属于这片控制区（被拦时调度撤掉这列车在区内资源上的旧排队项）。控制区资源还没推出来时一律不算。
   *
   * @param box 控制区（{@code 运营商:控制区代码}，即 {@link Hold#box()}）
   * @param resource 占用资源
   */
  public boolean zoneContains(String box, OccupancyResource resource) {
    SignalBox found = box == null ? null : boxes.get(box.trim().toUpperCase(Locale.ROOT));
    ZoneMap map = found == null ? null : found.map;
    return map != null && map.contains(resource);
  }

  /** 这个玩家此刻是否在值守某个控制区（在岗登记用）。 */
  public boolean onDuty(UUID player) {
    return dutyOf(player).isPresent();
  }

  // ─── 值守 ──────────────────────────────────────────────────────────────────

  /** 上岗的结果。 */
  public enum DutyStart {
    STARTED,
    DISABLED,
    NO_PERMISSION,
    NO_REQUEST,
    ZONE_NOT_FOUND,
    ALREADY_ON_DUTY,
    ZONE_TAKEN,
    BUSY_DRIVER,
    BUSY_GUARD
  }

  /**
   * 确认上岗：用这个玩家最近一次点的控制台（{@value #REQUEST_TTL_SECONDS} 秒内有效）。结果已告诉玩家。
   *
   * @param player 玩家
   * @param playerName 玩家名
   * @param permitted 是否有 {@link #PERMISSION}
   */
  public DutyStart startDuty(UUID player, String playerName, boolean permitted) {
    Instant now = clock.get();
    if (!config.get().enabled()) {
      return tell(player, DutyStart.DISABLED, Map.of());
    }
    if (!permitted) {
      return tell(player, DutyStart.NO_PERMISSION, Map.of());
    }
    Optional<SignalBox> current = dutyOf(player);
    if (current.isPresent()) {
      return tell(player, DutyStart.ALREADY_ON_DUTY, Map.of("zone", zoneName(current.get())));
    }
    DutyRequest request = requests.get(player);
    if (request == null || now.isAfter(request.at().plus(REQUEST_TTL))) {
      requests.remove(player);
      return tell(player, DutyStart.NO_REQUEST, Map.of());
    }
    SignalBox box = boxes.get(request.box());
    if (box == null || world.zone(box.operator, box.zone).isEmpty()) {
      requests.remove(player);
      return tell(player, DutyStart.ZONE_NOT_FOUND, Map.of());
    }
    if (box.duty != null) {
      return tell(
          player,
          DutyStart.ZONE_TAKEN,
          Map.of("zone", zoneName(box), "signaller", box.duty.playerName));
    }
    Optional<CrewDuty.Role> busy = Optional.empty();
    try {
      busy = crewBusy.apply(player);
    } catch (RuntimeException ex) {
      debug.accept("SIGNALLER_CREW_CHECK_FAILED player=" + player + " error=" + ex);
    }
    if (busy.filter(role -> role == CrewDuty.Role.DRIVER).isPresent()) {
      return tell(player, DutyStart.BUSY_DRIVER, Map.of());
    }
    if (busy.filter(role -> role == CrewDuty.Role.GUARD).isPresent()) {
      return tell(player, DutyStart.BUSY_GUARD, Map.of());
    }
    requests.remove(player);
    box.duty = new SignalBox.Duty(player, playerName, now, request.console());
    box.resetToAuto();
    ZoneMap map = map(box);
    if (map != null) {
      setMode(box, SignalboxState.Mode.MANUAL);
    }
    invalidate(box);
    debug.accept("SIGNALLER_ON_DUTY box=" + box.label() + " player=" + playerName);
    messenger.send(
        player,
        map != null ? "signaller.duty.started" : "signaller.duty.started-auto",
        Map.of(
            "zone",
            zoneName(box),
            "range",
            String.valueOf(config.get().consoleRangeBlocks()),
            "grace",
            String.valueOf(config.get().graceSeconds())));
    return DutyStart.STARTED;
  }

  /** 每种上岗结果对应的提示键。 */
  private DutyStart tell(UUID player, DutyStart result, Map<String, String> values) {
    messenger.send(
        player,
        "signaller.duty.start." + result.name().toLowerCase(Locale.ROOT).replace('_', '-'),
        values);
    return result;
  }

  /**
   * 下岗：结算这一班，控制区回到自动。
   *
   * @param player 玩家
   * @param reason 原因
   * @return 这个玩家原本在值守时为 true
   */
  public boolean endDuty(UUID player, SignallerShift.EndReason reason) {
    Optional<SignalBox> box = dutyOf(player);
    box.ifPresent(found -> endDuty(found, reason));
    return box.isPresent();
  }

  /** 插件关闭或重载：所有值守结束。 */
  public void shutdown() {
    for (SignalBox box : List.copyOf(boxes.values())) {
      if (box.duty != null) {
        endDuty(box, SignallerShift.EndReason.SHUTDOWN);
      }
    }
    requests.clear();
  }

  private void endDuty(SignalBox box, SignallerShift.EndReason reason) {
    SignalBox.Duty duty = box.duty;
    if (duty == null) {
      return;
    }
    Instant now = clock.get();
    List<String> wake = new ArrayList<>();
    for (SignalBox.Waiting waiting : box.waiting.values()) {
      wake.add(waiting.trainName);
    }
    for (SignalBox.Hold hold : box.holds.values()) {
      wake.add(hold.trainName());
    }
    SignallerPlatformDesk desk = world.platforms();
    for (String train : box.platformChoices) {
      try {
        desk.forget(train);
      } catch (RuntimeException ex) {
        debug.accept("SIGNALLER_PLATFORM_FORGET_FAILED train=" + train + " error=" + ex);
      }
    }
    SignallerShift shift =
        new SignallerShift(
            duty.player,
            duty.playerName,
            box.operator,
            box.zone,
            duty.start,
            now,
            duty.trainsHandled,
            duty.routesSet,
            duty.autoRouted,
            duty.autoPassed,
            duty.preRouted,
            duty.holds,
            duty.callsAnswered,
            duty.callsMissed,
            reason);
    List<SignalBox.Call> unanswered = new ArrayList<>(box.calls);
    box.duty = null;
    box.holds.clear();
    box.calls.clear();
    box.notice = null;
    setMode(box, SignalboxState.Mode.AUTO);
    box.resetToAuto();
    invalidate(box);
    debug.accept(
        "SIGNALLER_OFF_DUTY box="
            + box.label()
            + " player="
            + duty.playerName
            + " reason="
            + reason
            + " trains="
            + shift.trainsHandled());
    // 先结算：发消息出错不能让这一班漏结
    try {
      settlement.settle(shift);
    } catch (RuntimeException ex) {
      debug.accept("SIGNALLER_SETTLE_FAILED box=" + box.label() + " error=" + ex);
    }
    String zoneName = zoneName(box);
    try {
      messenger.send(
          duty.player,
          "signaller.duty.ended." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-'),
          Map.of("zone", zoneName));
      // 还没回复的呼叫：告诉驾驶员信号楼没回复（下岗不算漏接）
      for (SignalBox.Call call : unanswered) {
        messenger.send(call.driver(), "signaller.call.unanswered", Map.of("zone", zoneName));
      }
    } catch (RuntimeException ex) {
      debug.accept("SIGNALLER_OFF_DUTY_NOTIFY_FAILED box=" + box.label() + " error=" + ex);
    }
    wake.forEach(this::reevaluate);
  }

  /** 玩家下线。 */
  public void playerQuit(UUID player) {
    requests.remove(player);
    prompted.remove(player);
    endDuty(player, SignallerShift.EndReason.QUIT);
  }

  /** 玩家死亡或换了游戏模式。 */
  public void playerIncapacitated(UUID player) {
    endDuty(player, SignallerShift.EndReason.INCAPACITATED);
  }

  /** 控制区被删除或改了代码：值守的人下岗，状态清掉。 */
  public void zoneRemoved(String operator, String zone) {
    SignalBox box = boxes.get(boxKey(operator, zone));
    if (box == null) {
      return;
    }
    if (box.duty != null) {
      endDuty(box, SignallerShift.EndReason.REVOKED);
    }
    boxes.remove(boxKey(operator, zone));
  }

  /**
   * 一座信号楼的概况（{@code /fta signal status}）。
   *
   * @param operator 运营商代码
   * @param zone 控制区代码
   * @param name 控制区名
   * @param mode 模式
   * @param signaller 值守者
   * @param waiting 在入口等许可的车数
   * @param permits 已给的许可数
   * @param holds 扣着的车数
   */
  public record BoxStatus(
      String operator,
      String zone,
      String name,
      SignalboxState.Mode mode,
      Optional<String> signaller,
      int waiting,
      int permits,
      int holds) {}

  /** 各信号楼的概况：有控制台在显示、被点过或有人值守过的楼。 */
  public List<BoxStatus> status() {
    Instant now = clock.get();
    List<BoxStatus> out = new ArrayList<>();
    for (SignalBox box : boxes.values()) {
      long waiting = box.waiting.values().stream().filter(w -> !stale(w, now)).count();
      out.add(
          new BoxStatus(
              box.operator,
              box.zone,
              zoneName(box),
              box.manual() ? SignalboxState.Mode.MANUAL : SignalboxState.Mode.AUTO,
              Optional.ofNullable(box.duty).map(duty -> duty.playerName),
              (int) waiting,
              box.permits.size(),
              box.holds.size()));
    }
    return out;
  }

  // ─── 每秒巡检 ──────────────────────────────────────────────────────────────

  /** 每秒一次：值守范围、许可锁闭与作废、代排、扣车上限、呼叫超时、临时提示。 */
  public void tick() {
    Instant now = clock.get();
    SignallerConfig settings = config.get();
    requests.values().removeIf(request -> now.isAfter(request.at().plus(REQUEST_TTL)));
    prompted.values().removeIf(at -> now.isAfter(at.plus(PROMPT_INTERVAL)));
    for (SignalBox box : List.copyOf(boxes.values())) {
      if (world.zone(box.operator, box.zone).isEmpty()) {
        // 控制区没了（删控制区、删运营商或公司、改运营商代码）：值守的人下岗，楼撤掉
        zoneRemoved(box.operator, box.zone);
        continue;
      }
      if (box.duty != null && !checkDuty(box, settings, now)) {
        continue;
      }
      boolean changed = false;
      if (box.notice != null && !now.isBefore(box.notice.until())) {
        box.notice = null;
        changed = true;
      }
      changed |= tickPermits(box, now);
      changed |= tickWaiting(box, settings, now);
      changed |= tickHolds(box, settings, now);
      changed |= tickCalls(box, settings, now);
      if (changed) {
        invalidate(box);
      }
    }
  }

  /** 值守还成立吗；不成立时下岗并返回 false。 */
  private boolean checkDuty(SignalBox box, SignallerConfig settings, Instant now) {
    SignalBox.Duty duty = box.duty;
    if (!settings.enabled()) {
      endDuty(box, SignallerShift.EndReason.SHUTDOWN);
      return false;
    }
    Optional<SignallerWorld.PlayerSpot> spot = world.player(duty.player);
    if (spot.isEmpty()) {
      endDuty(box, SignallerShift.EndReason.QUIT);
      return false;
    }
    if (!spot.get().fit()) {
      endDuty(box, SignallerShift.EndReason.INCAPACITATED);
      return false;
    }
    double range = settings.consoleRangeBlocks();
    boolean near =
        spot.get().world().equals(duty.console.world())
            && duty.console.distanceSquared(spot.get()) <= range * range;
    if (near) {
      if (duty.awaySince != null) {
        duty.awaySince = null;
        messenger.alert(duty.player, "signaller.duty.back", Map.of("zone", zoneName(box)));
      }
      return true;
    }
    if (duty.awaySince == null) {
      duty.awaySince = now;
      messenger.alert(
          duty.player,
          "signaller.duty.away",
          Map.of("zone", zoneName(box), "grace", String.valueOf(settings.graceSeconds())));
      return true;
    }
    if (!now.isBefore(duty.awaySince.plusSeconds(settings.graceSeconds()))) {
      endDuty(box, SignallerShift.EndReason.LEFT_CONSOLE);
      return false;
    }
    return true;
  }

  /** 许可：车拿到控制区资源即锁闭，按锁闭计经手与来源（同一列车往返两次算两次）；锁闭后车不再占用控制区即作废；给出后迟迟没有进区的（车改道、车库出车一直没出）到时作废。 */
  private boolean tickPermits(SignalBox box, Instant now) {
    if (box.permits.isEmpty()) {
      return false;
    }
    boolean changed = false;
    Iterator<Map.Entry<String, SignalBox.Permit>> it = box.permits.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, SignalBox.Permit> entry = it.next();
      SignalBox.Permit permit = entry.getValue();
      boolean holding = presence(box, entry.getKey()).filter(Presence::moving).isPresent();
      if (permit.state == SignalBox.PermitState.PENDING) {
        if (holding) {
          permit.state = SignalBox.PermitState.LOCKED;
          countEntry(box.duty, permit);
          changed = true;
        } else if (now.isAfter(permit.granted.plus(PENDING_PERMIT_TTL))) {
          it.remove();
          changed = true;
        }
      } else if (!holding) {
        it.remove();
        changed = true;
      }
    }
    return changed;
  }

  /** 一列车拿着许可驶入：计经手，并按许可来源计代排、自动通过与预排命中。 */
  private static void countEntry(SignalBox.Duty duty, SignalBox.Permit permit) {
    if (duty == null) {
      return;
    }
    duty.trainsHandled++;
    switch (permit.source) {
      case TIMEOUT -> duty.autoRouted++;
      case AUTO_PASS -> duty.autoPassed++;
      case SIGNALLER -> {
        if (permit.waitingSince.isEmpty()) {
          duty.preRouted++;
        }
      }
    }
  }

  /** 在入口等的车：不再来问闸的清掉；等满了代排（闸本身也会代排，这里兜住没人再问闸的情形）。 */
  private boolean tickWaiting(SignalBox box, SignallerConfig settings, Instant now) {
    if (box.waiting.isEmpty()) {
      return false;
    }
    boolean changed = false;
    Iterator<Map.Entry<String, SignalBox.Waiting>> it = box.waiting.entrySet().iterator();
    List<SignalBox.Waiting> due = new ArrayList<>();
    while (it.hasNext()) {
      Map.Entry<String, SignalBox.Waiting> entry = it.next();
      SignalBox.Waiting waiting = entry.getValue();
      if (stale(waiting, now) || box.permits.containsKey(entry.getKey()) || !box.manual()) {
        it.remove();
        changed = true;
        continue;
      }
      if (settings.autoRouteSeconds() > 0
          && !now.isBefore(waiting.since.plus(settings.autoRoute()))) {
        due.add(waiting);
      }
    }
    for (SignalBox.Waiting waiting : due) {
      if (now.isAfter(waiting.lastSeen.plus(AUTO_ROUTE_FRESH))) {
        // 好一阵没来问闸：多半是内核自己在拦（前方有车），不代排、不记到信号员头上，只唤醒它来问一次，闸当场判
        reevaluate(waiting.trainName);
        continue;
      }
      grant(
          box,
          waiting.trainName,
          SignalBox.Source.TIMEOUT,
          now,
          Optional.of(waiting.since),
          waiting.route);
      changed = true;
    }
    return changed;
  }

  /** 扣车：到上限自动放行；车已经不在停站了（被别的原因放走）也清掉。 */
  private boolean tickHolds(SignalBox box, SignallerConfig settings, Instant now) {
    if (box.holds.isEmpty()) {
      return false;
    }
    boolean changed = false;
    Iterator<Map.Entry<String, SignalBox.Hold>> it = box.holds.entrySet().iterator();
    while (it.hasNext()) {
      SignalBox.Hold hold = it.next().getValue();
      boolean expired = !now.isBefore(hold.since().plusSeconds(settings.maxHoldSeconds()));
      if (expired || !world.dwelling(hold.trainName())) {
        it.remove();
        changed = true;
        if (expired) {
          notice(box, SignalboxState.NoticeKind.HOLD_EXPIRED, Optional.of(hold.trainName()));
          reevaluate(hold.trainName());
        }
      }
    }
    return changed;
  }

  /** 呼叫：超时没回复算漏接，告诉驾驶员。 */
  private boolean tickCalls(SignalBox box, SignallerConfig settings, Instant now) {
    boolean changed = false;
    while (!box.calls.isEmpty()
        && !now.isBefore(
            box.calls.peekFirst().since().plusSeconds(settings.callTimeoutSeconds()))) {
      SignalBox.Call call = box.calls.pollFirst();
      if (box.duty != null) {
        box.duty.callsMissed++;
      }
      messenger.send(call.driver(), "signaller.call.unanswered", Map.of("zone", zoneName(box)));
      changed = true;
    }
    if (box.calls.isEmpty() && box.replying) {
      box.replying = false;
      changed = true;
    }
    return changed;
  }

  // ─── 呼叫 ──────────────────────────────────────────────────────────────────

  @Override
  public boolean call(UUID driver, String trainName) {
    String key = key(trainName);
    if (driver == null || key.isEmpty()) {
      return false;
    }
    Instant now = clock.get();
    for (SignalBox box : boxes.values()) {
      if (box.duty == null || !concerns(box, key)) {
        continue;
      }
      for (SignalBox.Call call : box.calls) {
        if (key.equals(key(call.trainName()))) {
          // 同一列车已经在等回复：只提醒一次，不重复排队
          if (now.isAfter(call.since().plus(CALL_COOLDOWN))) {
            alertCall(box, call.trainName());
          }
          return true;
        }
      }
      String driverName = world.player(driver).map(SignallerWorld.PlayerSpot::name).orElse("");
      box.calls.addLast(new SignalBox.Call(trainName, driver, driverName, now));
      alertCall(box, trainName);
      invalidate(box);
      debug.accept("SIGNALLER_CALL box=" + box.label() + " train=" + trainName);
      return true;
    }
    return false;
  }

  private void alertCall(SignalBox box, String trainName) {
    messenger.alert(
        box.duty.player,
        "signaller.call.incoming",
        Map.of("train", world.info(trainName).label(), "zone", zoneName(box)));
  }

  /** 这列车跟这座信号楼有关：在区内、正接近、在入口等，或有许可、被扣着。 */
  private boolean concerns(SignalBox box, String key) {
    if (box.waiting.containsKey(key)
        || box.permits.containsKey(key)
        || box.holds.containsKey(key)) {
      return true;
    }
    ZoneMap map = map(box);
    if (map == null) {
      return false;
    }
    if (presence(box, key).isPresent()) {
      return true;
    }
    for (RouteProgressRegistry.RouteProgressEntry entry : world.progress().values()) {
      if (key.equals(key(entry.trainName()))) {
        return entry.lastPassedGraphNode().filter(map::inside).isPresent()
            || entry.nextTarget().filter(map::inside).isPresent();
      }
    }
    return false;
  }

  // ─── 控制台 ────────────────────────────────────────────────────────────────

  @Override
  public Optional<SignalboxState> state(String operator, String zone) {
    Optional<SignallerWorld.Zone> found = world.zone(operator, zone);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    SignalBox box = box(found.get());
    long tick = ticks.getAsLong();
    CachedState cached = states.get(boxKey(box.operator, box.zone));
    if (cached != null && cached.tick() == tick) {
      return Optional.of(cached.state());
    }
    SignalboxState state = new SignalboxStates(this, box, map(box), clock.get()).build();
    states.put(boxKey(box.operator, box.zone), new CachedState(tick, state));
    return Optional.of(state);
  }

  @Override
  public void press(
      Player player, Location console, String operator, String zone, ConsoleTarget target) {
    if (player == null || target == null) {
      return;
    }
    SignalBox.ConsoleSpot spot =
        console == null || console.getWorld() == null
            ? null
            : new SignalBox.ConsoleSpot(
                console.getWorld().getUID(), console.getX(), console.getY(), console.getZ());
    press(
        player.getUniqueId(),
        player.getName(),
        player.hasPermission(PERMISSION),
        spot,
        operator,
        zone,
        target);
  }

  /** 控制台点击（不依赖 Bukkit 对象，测试直接调）。 */
  void press(
      UUID player,
      String playerName,
      boolean permitted,
      SignalBox.ConsoleSpot console,
      String operator,
      String zone,
      ConsoleTarget target) {
    Optional<SignallerWorld.Zone> found = world.zone(operator, zone);
    if (found.isEmpty()) {
      return;
    }
    SignalBox box = box(found.get());
    if (box.duty == null || !box.duty.player.equals(player)) {
      // 没在值守：点哪里都是请求上岗（空白处与左键也一样）
      requestDuty(box, player, permitted, console);
      return;
    }
    if (console != null) {
      box.duty.console = console;
    }
    // 按此刻的画面复核：选中的车已掉出列表、按钮已灰显、呼叫已超时出队时，旧画面上的点击不生效
    refreshHoldings();
    ZoneMap map = map(box);
    Instant now = clock.get();
    SignalboxState shown = new SignalboxStates(this, box, map, now).build();
    new SignalboxActions(this, box, map, now, shown).apply(target);
    invalidate(box);
  }

  /** 没在值守的玩家点了控制台：已有人值守就告知；否则请他确认上岗。提示每人每 {@link #PROMPT_INTERVAL} 最多一条（一次点击会触发几下，旁观者也会乱点）。 */
  private void requestDuty(
      SignalBox box, UUID player, boolean permitted, SignalBox.ConsoleSpot console) {
    Instant now = clock.get();
    boolean quiet = prompted.containsKey(player);
    if (box.duty != null) {
      if (!quiet) {
        messenger.send(
            player,
            "signaller.duty.occupied",
            Map.of("zone", zoneName(box), "signaller", box.duty.playerName));
      }
    } else if (!config.get().enabled()) {
      if (!quiet) {
        messenger.send(player, "signaller.duty.start.disabled", Map.of());
      }
    } else if (!permitted) {
      // 没有权限的玩家点控制台只是在看：不提示
      return;
    } else if (dutyOf(player).isPresent() || console == null) {
      return;
    } else {
      requests.put(player, new DutyRequest(boxKey(box.operator, box.zone), console, now));
      if (!quiet) {
        messenger.send(player, "signaller.duty.prompt", Map.of("zone", zoneName(box)));
      }
    }
    prompted.putIfAbsent(player, now);
  }

  // ─── 给控制台动作与画面用的内部方法 ─────────────────────────────────────────

  SignallerWorld world() {
    return world;
  }

  SignallerConfig settings() {
    return config.get();
  }

  SignallerMessenger messenger() {
    return messenger;
  }

  RailGraphPathFinder finder() {
    return finder;
  }

  /** 发许可：记账、清掉等待、立刻重评这列车。 */
  void grant(
      SignalBox box,
      String trainName,
      SignalBox.Source source,
      Instant now,
      Optional<Instant> waitingSince,
      Optional<SignalBox.Route> route) {
    if (record(box, trainName, source, now, waitingSince, route)) {
      reevaluate(trainName);
    }
  }

  /** 记下许可（不唤醒）；已有许可时什么也不做并返回 false。 */
  private boolean record(
      SignalBox box,
      String trainName,
      SignalBox.Source source,
      Instant now,
      Optional<Instant> waitingSince,
      Optional<SignalBox.Route> route) {
    String key = key(trainName);
    if (key.isEmpty() || box.permits.containsKey(key)) {
      return false;
    }
    box.permits.put(key, new SignalBox.Permit(trainName, source, now, waitingSince, route));
    box.waiting.remove(key);
    // 代排与自动通过按车实际驶入（锁闭）时计，见 countEntry；这里只计信号员亲手排的
    if (box.duty != null && source == SignalBox.Source.SIGNALLER) {
      box.duty.routesSet++;
    }
    invalidate(box);
    return true;
  }

  /** 切换模式；记着有几座楼是人工模式，闸据此走快路径。 */
  void setMode(SignalBox box, SignalboxState.Mode mode) {
    boolean wasManual = box.mode == SignalboxState.Mode.MANUAL;
    box.mode = mode;
    boolean isManual = mode == SignalboxState.Mode.MANUAL;
    if (wasManual != isManual) {
      manualBoxes += isManual ? 1 : -1;
      manualBoxes = Math.max(0, manualBoxes);
    }
  }

  void notice(SignalBox box, SignalboxState.NoticeKind kind, Optional<String> train) {
    box.notice =
        new SignalBox.Notice(kind, train, clock.get().plusSeconds(config.get().noticeSeconds()));
    invalidate(box);
  }

  void reevaluate(String trainName) {
    try {
      world.reevaluate(trainName);
    } catch (RuntimeException ex) {
      debug.accept("SIGNALLER_REEVALUATE_FAILED train=" + trainName + " error=" + ex);
    }
  }

  /** 这列车此刻在控制区里占着什么：有区内的占用（任何实际占用角色）时有值。 */
  Optional<Presence> presence(SignalBox box, String key) {
    long tick = ticks.getAsLong();
    if (tick != holdingsTick) {
      holdings = indexHoldings();
      holdingsTick = tick;
    }
    Map<String, Presence> trains = holdings.get(boxKey(box.operator, box.zone));
    return trains == null ? Optional.empty() : Optional.ofNullable(trains.get(key));
  }

  /** 丢掉本拍的占用索引（控制台动作前重取，免得用旧的判断能不能取消）。 */
  void refreshHoldings() {
    holdingsTick = Long.MIN_VALUE;
  }

  /**
   * 各信号楼里各列车的占用：一拍只扫一遍全部占用。
   *
   * <p>“在区内”只认行车占用（进路锁闭、车身所在）与区内区间上的停车保持。不认保护占用（刚出区的车尾防护还压着区内资源），也不认道岔冲突上的停车保持：
   * 停在出入口外端的车，停车保持会带上区内那一头道岔的冲突资源，算进来它就能不经许可直接进区。
   */
  private Map<String, Map<String, Presence>> indexHoldings() {
    List<SignalBox> mapped = new ArrayList<>();
    for (SignalBox box : boxes.values()) {
      if (box.map != null) {
        mapped.add(box);
      }
    }
    if (mapped.isEmpty()) {
      return Map.of();
    }
    Map<String, Map<String, Presence>> out = new HashMap<>();
    for (OccupancyClaim claim : world.claims()) {
      ClaimRole role = claim.role();
      boolean moving = role == ClaimRole.MOVEMENT_REQUIRED || role == ClaimRole.PHYSICAL_FOOTPRINT;
      boolean heldOnTrack =
          role == ClaimRole.HOLD_ONLY && claim.resource().kind() == ResourceKind.EDGE;
      if (!moving && !heldOnTrack) {
        continue;
      }
      for (SignalBox box : mapped) {
        if (!box.map.contains(claim.resource())) {
          continue;
        }
        out.computeIfAbsent(boxKey(box.operator, box.zone), ignored -> new HashMap<>())
            .merge(key(claim.trainName()), new Presence(moving), Presence::merge);
      }
    }
    return out;
  }

  /**
   * 控制区资源：图快照换了或成员改了就重推；推不出来时为空（闸对这片放行）。第一次推时在各世界的图里找成员。
   *
   * @param box 信号楼
   */
  ZoneMap map(SignalBox box) {
    Optional<SignallerWorld.Zone> zone = world.zone(box.operator, box.zone);
    if (zone.isEmpty()) {
      box.map = null;
      return null;
    }
    List<YardArea.Member> members = zone.get().members();
    if (box.worldId != null) {
      RailGraph graph = world.graph(box.worldId).orElse(null);
      if (graph == null) {
        box.map = null;
        return null;
      }
      if (box.map != null && box.map.graph() == graph && members.equals(box.mapMembers)) {
        return box.map;
      }
      box.map =
          YardArea.of(graph, box.operator, members).map(a -> ZoneMap.of(graph, a)).orElse(null);
      box.mapMembers = members;
      holdingsTick = Long.MIN_VALUE;
      return box.map;
    }
    for (Map.Entry<UUID, RailGraph> entry : world.graphs().entrySet()) {
      Optional<YardArea> area = YardArea.of(entry.getValue(), box.operator, members);
      if (area.isPresent()) {
        box.worldId = entry.getKey();
        box.map = ZoneMap.of(entry.getValue(), area.get());
        box.mapMembers = members;
        holdingsTick = Long.MIN_VALUE;
        return box.map;
      }
    }
    return null;
  }

  /**
   * 这次申请走的是哪条进路：入口按申请的资源认，股道按交路展开认。
   *
   * @param requested 按路径顺序的申请资源
   */
  private Optional<SignalBox.Route> routeOf(
      SignalBox box, ZoneMap map, String trainName, Collection<OccupancyResource> requested) {
    Optional<RouteAhead> ahead = ahead(map, trainName);
    Optional<EdgeId> entry = map.entryOf(requested).or(() -> ahead.flatMap(RouteAhead::entry));
    return entry.map(edge -> new SignalBox.Route(edge, ahead.flatMap(RouteAhead::target)));
  }

  /** 列车前方在控制区里要走的路；没有进度或交路时为空。 */
  Optional<RouteAhead> ahead(ZoneMap map, String trainName) {
    String key = key(trainName);
    for (RouteProgressRegistry.RouteProgressEntry entry : world.progress().values()) {
      if (!key.equals(key(entry.trainName()))) {
        continue;
      }
      Optional<RouteDefinition> route =
          entry.routeUuid() == null ? Optional.empty() : world.route(entry.routeUuid());
      if (route.isEmpty()) {
        return Optional.empty();
      }
      Optional<RouteAhead.DynamicStop> dynamic = Optional.empty();
      try {
        dynamic =
            world
                .platforms()
                .options(entry.trainName())
                .flatMap(
                    options ->
                        options
                            .current()
                            .map(node -> new RouteAhead.DynamicStop(options.stopIndex(), node)));
      } catch (RuntimeException ex) {
        debug.accept("SIGNALLER_PLATFORM_OPTIONS_FAILED train=" + trainName + " error=" + ex);
      }
      return Optional.of(RouteAhead.of(map, entry, route.get(), dynamic, finder));
    }
    return Optional.empty();
  }

  Optional<SignalBox> dutyOf(UUID player) {
    if (player == null) {
      return Optional.empty();
    }
    for (SignalBox box : boxes.values()) {
      if (box.duty != null && box.duty.player.equals(player)) {
        return Optional.of(box);
      }
    }
    return Optional.empty();
  }

  String zoneName(SignalBox box) {
    return world.zone(box.operator, box.zone).map(SignallerWorld.Zone::name).orElse(box.zone);
  }

  void invalidate(SignalBox box) {
    states.remove(boxKey(box.operator, box.zone));
  }

  boolean stale(SignalBox.Waiting waiting, Instant now) {
    return now.isAfter(waiting.lastSeen.plus(WAITING_STALE));
  }

  private SignalBox box(SignallerWorld.Zone zone) {
    return boxes.computeIfAbsent(
        boxKey(zone.operator(), zone.code()),
        ignored ->
            new SignalBox(
                zone.operator().toUpperCase(Locale.ROOT), zone.code().toUpperCase(Locale.ROOT)));
  }

  static String key(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return "";
    }
    return TrainNameNormalizer.normalizeKey(trainName);
  }

  static String boxKey(String operator, String zone) {
    return (operator == null ? "" : operator.trim().toUpperCase(Locale.ROOT))
        + ":"
        + (zone == null ? "" : zone.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * 一列车在控制区里的占用。
   *
   * @param moving 有行车占用（进路锁闭或车身所在）；只有保护占用时为 false
   */
  record Presence(boolean moving) {
    static Presence merge(Presence a, Presence b) {
      return new Presence(a.moving() || b.moving());
    }
  }

  /**
   * 请求上岗：点了哪座楼的哪块控制台。
   *
   * @param box 信号楼键
   * @param console 控制台位置
   * @param at 点的时刻
   */
  private record DutyRequest(String box, SignalBox.ConsoleSpot console, Instant at) {}

  private record CachedState(long tick, SignalboxState state) {}

  static final long REQUEST_TTL_SECONDS = 60;

  /** 当前时刻（给控制台动作）。 */
  Instant now() {
    return clock.get();
  }

  /** 给测试：拿到信号楼。 */
  Optional<SignalBox> boxFor(String operator, String zone) {
    return Optional.ofNullable(boxes.get(boxKey(operator, zone)));
  }

  /** 给控制台动作：设一次选择（列车键）。 */
  void select(SignalBox box, String key) {
    box.selected = key;
  }

  /** 给控制台动作：记下改停股道的列车。 */
  void choosePlatform(SignalBox box, String trainName, NodeId platform) {
    try {
      if (world.platforms().choose(trainName, platform)) {
        box.platformChoices.add(key(trainName));
      } else {
        notice(box, SignalboxState.NoticeKind.NOT_DYNAMIC, Optional.of(trainName));
      }
    } catch (RuntimeException ex) {
      debug.accept("SIGNALLER_PLATFORM_CHOOSE_FAILED train=" + trainName + " error=" + ex);
    }
  }
}
