package org.fetarute.fetaruteTCAddon.signaller;

import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;

/** 信号员考法的考官：值守的信号员上岗、下岗，以及在控制台上做的每件事都告诉它。由操作许可证服务实现。只在服务器主线程调用；实现不得抛异常（抛了也只记一笔，不影响值守）。 */
public interface SignallerExaminer {

  /** 没有考官：谁都不在考试。 */
  SignallerExaminer NONE =
      new SignallerExaminer() {
        @Override
        public boolean examining(UUID player) {
          return false;
        }

        @Override
        public void onDutyStarted(UUID player, String zoneName, boolean manual, boolean stations) {}

        @Override
        public void onEvent(UUID player, Event event) {}

        @Override
        public void onDutyEnded(UUID player, SignallerShift.EndReason reason) {}
      };

  /** 这名玩家正在考信号员（含练习）：这一班不发奖励。 */
  boolean examining(UUID player);

  /** 这名玩家正在做信号员练习：这一班也不写记录。 */
  default boolean practicing(UUID player) {
    return false;
  }

  /**
   * 上岗。
   *
   * @param zoneName 控制区名
   * @param manual 控制区已转为人工（调度图未就绪时暂为自动）
   * @param stations 控制区里有车站（扣车要在站里停着的车上做）
   */
  void onDutyStarted(UUID player, String zoneName, boolean manual, boolean stations);

  /** 值守中的一件事。 */
  void onEvent(UUID player, Event event);

  /** 下岗（班次已结算）。 */
  void onDutyEnded(UUID player, SignallerShift.EndReason reason);

  /** 值守中的事。 */
  enum Event {
    /** 信号员把控制区转为人工。 */
    MANUAL,
    /** 信号员把控制区转为自动。 */
    AUTO,
    /** 连续几列车由系统代排、其间没有操作，控制区被转为自动。 */
    IDLE_AUTO,
    /** 选中了一列车。 */
    SELECTED,
    /** 信号员亲手排列了一条进路（发了许可）。 */
    ROUTE_SET,
    /** 信号员亲手排的进路上，车驶入了控制区。 */
    ENTERED_MANUAL,
    /** 等许可超时、由系统代排的车驶入了控制区。 */
    ENTERED_AUTO_ROUTED,
    /** 走自动通过进路的车驶入了控制区。 */
    ENTERED_AUTO_PASSED,
    /** 扣住了一列车。 */
    HELD,
    /** 放行了扣着的车。 */
    RELEASED,
    /** 设了自动通过。 */
    AUTO_PASS_ON,
    /** 取消了自动通过。 */
    AUTO_PASS_OFF,
    /** 驾驶员呼叫信号楼。 */
    CALL_INCOMING,
    /** 回复了呼叫，回复与原因相配。 */
    CALL_ANSWERED,
    /** 回复了呼叫，但回复与原因不符。 */
    CALL_WRONG,
    /** 呼叫超时没回复。 */
    CALL_MISSED,
    /** 值守中的模拟事件答对了。 */
    SIM_RIGHT,
    /** 值守中的模拟事件答错了（含回了“进路正在排列”却没排）。 */
    SIM_WRONG,
    /** 值守中的模拟事件超时没答。 */
    SIM_MISSED,
    /** 模拟呼叫（{@code SignallerService#drillCall}）回复对了。 */
    DRILL_ANSWERED,
    /** 模拟呼叫回复与原因不符。 */
    DRILL_WRONG,
    /** 模拟呼叫超时没回复。 */
    DRILL_MISSED,
    /** 模拟呼叫用的车不在了（收车、删车）：呼叫随之撤掉，要再来一次。 */
    DRILL_CANCELLED,
    /** 给一列车改停了股道。 */
    PLATFORM_CHANGED,
    /** 取消了一列车的车次。 */
    TRIP_CANCELLED,
    /** 令一列车越过下一个停车站。 */
    SKIP_ORDERED,
    /** 撤销了越站令。 */
    SKIP_REVOKED
  }
}
