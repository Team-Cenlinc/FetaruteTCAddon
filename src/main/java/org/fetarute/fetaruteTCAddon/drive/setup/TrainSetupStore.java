package org.fetarute.fetaruteTCAddon.drive.setup;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;

/**
 * 把列车已接通的系统存进 TrainCarts 列车标签，随列车保存，跨驾驶员、跨服务器重启保留。
 *
 * <p>同时记下最后一次写入的时刻：列车无人驾驶超过冷车时限后，下一次上车按冷车处理（受电弓视为已降下），不需要后台计时。
 */
public final class TrainSetupStore {

  /** 已接通的系统，逗号分隔，如 {@code power,breaker,aux}。 */
  public static final String TAG_SYSTEMS = "FTA_DRIVE_SYSTEMS";

  /** 最后一次写入的时刻（Unix 毫秒）。 */
  public static final String TAG_SYSTEMS_AT = "FTA_DRIVE_SYSTEMS_AT";

  /** 主风缸压力（kPa），simulation 级使用。 */
  public static final String TAG_MAIN_RESERVOIR = "FTA_DRIVE_MR_KPA";

  /** 手动压缩机开关是否打开，simulation 级的机车使用。 */
  public static final String TAG_COMPRESSOR = "FTA_DRIVE_COMPRESSOR";

  /** 主风缸压力的保存时刻（Unix 毫秒），用来计算漏泄；与已接通系统的时刻分开，各自清除互不影响。 */
  public static final String TAG_MAIN_RESERVOIR_AT = "FTA_DRIVE_MR_AT";

  /**
   * 列车上保存的气压状态。
   *
   * @param mainReservoirKpa 主风缸压力，已扣除保存以来的漏泄
   * @param compressorSwitch 手动压缩机开关
   */
  public record AirSnapshot(double mainReservoirKpa, boolean compressorSwitch) {}

  private TrainSetupStore() {}

  /**
   * 读取列车上保存的已接通系统。
   *
   * @param nowMillis 当前时刻（Unix 毫秒）
   * @param coldAfterMillis 冷车时限（毫秒）
   * @return 已接通的系统；没有记录或已超过冷车时限时为空集合
   */
  public static Set<SetupSystem> load(
      TrainProperties properties, long nowMillis, long coldAfterMillis) {
    Optional<Long> at = TrainTagHelper.readLongTag(properties, TAG_SYSTEMS_AT);
    if (at.isEmpty() || isCold(at.get(), nowMillis, coldAfterMillis)) {
      return EnumSet.noneOf(SetupSystem.class);
    }
    return parse(TrainTagHelper.readTagValue(properties, TAG_SYSTEMS).orElse(""));
  }

  /**
   * 读取列车上保存的气压状态，并按保存以来的时间扣除漏泄。没有保存时刻（记录残缺）时按主风缸已排空处理。
   *
   * @param leakKpaPerMinute 每分钟漏泄
   */
  public static AirSnapshot loadAir(
      TrainProperties properties, long nowMillis, double leakKpaPerMinute) {
    double saved = TrainTagHelper.readDoubleTag(properties, TAG_MAIN_RESERVOIR).orElse(0.0);
    Optional<Long> at = TrainTagHelper.readLongTag(properties, TAG_MAIN_RESERVOIR_AT);
    double mainReservoir =
        Double.isFinite(saved) && at.isPresent()
            ? afterLeak(saved, nowMillis - at.get(), leakKpaPerMinute)
            : 0.0;
    boolean compressor =
        TrainTagHelper.readTagValue(properties, TAG_COMPRESSOR).map("on"::equals).orElse(false);
    return new AirSnapshot(mainReservoir, compressor);
  }

  /** 保存气压状态与保存时刻；主风缸已空且压缩机开关关着时清掉标签。 */
  public static void saveAir(TrainProperties properties, AirSnapshot air, long nowMillis) {
    if (air.mainReservoirKpa() <= 0.0 && !air.compressorSwitch()) {
      TrainTagHelper.removeTagKey(properties, TAG_MAIN_RESERVOIR);
      TrainTagHelper.removeTagKey(properties, TAG_COMPRESSOR);
      TrainTagHelper.removeTagKey(properties, TAG_MAIN_RESERVOIR_AT);
      return;
    }
    TrainTagHelper.writeTag(
        properties, TAG_MAIN_RESERVOIR, String.valueOf(Math.round(air.mainReservoirKpa())));
    TrainTagHelper.writeTag(properties, TAG_COMPRESSOR, air.compressorSwitch() ? "on" : "off");
    TrainTagHelper.writeTag(properties, TAG_MAIN_RESERVOIR_AT, String.valueOf(nowMillis));
  }

  /** 无人驾驶一段时间后剩余的压力。 */
  static double afterLeak(double kpa, long elapsedMillis, double leakKpaPerMinute) {
    if (elapsedMillis <= 0L) {
      return Math.max(0.0, kpa);
    }
    return Math.max(0.0, kpa - leakKpaPerMinute * elapsedMillis / 60_000.0);
  }

  /** 保存已接通的系统并记下时刻；没有接通的系统时清掉标签。 */
  public static void save(TrainProperties properties, Set<SetupSystem> on, long nowMillis) {
    if (on.isEmpty()) {
      TrainTagHelper.removeTagKey(properties, TAG_SYSTEMS);
      TrainTagHelper.removeTagKey(properties, TAG_SYSTEMS_AT);
      return;
    }
    TrainTagHelper.writeTag(properties, TAG_SYSTEMS, format(on));
    TrainTagHelper.writeTag(properties, TAG_SYSTEMS_AT, String.valueOf(nowMillis));
  }

  /** 距离上次写入是否已超过冷车时限。时钟回拨（上次写入在未来）不算冷车。 */
  static boolean isCold(long savedAtMillis, long nowMillis, long coldAfterMillis) {
    return nowMillis - savedAtMillis > coldAfterMillis;
  }

  static String format(Set<SetupSystem> on) {
    StringJoiner joiner = new StringJoiner(",");
    for (SetupSystem system : SetupSystem.values()) {
      if (system.persistent() && on.contains(system)) {
        joiner.add(system.key());
      }
    }
    return joiner.toString();
  }

  static Set<SetupSystem> parse(String raw) {
    Set<SetupSystem> on = EnumSet.noneOf(SetupSystem.class);
    for (String part : raw.split(",")) {
      SetupSystem.parse(part).filter(SetupSystem::persistent).ifPresent(on::add);
    }
    return on;
  }
}
