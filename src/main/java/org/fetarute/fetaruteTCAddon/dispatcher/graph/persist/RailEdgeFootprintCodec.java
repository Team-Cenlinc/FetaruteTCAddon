package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Set;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/**
 * 逐边足迹的持久化编解码：{@code [[x,y,z],[x,y,z],...]}。
 *
 * <p>坐标三元组的写法与 {@link RailInterlockingSnapshotCodec} 的 zone cells 保持一致，避免同一种数据 在库里有两种形状。
 *
 * <p>存在的理由：足迹在图构建时是有的，却在写库那一步被丢掉（`RailEdge` 不带它， `RailInterlockingState.from(...)`
 * 建完索引就把它消费了），于是下次启动从快照恢复时 cell→edge 索引必然为空，{@code cellCoverageAvailable()} 为假， 一切以实测覆盖为放行条件的机制全部
 * fail-closed 到一个都不放。
 */
public final class RailEdgeFootprintCodec {

  private RailEdgeFootprintCodec() {}

  /** 空集合编码为空串——库里存空串与存 {@code []} 语义相同，都表示"这条边没有足迹"。 */
  public static String encode(Set<RailFootprintCell> cells) {
    if (cells == null || cells.isEmpty()) {
      return "";
    }
    JsonArray encoded = new JsonArray();
    for (RailFootprintCell cell : new TreeSet<>(cells)) {
      JsonArray coordinates = new JsonArray();
      coordinates.add(cell.x());
      coordinates.add(cell.y());
      coordinates.add(cell.z());
      encoded.add(coordinates);
    }
    return encoded.toString();
  }

  /**
   * 解码；**任何无法解析的输入都返回空集合**，绝不抛出。
   *
   * <p>fail-closed：坏数据的后果必须是"当作没有足迹"（于是覆盖索引不可用、调用方保守）， 而不是让一次图加载失败、或更糟——用半份足迹算出一份看似可用其实残缺的覆盖索引。
   */
  public static Set<RailFootprintCell> decode(String json) {
    Set<RailFootprintCell> cells = new TreeSet<>();
    if (json == null || json.isBlank()) {
      return cells;
    }
    try {
      JsonElement root = JsonParser.parseString(json);
      if (!root.isJsonArray()) {
        return Set.of();
      }
      for (JsonElement encoded : root.getAsJsonArray()) {
        if (!encoded.isJsonArray()) {
          return Set.of();
        }
        JsonArray coordinates = encoded.getAsJsonArray();
        if (coordinates.size() != 3) {
          return Set.of();
        }
        cells.add(
            new RailFootprintCell(
                coordinates.get(0).getAsInt(),
                coordinates.get(1).getAsInt(),
                coordinates.get(2).getAsInt()));
      }
    } catch (RuntimeException ex) {
      return Set.of();
    }
    return cells;
  }
}
