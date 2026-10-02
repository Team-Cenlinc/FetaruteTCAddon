package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.Locale;

/**
 * 站台屏绑定的车站：运营商代码与站码，均规整为大写。
 *
 * <p>站码可能跨运营商重名，必须带运营商才能唯一确定车站。
 */
public record PidsStationKey(String operatorCode, String stationCode) {

  public PidsStationKey {
    operatorCode = normalize(operatorCode, "operatorCode");
    stationCode = normalize(stationCode, "stationCode");
  }

  private static String normalize(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(name + " 不能为空");
    }
    return raw.trim().toUpperCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return operatorCode + ":" + stationCode;
  }
}
