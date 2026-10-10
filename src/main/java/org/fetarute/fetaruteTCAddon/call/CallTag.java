package org.fetarute.fetaruteTCAddon.call;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 叫来的车身上的标签值：{@code 叫车 id@运营商:站码}。
 *
 * <p>标签随 TrainCarts 属性持久化、改名也跟着车走：重启后据此认出哪些车是叫来的、是叫到哪个站的。
 *
 * @param callId 叫车 id（即叫车票的 id）
 * @param station 叫车的车站
 */
public record CallTag(UUID callId, PidsStationKey station) {

  public CallTag {
    Objects.requireNonNull(callId, "callId");
    Objects.requireNonNull(station, "station");
  }

  /** 写进标签的文本。 */
  public String format() {
    return callId + "@" + station.operatorCode() + ":" + station.stationCode();
  }

  /**
   * 解析标签值；写法不对时为空。
   *
   * @param value 标签值
   */
  public static Optional<CallTag> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String text = value.trim();
    int at = text.indexOf('@');
    if (at <= 0 || at == text.length() - 1) {
      return Optional.empty();
    }
    String stationText = text.substring(at + 1);
    int colon = stationText.indexOf(':');
    if (colon <= 0 || colon == stationText.length() - 1) {
      return Optional.empty();
    }
    try {
      UUID id = UUID.fromString(text.substring(0, at));
      return Optional.of(
          new CallTag(
              id,
              new PidsStationKey(
                  stationText.substring(0, colon).toUpperCase(Locale.ROOT),
                  stationText.substring(colon + 1).toUpperCase(Locale.ROOT))));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }
}
