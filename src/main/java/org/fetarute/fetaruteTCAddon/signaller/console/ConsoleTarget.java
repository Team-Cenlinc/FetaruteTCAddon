package org.fetarute.fetaruteTCAddon.signaller.console;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** 控制台上被点到的东西。 */
public sealed interface ConsoleTarget {

  /**
   * 一列车：列车表的一行、站场图上的车次窗，或有车在等的入口信号。
   *
   * @param trainName 列车名
   */
  record Train(String trainName) implements ConsoleTarget {
    public Train {
      Objects.requireNonNull(trainName, "trainName");
    }
  }

  /**
   * 操作区的一个按钮。
   *
   * @param button 按钮
   */
  record Press(SignalboxState.Button button) implements ConsoleTarget {
    public Press {
      Objects.requireNonNull(button, "button");
    }
  }

  /**
   * 回复呼叫时，操作区换成的四条固定回复之一。
   *
   * @param reply 回复
   */
  record Answer(SignalboxState.Reply reply) implements ConsoleTarget {
    public Answer {
      Objects.requireNonNull(reply, "reply");
    }
  }

  /**
   * 列车操作（第二页）的一行。
   *
   * @param action 操作
   */
  record Act(SignalboxState.TrainAction action) implements ConsoleTarget {
    public Act {
      Objects.requireNonNull(action, "action");
    }
  }

  /** 标题栏的呼叫牌：开始回复呼叫（操作区换成固定回复）。 */
  record CallBadge() implements ConsoleTarget {}

  /**
   * 选中车可以改停的一条股道（{@code DYNAMIC:} 停站）。
   *
   * @param node 股道的站台节点
   */
  record Platform(NodeId node) implements ConsoleTarget {
    public Platform {
      Objects.requireNonNull(node, "node");
    }
  }

  /**
   * 没有车在等、设了自动通过的入口信号：撤掉从这个入口进来的自动通过。
   *
   * @param exit 入口区间
   */
  record Signal(EdgeId exit) implements ConsoleTarget {
    public Signal {
      Objects.requireNonNull(exit, "exit");
    }
  }

  /** 点在空白处，或左键：取消选择（回复呼叫或列车操作时先退回按钮）。 */
  record Deselect() implements ConsoleTarget {}
}
