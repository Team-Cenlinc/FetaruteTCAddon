package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.drive.crew.CallerRole;
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

  /** 选中的车不再跟这座楼有关（出了区、不在列车表里）的起始时刻；还在时为空。 */
  Instant selectedMissingSince;

  /** 上一次点控制台的时刻：按住右键时客户端会连发，紧跟着上一下的点击不算（见 {@link SignallerService#HELD_REPEAT}）。 */
  Instant lastClick;

  boolean replying;

  /** 操作区换成了选中车的列车操作（第二页）。 */
  boolean trainOps;

  /** 按过一次“取消车次”、等着确认的那一下；没有时为空。 */
  CancelArm cancelArm;

  Notice notice;

  /** 已给的许可：列车键 → 许可。 */
  final Map<String, Permit> permits = new LinkedHashMap<>();

  /** 在入口等许可的车：列车键 → 等待。 */
  final Map<String, Waiting> waiting = new LinkedHashMap<>();

  /** 扣在站里的车：列车键 → 扣车开始。 */
  final Map<String, Hold> holds = new LinkedHashMap<>();

  /** 已确认取消、正在倒计时的车（车上有人，先插播停运再删）：列车键 → 倒计时。 */
  final Map<String, PendingCancel> cancelling = new LinkedHashMap<>();

  /** 待回复的呼叫，先到先回。 */
  final Deque<Call> calls = new ArrayDeque<>();

  /** 设了自动通过的进路。 */
  final Set<Route> autoPasses = new HashSet<>();

  /** 信号员改过停站股道的列车键（下岗时撤销；转为自动不撤：扣车与改停股道只经出站门控，与模式无关）。 */
  final Set<String> platformChoices = new HashSet<>();

  /** 交汇排序：信号员排进来的车与另一个入口同时在等、区内要走的路有交汇的车，两车都出区后按合计多出的晚点计分。 */
  final List<Pair> pairs = new ArrayList<>();

  /**
   * 接近锁闭过的入口（不分方向的入口区间 →
   * 解锁时刻）：车已在接近时取消了它的进路，到时之前这个入口不再排进路。解锁后还记一阵，用来认出代排时限是不是在锁闭期间到的（那样的车锁一解除就放，不算代排）。
   */
  final Map<EdgeId, Instant> approachLocks = new HashMap<>();

  /** 已提示过正在接近的列车键：一列车接近一次只响一声，进了区、离开接近就移出。 */
  final Set<String> approaching = new HashSet<>();

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
    selectedMissingSince = null;
    lastClick = null;
    replying = false;
    approachLocks.clear();
    pairs.clear();
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
    TIMEOUT,
    /** 代排时限在接近锁闭期间就到了：锁一解除由系统放行，不算代排（等待照样有上限，信号员也不因取消进路受罚）。 */
    RELEASED
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

    /** 锁闭（驶入）时这列车的晚点（秒）；没有时刻表时为空。出区时与那时的晚点比，计正点放行或在区内丢了时间。 */
    OptionalLong entryDelay = OptionalLong.empty();

    /** 锁闭时晚点读数的版本：出区时版本没变（其间没停过站）就不比。 */
    Optional<String> entryStamp = Optional.empty();

    /** 在区内被令越站：越站省下的时间会让正点放行、交汇排序虚高，出区时这两项都不评。 */
    boolean skipOrdered;

    /** 在区内被扣车扣了多少秒：扣车是信号员有意的调整，比晚点时减掉。 */
    long heldSeconds;

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

    /** 等满代排时限却好一阵没来问闸时，巡检最近一次唤醒它的时刻。 */
    Instant wokenAt;

    /** 等到代排时限一半时已提醒过信号员。 */
    boolean halfAlerted;

    Waiting(String trainName, Instant since, Optional<Route> route) {
      this.trainName = trainName;
      this.since = since;
      this.lastSeen = since;
      this.route = route == null ? Optional.empty() : route;
    }
  }

  /**
   * 一对交汇的列车：先放的与在另一个入口等着的。两车各自出区时记下在区内多出的晚点（秒），都有了就结算。
   *
   * @param first 信号员先放进来的车（键）
   * @param second 另一个入口等着的车（键）
   * @param since 记下的时刻（太久没结算就作废）
   */
  static final class Pair {
    String first;
    String second;
    final Instant since;
    Long firstGrowth;
    Long secondGrowth;

    Pair(String first, String second, Instant since) {
      this.first = first;
      this.second = second;
      this.since = since;
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
   * 已确认取消、正在倒计时的车。
   *
   * @param trainName 列车名
   * @param due 到这个时刻删车
   */
  record PendingCancel(String trainName, Instant due) {}

  /**
   * 乘务员（驾驶员或车掌）的呼叫。
   *
   * @param trainName 列车名
   * @param caller 呼叫的乘务员；模拟呼叫时为空
   * @param callerName 呼叫者名字（模拟呼叫为空串）
   * @param role 驾驶员或车掌（模拟呼叫按驾驶员）
   * @param since 呼叫时刻
   * @param reason 原因
   * @param drill 是模拟呼叫（操作许可证的练习与考试）：回复与漏接不发给任何乘务员
   */
  record Call(
      String trainName,
      UUID caller,
      String callerName,
      CallerRole role,
      Instant since,
      CallReason reason,
      boolean drill) {
    Call {
      callerName = callerName == null ? "" : callerName;
      role = role == null ? CallerRole.DRIVER : role;
      reason = reason == null ? CallReason.GENERAL : reason;
    }

    /** 列车改名后的这条呼叫。 */
    Call renamed(String trainName) {
      return new Call(trainName, caller, callerName, role, since, reason, drill);
    }

    /** 换上新写明的原因（排队位置与呼叫时刻不变）。 */
    Call withReason(CallReason reason) {
      return new Call(trainName, caller, callerName, role, since, reason, drill);
    }
  }

  /**
   * 临时提示。
   *
   * @param kind 种类
   * @param train 涉及的列车名
   * @param until 到这个时刻消失
   */
  record Notice(SignalboxState.NoticeKind kind, Optional<String> train, Instant until) {}

  /**
   * 按过一次“取消车次”：谁按的、对哪列车、什么时候。
   *
   * @param player 按的人
   * @param train 列车键
   * @param at 按下的时刻
   */
  record CancelArm(UUID player, String train, Instant at) {
    /** 此刻还等着确认（没过 {@link SignallerService#CANCEL_CONFIRM}）。 */
    boolean live(Instant now) {
      return now.isBefore(at.plus(SignallerService.CANCEL_CONFIRM));
    }
  }

  /** 值守：谁、在哪个控制台、从什么时候起，以及这一班的计数。 */
  static final class Duty {
    final UUID player;
    final String playerName;
    final Instant start;
    ConsoleSpot console;

    /** 离开控制台范围的起始时刻；在范围内时为空。 */
    Instant awaySince;

    /** 最近一次操作以来由系统代排的车次：连续几列都没人管就转为自动。 */
    int autoRoutedIdle;

    /** 上岗时已在接近的车已记下（不为它们响）：之后新接近的才提示。 */
    boolean approachSeeded;

    /** 拿着许可驶入控制区的车次（按锁闭计，同一列车往返两次算两次）。 */
    int trainsHandled;

    int routesSet;
    int autoRouted;
    int autoPassed;
    int preRouted;
    int holds;
    int callsAnswered;
    int callsMissed;

    /** 回复与呼叫原因不符（也计在回复了的呼叫里）。 */
    int callsWrong;

    /** 亲手排进来、出区时没丢时间的车。 */
    int keptTime;

    /** 拿着许可驶入、在区内丢了时间的车。 */
    int lostTime;

    /** 交汇排序得当、不当的次数。 */
    int conflictsGood;

    int conflictsBad;

    /** 取消的车次。 */
    int tripsCancelled;

    /** 令越站的次数。 */
    int skipsOrdered;

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
