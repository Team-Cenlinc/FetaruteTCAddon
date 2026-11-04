package org.fetarute.fetaruteTCAddon.stationmaster;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 给站长与乘务员发语言文件里的消息。测试用假实现替换。 */
interface StationMasterMessenger {

  /** 聊天消息。 */
  void send(UUID player, String key, Map<String, String> values);

  /** 提醒：聊天消息，另在动作栏显示 {@code key + "-bar"} 并响一声。 */
  void alert(UUID player, String key, Map<String, String> values);

  /** 动作栏显示并响一声，不进聊天（一直在发生的日常作业，免得刷屏）。 */
  void notice(UUID player, String key, Map<String, String> values);

  /** 只在动作栏显示，不响。 */
  void cue(UUID player, String key, Map<String, String> values);

  /** 第一次遇到某种作业时的提示的种类（{@code stationmaster.tips.<key>}）。 */
  enum Tip {
    ON_DUTY,
    APPROACHING,
    SIGNAL,
    CLEARING,
    EVENT;

    /** 语言文件里的键名。 */
    String key() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  /** 第一次遇到某种作业时的提示：每名玩家每种只出一次。 */
  default void tip(UUID player, Tip tip) {}

  /** 语言文件里的一段纯文本。 */
  String text(String key);
}
