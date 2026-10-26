package org.fetarute.fetaruteTCAddon.signaller;

import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 呼叫原因配哪条回复：信号前等候按回复那一刻的实况判——此刻能给它排进路（前方区内没有别的车占着、车也没被扣），回“进路正在排列”；
 * 排不了，回“前方有车，请等待”。停车询问前方情况与信号前等候同一判法（被扣着、前方有车时请对方等）。车门故障与报告停妥回“已收到”；
 * 线路异常回“请以注意速度运行”；一般联系哪条都行。本类不依赖服务器对象。
 */
final class CallReplies {

  private CallReplies() {}

  /**
   * 这条回复配得上这个原因。
   *
   * @param routable 此刻能给呼叫的列车排进路（只有信号前等候看它）
   */
  static boolean fits(CallReason reason, SignalboxState.Reply reply, boolean routable) {
    return switch (reason) {
      case SIGNAL, QUERY -> reply
          == (routable ? SignalboxState.Reply.ROUTE_SETTING : SignalboxState.Reply.WAIT);
      case DOORS, REPORT -> reply == SignalboxState.Reply.ACK;
      case OBSTRUCTION -> reply == SignalboxState.Reply.CAUTION;
      case GENERAL -> true;
    };
  }
}
