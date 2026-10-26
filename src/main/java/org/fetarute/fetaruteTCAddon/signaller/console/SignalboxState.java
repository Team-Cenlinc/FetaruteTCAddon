package org.fetarute.fetaruteTCAddon.signaller.console;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.drive.crew.CallerRole;

/**
 * 控制台一帧的数据（纯数据、按值比较）：站台屏据此画标题栏的模式牌与值守者、站场图上的进路与信号、列车表、操作区，并推出点击区。
 *
 * <p>只放会画出来的东西：值不变就不重画，所以不放每拍都变、画面上又看不出来的量。文字由站台屏按文案键拼，这里只给语义。
 *
 * @param mode 控制区模式
 * @param signaller 值守的信号员名；没人值守时为空
 * @param selected 选中的列车名
 * @param rows 列车表，已排好序（先是在等的，再是接近的、区内的）；画几行由布局决定
 * @param buttons 各按钮此刻能不能按（不能按的灰显）
 * @param call 待回复的呼叫
 * @param replying 操作区此刻换成了四条固定回复
 * @param notice 临时提示（几秒后消失）
 * @param preview 选中车的计划进路（浅蓝预显示）
 * @param pending 已给许可、内核还没放行的进路（白色虚线）
 * @param signals 控制区入口的信号
 * @param platforms 选中车在区内的 {@code DYNAMIC:} 停站可以改停的股道
 * @param delegated 让给小区（车站控制）的那几片
 * @param trainOps 操作区此刻换成了选中车的列车操作（第二页）；在第一页时为空
 */
public record SignalboxState(
    Mode mode,
    Optional<String> signaller,
    Optional<String> selected,
    List<TrainRow> rows,
    Map<Button, Boolean> buttons,
    Optional<Call> call,
    boolean replying,
    Optional<Notice> notice,
    Set<EdgeId> preview,
    Set<EdgeId> pending,
    List<EntrySignal> signals,
    List<PlatformChoice> platforms,
    List<Delegated> delegated,
    Optional<TrainOps> trainOps) {

  /** 操作区在第一页。 */
  public SignalboxState(
      Mode mode,
      Optional<String> signaller,
      Optional<String> selected,
      List<TrainRow> rows,
      Map<Button, Boolean> buttons,
      Optional<Call> call,
      boolean replying,
      Optional<Notice> notice,
      Set<EdgeId> preview,
      Set<EdgeId> pending,
      List<EntrySignal> signals,
      List<PlatformChoice> platforms,
      List<Delegated> delegated) {
    this(
        mode,
        signaller,
        selected,
        rows,
        buttons,
        call,
        replying,
        notice,
        preview,
        pending,
        signals,
        platforms,
        delegated,
        Optional.empty());
  }

  /** 没有让出去的小区。 */
  public SignalboxState(
      Mode mode,
      Optional<String> signaller,
      Optional<String> selected,
      List<TrainRow> rows,
      Map<Button, Boolean> buttons,
      Optional<Call> call,
      boolean replying,
      Optional<Notice> notice,
      Set<EdgeId> preview,
      Set<EdgeId> pending,
      List<EntrySignal> signals,
      List<PlatformChoice> platforms) {
    this(
        mode, signaller, selected, rows, buttons, call, replying, notice, preview, pending, signals,
        platforms, List.of());
  }

  /**
   * 列车操作（第二页）此刻的样子。
   *
   * @param cancelArmed 已按过一次“取消车次”、等着确认
   * @param skipStation 下一个停车站的站码；没有时为空
   * @param skipOrdered 已令越过这一站（越站键写“撤销越站”）
   */
  public record TrainOps(boolean cancelArmed, Optional<String> skipStation, boolean skipOrdered) {
    public TrainOps {
      skipStation = skipStation == null ? Optional.empty() : skipStation;
    }

    /** 没有可越的站。 */
    public TrainOps(boolean cancelArmed) {
      this(cancelArmed, Optional.empty(), false);
    }
  }

  /**
   * 嵌在这片控制区里、正由别的信号员值守的小区：这一片让给了小区（车站控制），站场图上围出来，这里的车不进本控制台的列车表。
   *
   * @param zone 小区名；中心工位分出去的写所辖车站
   * @param signaller 小区的信号员
   * @param nodes 小区里的节点（站场图据此围出范围）
   * @param seat 中心工位的序号；管理员划好的小区为空
   */
  public record Delegated(String zone, String signaller, Set<NodeId> nodes, OptionalInt seat) {
    public Delegated {
      Objects.requireNonNull(zone, "zone");
      Objects.requireNonNull(signaller, "signaller");
      nodes = Set.copyOf(nodes);
      seat = seat == null ? OptionalInt.empty() : seat;
    }

    /** 管理员划好的小区。 */
    public Delegated(String zone, String signaller, Set<NodeId> nodes) {
      this(zone, signaller, nodes, OptionalInt.empty());
    }
  }

  public SignalboxState {
    Objects.requireNonNull(mode, "mode");
    delegated = delegated == null ? List.of() : List.copyOf(delegated);
    signaller = signaller == null ? Optional.empty() : signaller;
    selected = selected == null ? Optional.empty() : selected;
    rows = rows == null ? List.of() : List.copyOf(rows);
    buttons = buttons == null ? Map.of() : Map.copyOf(buttons);
    call = call == null ? Optional.empty() : call;
    notice = notice == null ? Optional.empty() : notice;
    preview = preview == null ? Set.of() : Set.copyOf(preview);
    pending = pending == null ? Set.of() : Set.copyOf(pending);
    signals = signals == null ? List.of() : List.copyOf(signals);
    platforms = platforms == null ? List.of() : List.copyOf(platforms);
    trainOps = trainOps == null ? Optional.empty() : trainOps;
  }

  /** 这个按钮此刻能不能按；没写的按不能按处理。 */
  public boolean enabled(Button button) {
    return Boolean.TRUE.equals(buttons.get(button));
  }

  /** 控制区模式。 */
  public enum Mode {
    /** 内核照常放行，信号员只观察（没人值守时也是这个）。 */
    AUTO,
    /** 进入控制区要先有信号员给的许可。 */
    MANUAL
  }

  /** 操作区的六个按钮，按 2 列 × 3 行从左到右、从上到下排。 */
  public enum Button {
    /** 给选中的车排列进路（发许可）。 */
    SET_ROUTE,
    /** 取消选中车还没锁闭的进路。 */
    CANCEL_ROUTE,
    /** 扣住选中的、正在停站的车；选中的车被扣着时放行。 */
    HOLD,
    /** 操作区换成选中车的列车操作（第二页）。 */
    TRAIN_OPS,
    /** 选中车这条进路（同一入口、同一股道）以后的车都自动排列；再按取消。 */
    AUTO_PASS,
    /** 整个控制区在人工与自动之间切换。 */
    MODE
  }

  /** 列车操作（第二页）的各行。 */
  public enum TrainAction {
    /** 令选中车越过下一个停车站；已下令时撤销。 */
    SKIP,
    /** 取消选中车的车次（删车）。 */
    CANCEL,
    /** 回到第一页。 */
    BACK
  }

  /** 回复驾驶员呼叫的四条固定回复。 */
  public enum Reply {
    /** 前方有车，请等待。 */
    WAIT,
    /** 进路正在排列。 */
    ROUTE_SETTING,
    /** 请以注意速度运行。 */
    CAUTION,
    /** 已收到。 */
    ACK
  }

  /** 列车表一行的状态。 */
  public enum RowStatus {
    /** 正接近控制区（下一个节点在区内）。 */
    APPROACHING,
    /** 在控制区入口等许可。 */
    WAITING,
    /** 已给许可，内核还没放行（进路排列中）。 */
    ROUTE_PENDING,
    /** 进路已锁闭，车正驶入。 */
    ROUTE_SET,
    /** 在区内运行。 */
    IN_ZONE,
    /** 在区内停站。 */
    DWELLING,
    /** 被信号员扣在站里。 */
    HELD
  }

  /**
   * 列车表的一行。
   *
   * @param trainName 列车名（点击回传用）
   * @param label 显示的车次号；按表运行时为车次号，否则为列车名
   * @param lineColor 线路色
   * @param destination 去向的站码（站台屏按站名显示）
   * @param status 状态
   * @param seconds 处在这个状态多少秒（等许可、扣车时显示；其余为 0）
   * @param delaySeconds 晚点秒数（负为早点）；没有时刻表时为空
   * @param autoProgress 等许可时离超时自动排列还有多远、扣车时离扣车上限还有多远：0 刚开始，1 马上到；其余为 0
   * @param autoPass 这列车要走的进路此刻设了自动通过（选中车的这一项决定自动通过按钮写“设置”还是“取消”）
   * @param autoRouted 这列车的许可是等候超时由系统代排的
   * @param skipping 已令越过的那一站的站码；没有越站令时为空
   */
  public record TrainRow(
      String trainName,
      String label,
      Optional<Integer> lineColor,
      Optional<String> destination,
      RowStatus status,
      long seconds,
      OptionalLong delaySeconds,
      double autoProgress,
      boolean autoPass,
      boolean autoRouted,
      Optional<String> skipping) {
    public TrainRow {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(status, "status");
      lineColor = lineColor == null ? Optional.empty() : lineColor;
      destination = destination == null ? Optional.empty() : destination;
      delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
      skipping = skipping == null ? Optional.empty() : skipping;
    }

    /** 没有越站令。 */
    public TrainRow(
        String trainName,
        String label,
        Optional<Integer> lineColor,
        Optional<String> destination,
        RowStatus status,
        long seconds,
        OptionalLong delaySeconds,
        double autoProgress,
        boolean autoPass,
        boolean autoRouted) {
      this(
          trainName,
          label,
          lineColor,
          destination,
          status,
          seconds,
          delaySeconds,
          autoProgress,
          autoPass,
          autoRouted,
          Optional.empty());
    }
  }

  /**
   * 乘务员的呼叫。
   *
   * @param trainName 呼叫的列车
   * @param caller 呼叫者名（模拟呼叫为空串）
   * @param role 驾驶员或车掌（呼叫牌上写出来）
   * @param reason 原因（呼叫牌上写出来，回复要配得上）
   */
  public record Call(String trainName, String caller, CallerRole role, CallReason reason) {
    public Call {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(caller, "caller");
      Objects.requireNonNull(role, "role");
      reason = reason == null ? CallReason.GENERAL : reason;
    }
  }

  /**
   * 临时提示（标题栏右侧，几秒后消失）。
   *
   * @param kind 种类
   * @param train 涉及的列车（例如挡住进路的车）
   */
  public record Notice(NoticeKind kind, Optional<String> train) {
    public Notice {
      Objects.requireNonNull(kind, "kind");
      train = train == null ? Optional.empty() : train;
    }
  }

  /** 临时提示的种类。带列车的提示，文案里的 {@code <train>} 换成车次。 */
  public enum NoticeKind {
    /** 进路被别的车占用，许可排列中。 */
    ROUTE_BLOCKED,
    /** 进路已锁闭，不能取消。 */
    ROUTE_LOCKED,
    /** 这列车不在站里停着，不能扣。 */
    NOT_DWELLING,
    /** 车上有人、车又不在站台上停着，不能取消车次。 */
    CANCEL_OCCUPIED,
    /** 联挂中的一体车带着两条交路，不能取消车次（先拆开）。 */
    CANCEL_COUPLED,
    /** 没有取消车次的权限。 */
    CANCEL_NO_PERMISSION,
    /** 考试、练习中不能取消车次。 */
    CANCEL_IN_EXAM,
    /** 车还在接近，不归本楼处置，不能取消车次。 */
    CANCEL_OUT_OF_ZONE,
    /** 已确认取消、正在停运倒计时：不能放行，也不再算一次取消。 */
    CANCEL_PENDING,
    /** 已取消车次。 */
    TRIP_CANCELLED,
    /** 晚点不够门槛，不能越站。 */
    SKIP_NOT_LATE,
    /** 联挂中的一体车带着两条交路，不能越站。 */
    SKIP_COUPLED,
    /** 往后没有停车站（或车没接上调度），没有可越的站。 */
    SKIP_NO_STOP,
    /** 下一个停车站不归本楼管，不能越站。 */
    SKIP_OUT_OF_ZONE,
    /** 下一个停车站是终点，不能越。 */
    SKIP_TERMINAL,
    /** 下一个停车站有作业（换线、加挂、拆开、换模、折返等），不能越。 */
    SKIP_DIRECTIVE,
    /** 车已开始进站刹车，不能再越站或撤销。 */
    SKIP_BRAKING,
    /** 下一个停车站是叫车的目的站，不能越。 */
    SKIP_CALL_DESTINATION,
    /** 有乘务员在下一个停车站等着接班，不能越。 */
    SKIP_CREW_BOARDING,
    /** 考试中不能令越站（练习中可以）。 */
    SKIP_IN_EXAM,
    /** 已令越站。 */
    SKIP_ORDERED,
    /** 已撤销越站。 */
    SKIP_REVOKED,
    /** 这一站已不能改停股道（停站已开始，或车已不在）。 */
    PLATFORM_UNAVAILABLE,
    /** 已锁闭通往原股道的进路，不能改停。 */
    PLATFORM_LOCKED,
    /** 自动通过已设置。 */
    AUTO_PASS_ON,
    /** 自动通过已取消。 */
    AUTO_PASS_OFF,
    /** 回复已发给驾驶员。 */
    REPLY_SENT,
    /** 回复与呼叫的原因不符（仍已发给驾驶员）。 */
    REPLY_WRONG,
    /** 扣车到了上限，已自动放行。 */
    HOLD_EXPIRED,
    /** 扣着的车已不在站里停着（已发车），扣车解除。 */
    HOLD_ENDED,
    /** 认不出这列车从哪个入口进来，不能设自动通过。 */
    NO_ROUTE,
    /** 调度图未就绪，不能转为人工。 */
    GRAPH_NOT_READY,
    /** 调度图未就绪，控制区暂为自动（常驻，直到图就绪）。 */
    GRAPH_NOT_READY_AUTO,
    /** 按钮要作用在选中的车上，还没选车。 */
    NO_SELECTION,
    /** 自动模式下由系统放行，这个操作不可用。 */
    AUTO_MODE,
    /** 这列车已有进路许可。 */
    ALREADY_ROUTED,
    /** 这列车已在控制区内，不用再排进路。 */
    IN_ZONE,
    /** 这列车没有可取消的进路。 */
    NO_PERMIT,
    /** 这列车已经扣着。 */
    ALREADY_HELD,
    /** 这列车没有被扣。 */
    NOT_HELD,
    /** 一列车等候超时，已由系统代排。 */
    AUTO_ROUTED,
    /** 取消的许可原来那段等待已过代排时限，车再来问闸时会立即代排。 */
    AUTO_ROUTE_DUE,
    /** 连续几列车都由系统代排、其间没有操作，控制区已转为自动。 */
    IDLE_AUTO,
    /** 取消的进路上车已在接近：这个入口接近锁闭，到时才能再排。 */
    APPROACH_LOCKING,
    /** 这列车的入口还在接近锁闭中，暂不能排进路。 */
    APPROACH_LOCKED
  }

  /**
   * 控制区入口的一架信号。
   *
   * @param exit 入口区间（一端在区内、一端在区外；站场图上画在区内那一端）
   * @param aspect 显示
   * @param waitingTrain 在这里等的车
   * @param autoPass 从这个入口进来的进路设了自动通过（信号旁写 “A”）
   */
  public record EntrySignal(
      EdgeId exit, Aspect aspect, Optional<String> waitingTrain, boolean autoPass) {
    public EntrySignal {
      Objects.requireNonNull(exit, "exit");
      Objects.requireNonNull(aspect, "aspect");
      waitingTrain = waitingTrain == null ? Optional.empty() : waitingTrain;
    }
  }

  /** 入口信号的显示。 */
  public enum Aspect {
    /** 有车在等，没有许可。 */
    STOP,
    /** 已给许可，内核还没放行。 */
    PENDING,
    /** 进路已锁闭或自动模式下放行。 */
    PROCEED
  }

  /**
   * 选中车可以改停的一条股道。
   *
   * @param node 股道的站台节点
   * @param number 股道号
   * @param current 是它现在要停的那条
   */
  public record PlatformChoice(NodeId node, String number, boolean current) {
    public PlatformChoice {
      Objects.requireNonNull(node, "node");
      Objects.requireNonNull(number, "number");
    }
  }
}
