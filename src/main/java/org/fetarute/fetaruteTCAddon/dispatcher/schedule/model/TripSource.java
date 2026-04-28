package org.fetarute.fetaruteTCAddon.dispatcher.schedule.model;

/**
 * 服务车次的来源。
 *
 * <p>第一阶段把静态骨干、按需叠加与运行恢复统一放进同一套计划模型中。来源字段不改变 TrainCarts 的 destination 策略，只用于排班审阅、发车仲裁和后续
 * PIDS/ETA/HUD 消费时区分“这趟车为什么存在”。
 */
public enum TripSource {
  /** 来自静态 headway 或后续时刻表快照的骨干车次。 */
  SCHEDULED,

  /** 由客流、运营员或外部系统临时追加的按需车次。 */
  ON_DEMAND,

  /** 为补洞、追赶延误或故障恢复而生成的恢复车次。 */
  RECOVERY,

  /** 由命令或调试入口手动创建的车次。 */
  MANUAL
}
