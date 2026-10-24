package org.fetarute.fetaruteTCAddon.signaller;

import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 呼叫原因配哪条回复：信号前等候回“进路正在排列”（要给它排）或“前方有车，请等待”（暂时排不了）；车门故障回“已收到”；线路异常回“请以注意速度运行”； 一般联系哪条都行。本类不依赖服务器对象。
 */
final class CallReplies {

  private CallReplies() {}

  /** 这条回复配得上这个原因。 */
  static boolean fits(CallReason reason, SignalboxState.Reply reply) {
    return switch (reason) {
      case SIGNAL -> reply == SignalboxState.Reply.ROUTE_SETTING
          || reply == SignalboxState.Reply.WAIT;
      case DOORS -> reply == SignalboxState.Reply.ACK;
      case OBSTRUCTION -> reply == SignalboxState.Reply.CAUTION;
      case GENERAL -> true;
    };
  }
}
