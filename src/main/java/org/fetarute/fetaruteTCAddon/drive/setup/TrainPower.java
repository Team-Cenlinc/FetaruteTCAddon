package org.fetarute.fetaruteTCAddon.drive.setup;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;

/**
 * 一列车的受电方式：列车标签 {@code FTA_TRAIN_POWER}，没有或认不出时用 {@code drive.yml} 的 {@code default-power}。
 *
 * <p>标签写多个即多模车（如 {@code ptg6,diesel}）：第一个是默认模式，当前用哪个记在 {@code FTA_TRAIN_POWER_ACTIVE}，随车走，
 * 重启、改名照认；没记或记的不在列表里时取第一个。
 */
public final class TrainPower {

  private TrainPower() {}

  /**
   * 当前用的受电方式。
   *
   * @param fallback 标签缺失或认不出时的受电方式（{@code default-power}）
   */
  public static PowerSupply of(TrainProperties properties, PowerSupply fallback) {
    List<PowerSupply> supplies = supplies(properties, fallback);
    return TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_POWER_ACTIVE)
        .flatMap(PowerSupply::parse)
        .filter(supplies::contains)
        .orElse(supplies.get(0));
  }

  /**
   * 车上有的全部受电方式，第一个是默认模式。
   *
   * @param fallback 标签缺失或认不出时的受电方式
   */
  public static List<PowerSupply> supplies(TrainProperties properties, PowerSupply fallback) {
    return TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_POWER)
        .flatMap(PowerSupply::parseList)
        .orElse(List.of(fallback));
  }

  /** 是不是多模车：标签写了不止一种受电方式。 */
  public static boolean multiMode(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_POWER)
        .flatMap(PowerSupply::parseList)
        .map(list -> list.size() > 1)
        .orElse(false);
  }

  /** 记下当前用的受电方式。 */
  public static void setActive(TrainProperties properties, PowerSupply supply) {
    TrainTagHelper.writeTag(properties, TrainConfigResolver.TAG_TRAIN_POWER_ACTIVE, supply.key());
  }

  /** 记下的当前受电方式；没记时为空。 */
  public static Optional<PowerSupply> recordedActive(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_POWER_ACTIVE)
        .flatMap(PowerSupply::parse);
  }
}
