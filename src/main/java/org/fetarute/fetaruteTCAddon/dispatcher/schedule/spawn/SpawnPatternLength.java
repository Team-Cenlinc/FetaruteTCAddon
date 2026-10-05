package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableMember;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.PhysicalRailFootprintPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.PhysicalInterlockingBerthPolicy;

/**
 * 出车编组的保守车长（格）。
 *
 * <p>与运行时量在线列车的车长同一公式（{@link PhysicalRailFootprintPolicy#conservativeTrainLengthBlocks}，再向上取整），
 * 只是各节车中心之间的跨度不是实测，而按 TrainCarts 在直道上出车时的间距算：相邻两节中心相距两个半车长加两节的车钩长。编表据此估前车把身后线路放出来的时刻，
 * 与运行时尾部保护量车身用的是同一个数。
 */
public final class SpawnPatternLength {

  private SpawnPatternLength() {}

  /**
   * 解析编组并算车长；需要 TrainCarts 已加载（读存档车）。
   *
   * @param pattern 车库牌子第 4 行或交路 metadata 写的编组
   * @return 保守车长；TrainCarts 未加载、编组解析不出车时为空
   */
  public static OptionalLong of(String pattern) {
    TrainCarts trainCarts = TrainCarts.plugin;
    if (trainCarts == null || pattern == null || pattern.isBlank()) {
      return OptionalLong.empty();
    }
    SpawnableGroup group = SpawnableGroup.parse(trainCarts, pattern);
    if (group == null || group.getMembers().isEmpty()) {
      return OptionalLong.empty();
    }
    List<Double> cartLengths = new ArrayList<>();
    List<Double> couplerLengths = new ArrayList<>();
    for (SpawnableMember member : group.getMembers()) {
      cartLengths.add(member.getLength());
      couplerLengths.add(member.getCartCouplerLength());
    }
    return of(cartLengths, couplerLengths);
  }

  /**
   * 由每节车的车体长度与车钩长度算车长。
   *
   * @param cartLengths 每节车的车体长度，按编组从头到尾
   * @param couplerLengths 每节车的车钩长度，与 {@code cartLengths} 一一对应
   * @return 保守车长；没有车、两列长度不一样或有无效值时为空
   */
  static OptionalLong of(List<Double> cartLengths, List<Double> couplerLengths) {
    if (cartLengths == null
        || couplerLengths == null
        || cartLengths.isEmpty()
        || cartLengths.size() != couplerLengths.size()) {
      return OptionalLong.empty();
    }
    double centerSpan = 0.0D;
    for (int i = 0; i + 1 < cartLengths.size(); i++) {
      Double coupler = couplerLengths.get(i);
      Double nextCoupler = couplerLengths.get(i + 1);
      if (coupler == null
          || nextCoupler == null
          || cartLengths.get(i) == null
          || cartLengths.get(i + 1) == null) {
        return OptionalLong.empty();
      }
      centerSpan +=
          0.5D * cartLengths.get(i) + coupler + nextCoupler + 0.5D * cartLengths.get(i + 1);
    }
    OptionalDouble estimate =
        PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(centerSpan, cartLengths);
    return PhysicalInterlockingBerthPolicy.conservativeTrainLengthBlocks(estimate);
  }
}
