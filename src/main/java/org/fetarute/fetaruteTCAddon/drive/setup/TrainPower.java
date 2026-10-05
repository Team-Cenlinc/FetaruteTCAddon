package org.fetarute.fetaruteTCAddon.drive.setup;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;

/** 一列车的受电方式：列车标签 {@code FTA_TRAIN_POWER}，没有或认不出时用 {@code drive.yml} 的 {@code default-power}。 */
public final class TrainPower {

  private TrainPower() {}

  /**
   * @param fallback 标签缺失或认不出时的受电方式（{@code default-power}）
   */
  public static PowerSupply of(TrainProperties properties, PowerSupply fallback) {
    return TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_POWER)
        .flatMap(PowerSupply::parse)
        .orElse(fallback);
  }
}
