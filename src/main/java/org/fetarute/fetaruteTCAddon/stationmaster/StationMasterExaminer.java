package org.fetarute.fetaruteTCAddon.stationmaster;

import java.time.Instant;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterShift;

/** 站长的操作许可证考官：考试或练习中的这一班，站长服务把上岗、作业与下岗报给它。由操作许可证服务实现；站长服务不依赖它的实现。只在主线程调用，不抛异常。 */
public interface StationMasterExaminer {

  /** 没有考官：谁都不在考。 */
  StationMasterExaminer NONE = new StationMasterExaminer() {};

  /** 作业事件。 */
  enum Event {
    /** 亲手发了发车信号。 */
    SIGNAL,
    /** 等发车信号超时，由系统代发。 */
    AUTO_SIGNAL,
    /** 接车或出站监视不合格。 */
    WATCH_MISSED,
    /** 模拟事件处置正确。 */
    SIM_RIGHT,
    /** 模拟事件处置错误。 */
    SIM_WRONG,
    /** 模拟事件没按时处置。 */
    SIM_MISSED,
    /** 模拟事件用的车不在了，作废。 */
    SIM_CANCELLED
  }

  /** 这名玩家此刻在站长考试或练习的这一班上（值守中的模拟事件不另出题，只出考官要的那一道）。 */
  default boolean examining(UUID player) {
    return false;
  }

  /** 这一班是练习（不写记录、不发奖励）。 */
  default boolean practicing(UUID player) {
    return false;
  }

  /**
   * 上岗了。
   *
   * @param player 站长
   * @param station 站名
   * @param now 上岗时刻
   */
  default void onDutyStarted(UUID player, String station, Instant now) {}

  /** 作业事件。 */
  default void onEvent(UUID player, Event event) {}

  /** 下岗了（结算之后）。 */
  default void onDutyEnded(UUID player, StationMasterShift.EndReason reason) {}
}
