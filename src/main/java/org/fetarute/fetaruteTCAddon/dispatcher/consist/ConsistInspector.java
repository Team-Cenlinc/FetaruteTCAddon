package org.fetarute.fetaruteTCAddon.dispatcher.consist;

/**
 * 读 TrainCarts 的编组：只读配置，不出车、不碰世界。
 *
 * <p>TrainCarts 的存车配置没有加锁，主线程上的 {@code /savedtrain} 修改、重载与自动保存可能与异步读撞上， 所以两个方法都只能在主线程调用。
 */
public interface ConsistInspector {

  /**
   * 解析一个编组写法。
   *
   * @param pattern 编组写法
   * @return 原始情况；TrainCarts 不可用或解析不出车厢时 {@code cars} 为 0
   */
  ConsistInspection inspect(String pattern);

  /**
   * 这个编组再出一列是否会超过存车的出车上限。
   *
   * @param pattern 编组写法
   * @return 超过上限；不是存车或不限时为 false
   */
  boolean exceedsSpawnLimit(String pattern);
}
