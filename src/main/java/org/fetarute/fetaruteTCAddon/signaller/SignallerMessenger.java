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
   * 提醒：动作栏消息加提示音（呼叫、回复、即将下岗）。
   *
   * @param player 玩家
   * @param key 语言键
   * @param values 占位符
   */
  void alert(UUID player, String key, Map<String, String> values);
}
