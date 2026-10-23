package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 一座信号楼（一片控制区）此刻的状态：模式、值守、许可、在入口等着的车、扣着的车、待回复的呼叫与自动通过。只在内存里，重启与重载一律回到自动。
 *
 * <p>列车一律按 {@code TrainNameNormalizer.normalizeKey} 记，显示时用记下的原名。只在主线程读写。
 */
final class SignalBox {

  final String operator;
  final String zone;

  /** 推导控制区用的世界；第一次找到成员时定下。 */
  UUID worldId;

  /** 按图快照缓存的控制区资源；图换了重算。 */
  ZoneMap map;

  SignalboxState.Mode mode = SignalboxState.Mode.AUTO;
  Duty duty;

  /** 选中的列车（键）。 */
  String selected;

  boolean replying;
  Notice notice;

  /** 已给的许可：列车键 → 许可。 */
  final Map<String, Permit> permits = new LinkedHashMap<>();

  /** 在入口等许可的车：列车键 → 等待。 */
  final Map<String, Waiting> waiting = new LinkedHashMap<>();

  /** 扣在站里的车：列车键 → 扣车开始。 */
  final Map<String, Hold> holds = new LinkedHashMap<>();

  /** 待回复的呼叫，先到先回。 */
  final Deque<Call> calls = new ArrayDeque<>();

  /** 设了自动通过的进路。 */
  final Set<Route> autoPasses = new HashSet<>();

  /** 信号员改过停站股道的列车键（下岗时撤销；转为自动不撤：扣车与改停股道只经出站门控，与模式无关）。 */
  final Set<String> platformChoices = new HashSet<>();

  /** 推导控制区用的成员；控制区改了成员就重算。 */
  List<YardArea.Member> mapMembers;

  SignalBox(String operator, String zone) {
    this.operator = Objects.requireNonNull(operator, "operator");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  /** 拦截明细里的控制区名：{@code 运营商:控制区}。 */
  String label() {
    return operator + ":" + zone;
  }

  boolean manual() {
    return mode == SignalboxState.Mode.MANUAL && duty != null;
  }

  /** 回到自动：清掉许可、等待、自动通过与选择（扣车与呼叫由调用方先处理）。 */
  void resetToAuto() {
    mode = SignalboxState.Mode.AUTO;
    permits.clear();
    waiting.clear();
    autoPasses.clear();
    platformChoices.clear();
    selected = null;
    replying = false;
  }

  /**
   * 一条进路：同一入口进、同一股道停，算同一条（自动通过按它认）。
   *
   * @param entry 入口区间
   * @param target 区内第一条要到的股道；直接通过不停时为空
   */
  record Route(EdgeId entry, Optional<NodeId> target) {
    Route {
      Objects.requireNonNull(entry, "entry");
      target = target == null ? Optional.empty() : target;
    }
  }

  /** 许可是怎么来的。 */
  enum Source {
    /** 信号员排列（含预排）。 */
    SIGNALLER,
    /** 自动通过的进路。 */
    AUTO_PASS,
    /** 等许可超时，系统代排。 */
    TIMEOUT
  }

  /** 许可的状态。 */
  enum PermitState {
    /** 已给，车还没拿到控制区资源（可以取消）。 */
    PENDING,
    /** 车已拿到控制区资源：进路锁闭，不能再取消。 */
    LOCKED
  }

  /**
   * 一张许可。
   *
   * @param trainName 列车名（显示用）
   * @param source 来源
   * @param granted 给出的时刻
   * @param waitingSince 给出时车已经在入口等着时，那段等待的开始时刻（不算预排命中；取消许可时按它恢复等待，代排计时不清零）
   * @param route 认出的进路；认不出时为空
   */
  static final class Permit {
    String trainName;
    final Source source;
    final Instant granted;
    final Optional<Instant> waitingSince;
    final Optional<Route> route;
    PermitState state = PermitState.PENDING;

    Permit(
        String trainName,
        Source source,
        Instant granted,
        Optional<Instant> waitingSince,
        Optional<Route> route) {
      this.trainName = trainName;
      this.source = source;
      this.granted = granted;
      this.waitingSince = waitingSince == null ? Optional.empty() : waitingSince;
      this.route = route == null ? Optional.empty() : route;
    }
  }

  /**
   * 在入口等许可的一列车。
   *
   * @param trainName 列车名
   * @param since 开始等的时刻
   * @param lastSeen 最近一次被闸拦下的时刻（不再来问闸就当它走了）
   * @param route 认出的进路
   */
  static final class Waiting {
    String trainName;
    final Instant since;
    Instant lastSeen;
    final Optional<Route> route;

    Waiting(String trainName, Instant since, Optional<Route> route) {
      this.trainName = trainName;
      this.since = since;
      this.lastSeen = since;
      this.route = route == null ? Optional.empty() : route;
    }
  }

  /**
   * 扣车。
   *
   * @param trainName 列车名
   * @param since 开始扣的时刻
   */
  record Hold(String trainName, Instant since) {}

  /**
   * 驾驶员的呼叫。
   *
   * @param trainName 列车名
   * @param driver 驾驶员
   * @param driverName 驾驶员名字
   * @param since 呼叫时刻
   */
  record Call(String trainName, UUID driver, String driverName, Instant since) {}

  /**
   * 临时提示。
   *
   * @param kind 种类
   * @param train 涉及的列车名
   * @param until 到这个时刻消失
   */
  record Notice(SignalboxState.NoticeKind kind, Optional<String> train, Instant until) {}

  /** 值守：谁、在哪个控制台、从什么时候起，以及这一班的计数。 */
  static final class Duty {
    final UUID player;
    final String playerName;
    final Instant start;
    ConsoleSpot console;

    /** 离开控制台范围的起始时刻；在范围内时为空。 */
    Instant awaySince;

    /** 拿着许可驶入控制区的车次（按锁闭计，同一列车往返两次算两次）。 */
    int trainsHandled;

    int routesSet;
    int autoRouted;
    int autoPassed;
    int preRouted;
    int holds;
    int callsAnswered;
    int callsMissed;

    Duty(UUID player, String playerName, Instant start, ConsoleSpot console) {
      this.player = player;
      this.playerName = playerName;
      this.start = start;
      this.console = console;
    }
  }

  /**
   * 控制台的位置（被点的屏幕中心）。
   *
   * @param world 世界
   * @param x x
   * @param y y
   * @param z z
   */
  record ConsoleSpot(UUID world, double x, double y, double z) {
    double distanceSquared(SignallerWorld.PlayerSpot spot) {
      double dx = spot.x() - x;
      double dy = spot.y() - y;
      double dz = spot.z() - z;
      return dx * dx + dy * dy + dz * dz;
    }
  }
}
