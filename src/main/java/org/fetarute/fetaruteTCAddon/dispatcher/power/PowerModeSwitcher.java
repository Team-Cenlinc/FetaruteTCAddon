package org.fetarute.fetaruteTCAddon.dispatcher.power;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RoutePowerModes;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.OnDemandTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.Pantographs;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainPower;

/**
 * 多模车按交路备注 {@code POWER} 换模（见 {@link RoutePowerModes}）：出车、接续时按交路起点换好，到达或经过交路节点时换成那一点起应当用的受电方式。
 *
 * <p>电对电经过即换；涉及内燃只在停车时换，那一站多停 {@link RoutePowerModes#DIESEL_SWITCH_SECONDS}。车上没有交路要的受电方式时照旧跑，
 * 记一行日志。 当前模式记在列车标签 {@code FTA_TRAIN_POWER_ACTIVE}（随车走）。只做表现：受电弓按当前模式升降（重复调用不会重放；附件重新挂载后下一次
 * 经过节点时补上）， 控车与编表不按模式区分。单模车什么也不做。
 *
 * <p>手动驾驶的车先问 {@link DriverControl}：simulation 级涉及内燃的由驾驶员在驾驶台换，本类不动；其余照常换，换完通知驾驶会话。有人驾驶时升降弓归驾驶会话
 * （启动流程与换模），本类不动受电弓。
 */
public final class PowerModeSwitcher {

  /** 手动驾驶的接入点。 */
  public interface DriverControl {

    /** 不接手动驾驶：都由本类换。 */
    DriverControl NONE =
        new DriverControl() {
          @Override
          public boolean driven(String trainName) {
            return false;
          }

          @Override
          public boolean driverSwitches(String trainName, PowerSupply sectionMode) {
            return false;
          }

          @Override
          public void switched(String trainName, PowerSupply from, PowerSupply to) {}
        };

    /** 列车有人手动驾驶：升降弓归驾驶会话，本类不动受电弓。 */
    boolean driven(String trainName);

    /**
     * 有人手动驾驶时，每经过一个交路节点都把这一点起应当用的受电方式交给驾驶会话。
     *
     * @param sectionMode 这一点起应当用、车上也有的受电方式；交路到这里还没写 POWER、或车上没有这种方式时为 {@code null}（会话清掉上一段的要求）
     * @return 由驾驶员自己换模（simulation 级涉及内燃）时为 true，本类不换
     */
    boolean driverSwitches(String trainName, PowerSupply sectionMode);

    /** 本类换了模（驾驶会话据此更新受电方式）。 */
    void switched(String trainName, PowerSupply from, PowerSupply to);
  }

  /** 出车后隔多久再按当前模式补一次升降弓（tick）：刚生成的车附件往往还没挂载。 */
  private static final long DISPATCH_PANTOGRAPH_RETRY_TICKS = 20L;

  private final Supplier<Optional<RouteDefinitionCache>> routes;
  private final Supplier<PowerSupply> defaultPower;
  private final Consumer<String> debugLogger;
  private volatile DriverControl driverControl = DriverControl.NONE;
  private volatile BiConsumer<Long, Runnable> laterRunner = (ticks, task) -> {};

  /** 换模那一站要多停的秒数，按交路与节点下标认：同一节点的重复到达事件不清掉它，车开到别的节点才作废。 */
  private record PendingDwell(RouteId route, int index, int seconds) {}

  /** 列车 → 这一站因换模要多停的秒数：车站停站时 AutoStation 取走。 */
  private final Map<String, PendingDwell> stationDwell = new ConcurrentHashMap<>();

  /** 日志去重表的上限。 */
  private static final int SKIP_MEMORY = 1024;

  /** 列车 → 上一次记下的"没换"原因：同车同原因只记一次日志。 */
  private final Map<String, String> lastSkip = new ConcurrentHashMap<>();

  /**
   * @param routes 交路缓存
   * @param defaultPower 列车没写受电方式时的缺省（{@code drive.yml default-power}）
   * @param debugLogger 调试日志
   */
  public PowerModeSwitcher(
      Supplier<Optional<RouteDefinitionCache>> routes,
      Supplier<PowerSupply> defaultPower,
      Consumer<String> debugLogger) {
    this.routes = Objects.requireNonNull(routes, "routes");
    this.defaultPower = Objects.requireNonNull(defaultPower, "defaultPower");
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /** 接入延时执行（出车后补一次升降弓用）；{@code null} 恢复为不执行。 */
  public void setLaterRunner(BiConsumer<Long, Runnable> runner) {
    this.laterRunner = runner == null ? (ticks, task) -> {} : runner;
  }

  /** 接入手动驾驶；{@code null} 恢复"都由本类换"。 */
  public void setDriverControl(DriverControl control) {
    this.driverControl = control == null ? DriverControl.NONE : control;
  }

  /**
   * 车派上一趟交路（出车或终点接续）：按交路起点应当用的受电方式换好（在站内，内燃也能换），再按当前模式升降弓。
   *
   * @param ticket 派出的票
   * @param trainName 列车名
   */
  public void onDispatched(SpawnTicket ticket, String trainName) {
    if (ticket == null || ticket.service() == null || trainName == null) {
      return;
    }
    Optional<TrainProperties> properties = properties(trainName);
    if (properties.isEmpty() || !TrainPower.multiMode(properties.get())) {
      return;
    }
    Optional<RouteDefinitionCache> cache = routes.get();
    if (cache.isEmpty()) {
      return;
    }
    int start =
        OnDemandTrip.entryOf(ticket.serviceTripId()).map(OnDemandTrip.Entry::index).orElse(0);
    List<RouteStop> stops =
        cache
            .get()
            .findById(ticket.service().routeId())
            .map(definition -> cache.get().listStops(definition.id()))
            .orElse(List.of());
    reconcile(trainName, properties.get(), stops, start, true, "dispatch");
    // 刚生成的车附件多半还没挂载，这一下升弓落空；稍后按当时的模式再补一次（已是这个状态的不会重放）
    laterRunner.accept(
        DISPATCH_PANTOGRAPH_RETRY_TICKS,
        () ->
            properties(trainName)
                .filter(TrainPower::multiMode)
                .filter(current -> !driverControl.driven(trainName))
                .ifPresent(
                    current ->
                        showPantographs(current, TrainPower.of(current, defaultPower.get()))));
  }

  /**
   * 列车到达或经过交路第 {@code index} 个节点。
   *
   * @param route 交路
   * @param dwelling 列车在这里停车（车站、停车的路径点）
   * @return 因换模要多停的秒数（涉及内燃的换模在停车点做时为 {@link RoutePowerModes#DIESEL_SWITCH_SECONDS}，否则 0）
   */
  public int onRouteNode(
      String trainName, TrainProperties properties, RouteId route, int index, boolean dwelling) {
    if (trainName == null || properties == null || route == null) {
      return 0;
    }
    if (!TrainPower.multiMode(properties)) {
      return 0;
    }
    String key = key(trainName);
    PendingDwell pending = stationDwell.get(key);
    if (pending != null && (pending.index() != index || !pending.route().equals(route))) {
      stationDwell.remove(key, pending);
    }
    List<RouteStop> stops = routes.get().map(cache -> cache.listStops(route)).orElse(List.of());
    int extra = reconcile(trainName, properties, stops, index, dwelling, "node");
    if (extra > 0) {
      if (stationDwell.size() > SKIP_MEMORY) {
        // 车被删掉后它在换模站记下的秒数没人取：记多了整表清掉
        stationDwell.clear();
      }
      stationDwell.put(key, new PendingDwell(route, index, extra));
    }
    return extra;
  }

  /**
   * AutoStation 停站：取走这一站因换模要多停的秒数（只取一次）。
   *
   * @return 秒数；没有换模时为 0
   */
  public int takeStationDwell(String trainName) {
    PendingDwell extra = trainName == null ? null : stationDwell.remove(key(trainName));
    return extra == null ? 0 : extra.seconds();
  }

  /** 换到这一点起应当用的受电方式并纠正升弓状态；返回因换模要多停的秒数。 */
  private int reconcile(
      String trainName,
      TrainProperties properties,
      List<RouteStop> stops,
      int index,
      boolean dwelling,
      String reason) {
    PowerSupply fallback = defaultPower.get();
    List<PowerSupply> supplies = TrainPower.supplies(properties, fallback);
    PowerSupply active = TrainPower.of(properties, fallback);
    Optional<PowerSupply> wanted = RoutePowerModes.modeAt(stops, index);
    boolean driven = driverControl.driven(trainName);
    // 有人驾驶时每个节点都把这一段的要求交给会话（没写 POWER、车上没有这种方式时交 null，清掉上一段的要求）
    boolean driverSwitches =
        driven
            && driverControl.driverSwitches(
                trainName, wanted.filter(supplies::contains).orElse(null));
    int extra = 0;
    if (wanted.isPresent() && wanted.get() != active) {
      PowerSupply target = wanted.get();
      if (!supplies.contains(target)) {
        skip(trainName, "unsupported:" + target.key(), "列车没有这种受电方式，照旧用 " + active.key());
      } else if (driverSwitches) {
        skip(trainName, "driver:" + target.key(), "由驾驶员在驾驶台换模");
      } else if (RoutePowerModes.needsStop(active, target) && !dwelling) {
        skip(trainName, "deferred:" + target.key(), "涉及内燃，推迟到下一个停车点");
      } else {
        switchTo(trainName, properties, active, target, index, reason);
        extra = RoutePowerModes.needsStop(active, target) && index > 0 ? stopSeconds() : 0;
        active = target;
      }
    } else if (wanted.isPresent()) {
      lastSkip.remove(key(trainName));
    }
    if (!driven) {
      showPantographs(properties, active);
    }
    return extra;
  }

  private static int stopSeconds() {
    return RoutePowerModes.DIESEL_SWITCH_SECONDS;
  }

  private void switchTo(
      String trainName,
      TrainProperties properties,
      PowerSupply from,
      PowerSupply to,
      int index,
      String reason) {
    TrainPower.setActive(properties, to);
    lastSkip.remove(key(trainName));
    debugLogger.accept(
        "POWER_MODE_SWITCH train="
            + trainName
            + " from="
            + from.key()
            + " to="
            + to.key()
            + " index="
            + index
            + " reason="
            + reason);
    driverControl.switched(trainName, from, to);
  }

  /**
   * 受电弓按当前模式升降：用受电弓的升起对应那一组、降下其余；超级电容车的升降归站台牌子与停站放行，这里不动。
   *
   * <p>附件没挂载（附近没有玩家）时什么也不做，下一次经过节点时补上。
   */
  private static void showPantographs(TrainProperties properties, PowerSupply active) {
    if (active == PowerSupply.SUPERCAP) {
      return;
    }
    MinecartGroup group = holder(properties);
    if (group == null) {
      return;
    }
    Optional<String> wanted = active.animation();
    for (String name : Pantographs.NAMES) {
      if (wanted.filter(name::equalsIgnoreCase).isEmpty()) {
        Pantographs.set(group, name, false);
      }
    }
    wanted.ifPresent(name -> Pantographs.set(group, name, true));
  }

  private void skip(String trainName, String code, String detail) {
    if (lastSkip.size() > SKIP_MEMORY) {
      // 只为日志去重：车名随出车不断更新，记多了整表清掉，最多多记几行
      lastSkip.clear();
    }
    if (code.equals(lastSkip.put(key(trainName), code))) {
      return;
    }
    debugLogger.accept("POWER_MODE_SKIP train=" + trainName + " reason=" + code + " " + detail);
  }

  private static MinecartGroup holder(TrainProperties properties) {
    try {
      return properties.getHolder();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static Optional<TrainProperties> properties(String trainName) {
    try {
      return TrainPropertiesStore.exists(trainName)
          ? Optional.ofNullable(TrainPropertiesStore.get(trainName))
          : Optional.empty();
    } catch (RuntimeException | LinkageError ex) {
      return Optional.empty();
    }
  }

  private static String key(String trainName) {
    return trainName.trim().toLowerCase(Locale.ROOT);
  }
}
