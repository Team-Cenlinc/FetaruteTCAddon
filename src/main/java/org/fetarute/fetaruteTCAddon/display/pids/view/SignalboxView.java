package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.drive.crew.CallerRole;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 信号员控制台（{@code signalbox-*} 布局）的一帧：站场图屏的一帧加上控制台数据。纯数据、按值比较，值相同就不重画。
 *
 * @param yard 站场图屏的一帧（标题、时钟、站场图）
 * @param state 控制台数据；信号员模块未就绪或屏幕没绑控制区时为空
 * @param labels 控制台文案
 * @param destinations 列车表里出现的去向站码 → 站名（查不到的不在内，按站码显示）
 * @param placeholder 没有控制台数据时写在列车表位置的说明
 */
public record SignalboxView(
    PidsYardView yard,
    Optional<SignalboxState> state,
    Labels labels,
    Map<String, String> destinations,
    Optional<Names> placeholder) {

  public SignalboxView {
    Objects.requireNonNull(yard, "yard");
    state = state == null ? Optional.empty() : state;
    Objects.requireNonNull(labels, "labels");
    destinations = destinations == null ? Map.of() : Map.copyOf(destinations);
    placeholder = placeholder == null ? Optional.empty() : placeholder;
  }

  /**
   * 控制台文案。含占位符的条目由站台屏在画的时候替换：{@code <name>} 信号员名、{@code <train>} 车次、{@code <seconds>} 秒数。
   *
   * @param manual 模式牌：人工
   * @param auto 模式牌：自动
   * @param signaller 值守者，含 {@code <name>}
   * @param unattended 没人值守
   * @param call 呼叫牌，含 {@code <train>}、{@code <caller>}（呼叫者的岗位）与 {@code <reason>}（呼叫原因）
   * @param callers 呼叫者岗位的写法（驾驶员、车掌）
   * @param callReasons 呼叫原因的写法
   * @param headerTrain 表头：车次
   * @param headerDestination 表头：去向
   * @param headerStatus 表头：状态
   * @param headerDelay 表头：晚点
   * @param statuses 列车表各状态；等许可与扣车含 {@code <seconds>}
   * @param autoRouted 由系统代排的车的状态写法，含 {@code <status>}（上面那个状态）
   * @param onTime 正点
   * @param noTrains 列车表空着时的说明
   * @param buttons 操作按钮（中英文上下叠放）；人工、自动切换按钮另见 {@code toAuto}、{@code toManual}
   * @param toAuto 模式按钮在人工模式下：转为自动
   * @param toManual 模式按钮在自动模式下：转为人工
   * @param cancelAutoPass 自动通过按钮在选中车的进路已设自动通过时：取消自动通过
   * @param replies 四条固定回复
   * @param notices 临时提示；带列车的含 {@code <train>}，进路被占用另见 {@code routeBlockedBy}
   * @param routeBlockedBy 进路被某列车占用，含 {@code <train>}
   * @param delegated 站场图上让给小区（车站控制）的那一片的标注，含 {@code <zone>}（小区名）与 {@code <name>}（小区的信号员）
   */
  public record Labels(
      String manual,
      String auto,
      String signaller,
      String unattended,
      String call,
      Map<CallerRole, String> callers,
      Map<CallReason, String> callReasons,
      String headerTrain,
      String headerDestination,
      String headerStatus,
      String headerDelay,
      Map<SignalboxState.RowStatus, String> statuses,
      String autoRouted,
      String onTime,
      String noTrains,
      Map<SignalboxState.Button, Names> buttons,
      Names toAuto,
      Names toManual,
      Names cancelAutoPass,
      Map<SignalboxState.Reply, Names> replies,
      Map<SignalboxState.NoticeKind, String> notices,
      String routeBlockedBy,
      String delegated) {

    public Labels {
      callers = Map.copyOf(callers);
      callReasons = Map.copyOf(callReasons);
      statuses = Map.copyOf(statuses);
      buttons = Map.copyOf(buttons);
      replies = Map.copyOf(replies);
      notices = Map.copyOf(notices);
    }
  }
}
