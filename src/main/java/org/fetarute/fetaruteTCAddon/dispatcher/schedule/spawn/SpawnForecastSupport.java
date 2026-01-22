package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 未出票服务的 ETA 预测支持。
 *
 * <p>用于站牌/ETA 展示，返回值为“虚拟票据”，不可用于实际出车。
 */
public interface SpawnForecastSupport {

  /**
   * 生成指定时间窗内的预测票据。
   *
   * @param now 当前时间
   * @param horizon 时间窗
   * @param maxPerService 单服务最大条数（<=0 则按 1 处理）
   * @return 预测票据列表
   */
  List<SpawnTicket> snapshotForecast(Instant now, Duration horizon, int maxPerService);
}
