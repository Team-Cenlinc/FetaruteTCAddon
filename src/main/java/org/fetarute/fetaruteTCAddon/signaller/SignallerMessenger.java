package org.fetarute.fetaruteTCAddon.signaller;

import java.util.Map;
import java.util.UUID;

/** 给玩家发信号员相关的消息；玩家不在线时什么也不做。只在主线程调用。 */
interface SignallerMessenger {

  /**
   * 聊天栏消息。
   *
   * @param player 玩家
   * @param key 语言键
   * @param values 占位符
   */
  void send(UUID player, String key, Map<String, String> values);

  /**
   * 提醒：聊天栏与动作栏消息加提示音（呼叫、回复、即将下岗）。
   *
   * @param player 玩家
   * @param key 语言键
   * @param values 占位符
   */
  void alert(UUID player, String key, Map<String, String> values);

  /**
   * 提示音加动作栏一行，不进聊天栏：列车开始接近、停到入口等候（这类事很频繁，进聊天栏会刷屏）。
   *
   * @param player 玩家
   * @param cue 哪一种（语言键 {@code signaller.cue.<种类>}，提示音各不相同）
   * @param values 占位符
   */
  default void cue(UUID player, Cue cue, Map<String, String> values) {
    alert(player, cue.key(), values);
  }

  /**
   * 语言文件里的一段纯文本，用来拼进别的消息的占位符（例如呼叫者的岗位）。
   *
   * @param key 语言键
   * @return 读不到时为键本身
   */
  String text(String key);

  /** 提示音的种类。 */
  enum Cue {
    /** 有车开始接近控制区。 */
    APPROACHING,
    /** 有车停到入口等许可。 */
    WAITING;

    /** 语言键。 */
    String key() {
      return "signaller.cue." + name().toLowerCase(java.util.Locale.ROOT);
    }
  }
}
