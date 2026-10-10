package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/**
 * 找出车身叠在一起的列车对。
 *
 * <p>调度出的车关了列车间碰撞（见 {@code SpawnPhysicsProperties}），两车接近只靠闭塞隔开；闭塞一旦放错，后车会直接开进前车车身，
 * 停在同一站台上叠成一团。这种状态不会自己解开：两车互相压着对方要走的资源，谁也发不了车。
 *
 * <p>判据：有一对车厢真的嵌进对方（两车厢半车长之和减去中心距至少 {@link #MIN_DEPTH_BLOCKS} 格），且这两节车厢都压在对方列车的实时轨道足迹上。
 *
 * <ul>
 *   <li>只看足迹不够：足迹两端带余量，跨多段路径时会保留整段路径（TCCoasters 一段可以很长），紧跟在前车后面排队的车也会共用不少格；
 *   <li>只看车厢距离也不够：并列股道上并排停的车厢中心只隔几格；
 *   <li>两车足迹随便共用一格也不够：余量可能顺着道岔漏到邻线，配上恰好并排的两节车厢就成了误判。所以"在同一条轨道上"必须落在嵌进去的那对车厢自己身上。
 * </ul>
 *
 * <p>只做纯计算：输入由调用方在主线程采样，本类不读运行时状态。
 */
final class TrainOverlapDetector {

  /** 车厢至少嵌进对方这么多格才算车身重叠。 */
  static final double MIN_DEPTH_BLOCKS = 2.0;

  private TrainOverlapDetector() {}

  /**
   * 一节车厢的中心位置、半车长（格）与中心所在方块。
   *
   * @param cell 车厢中心所在的方块，用来判断它压没压在另一列车的足迹上
   */
  record Cart(double x, double y, double z, double halfLength, RailFootprintCell cell) {

    /** 按实体中心坐标取所在方块。 */
    static Cart at(double x, double y, double z, double halfLength) {
      return new Cart(
          x,
          y,
          z,
          halfLength,
          new RailFootprintCell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    /** 车厢中心压在这份足迹上：中心所在方块或正下方一格在足迹里（车厢实体可能略高于轨道路径）。 */
    boolean onFootprint(Set<RailFootprintCell> cells) {
      return cell != null
          && (cells.contains(cell)
              || cells.contains(new RailFootprintCell(cell.x(), cell.y() - 1, cell.z())));
    }
  }

  /**
   * 一列车此刻的实时轨道足迹与各节车厢。
   *
   * @param trainName 调度用的逻辑列车名
   * @param worldId 所在世界（足迹与车厢坐标都不带世界）
   * @param cells 整列车身覆盖的轨道方块
   * @param carts 各节车厢
   */
  record Sample(String trainName, UUID worldId, Set<RailFootprintCell> cells, List<Cart> carts) {

    Sample {
      trainName = trainName == null ? "" : trainName.trim();
      cells = cells == null ? Set.of() : Set.copyOf(cells);
      carts = carts == null ? List.of() : List.copyOf(carts);
    }
  }

  /**
   * 一对车身重叠的列车，按列车名（不分大小写）排序，{@code trainA} 在前。
   *
   * @param depthBlocks 嵌得最深的一对车厢嵌进对方的格数（取整）
   */
  record Pair(String trainA, String trainB, int depthBlocks) {

    /** 不分大小写、与先后无关的配对键。 */
    String key() {
      return trainA.toLowerCase(Locale.ROOT) + "|" + trainB.toLowerCase(Locale.ROOT);
    }

    /** 两组车同名：每份采样来自不同的物理编组，同名说明出现了重复的车，按车名分不出要删哪一组。 */
    boolean sameName() {
      return trainA.equalsIgnoreCase(trainB);
    }
  }

  /**
   * 采样前的粗筛：只按车厢中心与半车长，两列车有没有可能叠上。
   *
   * <p>是 {@link #overlappingPairs} 判据的必要条件（那里只是在此基础上再要求车厢压在对方足迹上），所以粗筛不会漏掉真叠上的车。
   */
  static boolean mayOverlap(List<Cart> first, List<Cart> second) {
    return deepestInterpenetration(first, second, null, null) >= MIN_DEPTH_BLOCKS;
  }

  /** 返回车身重叠的列车对，按配对键排序；同一配对键只留嵌得最深的一份。 */
  static List<Pair> overlappingPairs(Collection<Sample> samples) {
    if (samples == null || samples.size() < 2) {
      return List.of();
    }
    List<Sample> usable = new ArrayList<>();
    for (Sample sample : samples) {
      if (sample != null
          && !sample.trainName().isEmpty()
          && sample.worldId() != null
          && !sample.cells().isEmpty()
          && !sample.carts().isEmpty()) {
        usable.add(sample);
      }
    }
    usable.sort(Comparator.comparing(sample -> sample.trainName().toLowerCase(Locale.ROOT)));
    Map<String, Pair> pairs = new TreeMap<>();
    for (int i = 0; i < usable.size(); i++) {
      for (int j = i + 1; j < usable.size(); j++) {
        Sample first = usable.get(i);
        Sample second = usable.get(j);
        if (!first.worldId().equals(second.worldId())) {
          continue;
        }
        double depth =
            deepestInterpenetration(first.carts(), second.carts(), first.cells(), second.cells());
        if (depth < MIN_DEPTH_BLOCKS) {
          continue;
        }
        Pair pair = new Pair(first.trainName(), second.trainName(), (int) Math.floor(depth));
        pairs.merge(
            pair.key(),
            pair,
            (kept, added) -> kept.depthBlocks() >= added.depthBlocks() ? kept : added);
      }
    }
    return List.copyOf(pairs.values());
  }

  /** 两列车里嵌得最深的一对车厢：半车长之和减去中心距（不看足迹）。 */
  static double deepestInterpenetration(List<Cart> first, List<Cart> second) {
    return deepestInterpenetration(first, second, null, null);
  }

  /**
   * 两列车里嵌得最深的一对车厢。足迹不为空时，只算两节都压在对方足迹上的车厢对。
   *
   * @param firstCells 第一列车的足迹，{@code null} 表示不看足迹
   * @param secondCells 第二列车的足迹，{@code null} 表示不看足迹
   */
  private static double deepestInterpenetration(
      List<Cart> first,
      List<Cart> second,
      Set<RailFootprintCell> firstCells,
      Set<RailFootprintCell> secondCells) {
    double deepest = Double.NEGATIVE_INFINITY;
    for (Cart a : first) {
      if (secondCells != null && !a.onFootprint(secondCells)) {
        continue;
      }
      for (Cart b : second) {
        if (firstCells != null && !b.onFootprint(firstCells)) {
          continue;
        }
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        double depth = a.halfLength() + b.halfLength() - Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (Double.isFinite(depth)) {
          deepest = Math.max(deepest, depth);
        }
      }
    }
    return deepest;
  }
}
