package org.fetarute.fetaruteTCAddon.signaller.console;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

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
    List<PlatformChoice> platforms) {

  public SignalboxState {
    Objects.requireNonNull(mode, "mode");
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
    /** 扣住选中的、正在停站的车。 */
    HOLD,
    /** 放行扣着的车。 */
    RELEASE,
    /** 选中车这条进路（同一入口、同一股道）以后的车都自动排列；再按取消。 */
    AUTO_PASS,
    /** 整个控制区在人工与自动之间切换。 */
    MODE
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
   * @param autoProgress 离超时自动排列还有多远：0 刚开始等，1 马上自动排列；不在等时为 0
   * @param autoPass 这列车走的是一条自动通过的进路
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
      boolean autoPass) {
    public TrainRow {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(status, "status");
      lineColor = lineColor == null ? Optional.empty() : lineColor;
      destination = destination == null ? Optional.empty() : destination;
      delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
    }
  }

  /**
   * 驾驶员的呼叫。
   *
   * @param trainName 呼叫的列车
   * @param driver 驾驶员名
   */
  public record Call(String trainName, String driver) {
    public Call {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(driver, "driver");
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

  /** 临时提示的种类。 */
  public enum NoticeKind {
    /** 进路被别的车占用，许可排列中。 */
    ROUTE_BLOCKED,
    /** 进路已锁闭，不能取消。 */
    ROUTE_LOCKED,
    /** 这列车不在站里停着，不能扣。 */
    NOT_DWELLING,
    /** 这一站不是动态站台，不能改股道。 */
    NOT_DYNAMIC,
    /** 自动通过已设置。 */
    AUTO_PASS_ON,
    /** 自动通过已取消。 */
    AUTO_PASS_OFF,
    /** 回复已发给驾驶员。 */
    REPLY_SENT,
    /** 扣车到了上限，已自动放行。 */
    HOLD_EXPIRED,
    /** 认不出这列车从哪个入口进来，不能设自动通过。 */
    NO_ROUTE,
    /** 调度图未就绪，不能转为人工。 */
    GRAPH_NOT_READY
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
