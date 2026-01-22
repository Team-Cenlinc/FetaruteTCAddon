package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.OptionalInt;

/**
 * 停站时间模型：区分“运行时剩余停站”与“预排班计划停站”。
 *
 * <p>MVP：仅支持传入运行时快照中的 dwellRemainingSec。
 */
public final class DwellModel {

  /**
   * @param dwellRemainingSec 运行时剩余停站秒数（可为 null）
   */
  public OptionalInt dwellSec(Integer dwellRemainingSec) {
    if (dwellRemainingSec == null) {
      return OptionalInt.empty();
    }
    int sec = dwellRemainingSec;
    return sec < 0 ? OptionalInt.empty() : OptionalInt.of(sec);
  }
}
