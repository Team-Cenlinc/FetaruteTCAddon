package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 正在调车（车钩扳手推着慢速对接）的列车：调度让出控制，与驾驶员接管同样对待——执行层不写限速、不发车、不刹车，健康层不做恢复动作，发车票与回收不接它。
 *
 * <p>按逻辑车名记（不分大小写，拆分临时名按本名认）。调车动作结束（接上、失败或被打断）必须撤销。
 */
public final class ShuntingRegistry {

  private static final Set<String> SHUNTING = ConcurrentHashMap.newKeySet();

  private ShuntingRegistry() {}

  /** 开始调车。 */
  public static void begin(String trainName) {
    String key = key(trainName);
    if (!key.isEmpty()) {
      SHUNTING.add(key);
    }
  }

  /** 结束调车。 */
  public static void end(String trainName) {
    SHUNTING.remove(key(trainName));
  }

  /** 这列车是否正在调车。 */
  public static boolean isShunting(String trainName) {
    return !SHUNTING.isEmpty() && SHUNTING.contains(key(trainName));
  }

  /** 清空（插件停用时）。 */
  public static void clear() {
    SHUNTING.clear();
  }

  private static String key(String trainName) {
    return trainName == null ? "" : TrainNameNormalizer.normalizeKey(trainName);
  }
}
