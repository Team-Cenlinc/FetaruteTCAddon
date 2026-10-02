package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.InterlockingZoneInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 稀疏物理联锁快照的确定性 JSON 编解码器。
 *
 * <p>编码按 Zone key、Edge 端点与三维坐标稳定排序。解码任何未来格式、损坏结构或不合法 Zone 时返回空值，持久化 Adapter 据此 fail-closed。
 */
public final class RailInterlockingSnapshotCodec {

  private static final Gson GSON = new Gson();

  private RailInterlockingSnapshotCodec() {}

  /** 将一个世界的稀疏快照编码为稳定 JSON。 */
  public static String encode(RailInterlockingSnapshotRecord snapshot) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot 不能为空");
    }
    JsonObject root = new JsonObject();
    root.addProperty("formatVersion", snapshot.formatVersion());
    root.addProperty("edgeSignature", snapshot.edgeSignature());
    JsonObject coverage = new JsonObject();
    coverage.addProperty("expectedEdges", snapshot.coverage().inputEdgeCount());
    coverage.addProperty("participatingEdges", snapshot.coverage().participatingEdgeCount());
    coverage.addProperty("complete", snapshot.coverage().complete());
    root.add("coverage", coverage);
    JsonArray zones = new JsonArray();
    new TreeMap<>(snapshot.zones()).values().forEach(zone -> zones.add(encodeZone(zone)));
    root.add("zones", zones);
    return GSON.toJson(root);
  }

  /**
   * 解码指定世界的稀疏快照。
   *
   * @param worldId SQL 主键提供的世界
   * @param json 待验证的快照 JSON
   * @return 完整受支持的记录；任意损坏均返回 empty
   */
  public static Optional<RailInterlockingSnapshotRecord> decode(UUID worldId, String json) {
    if (worldId == null || json == null || json.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      int formatVersion = requireInteger(root.get("formatVersion"));
      if (formatVersion != RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION) {
        return Optional.empty();
      }
      String edgeSignature = requireString(root.get("edgeSignature"));
      JsonObject encodedCoverage = root.getAsJsonObject("coverage");
      RailInterlockingCoverage coverage =
          new RailInterlockingCoverage(
              requireInteger(encodedCoverage.get("expectedEdges")),
              requireInteger(encodedCoverage.get("participatingEdges")),
              requireBoolean(encodedCoverage.get("complete")));
      Map<String, InterlockingZoneInfo> zones = new TreeMap<>();
      for (JsonElement encodedZone : root.getAsJsonArray("zones")) {
        InterlockingZoneInfo zone = decodeZone(encodedZone.getAsJsonObject());
        if (zones.putIfAbsent(zone.zoneKey(), zone) != null) {
          return Optional.empty();
        }
      }
      return Optional.of(
          new RailInterlockingSnapshotRecord(
              worldId, formatVersion, edgeSignature, coverage, zones));
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private static JsonObject encodeZone(InterlockingZoneInfo zone) {
    JsonObject encoded = new JsonObject();
    encoded.addProperty("key", zone.zoneKey());
    encoded.add("firstEdge", encodeEdge(zone.firstEdge()));
    encoded.add("secondEdge", encodeEdge(zone.secondEdge()));
    JsonArray cells = new JsonArray();
    for (RailFootprintCell cell : new TreeSet<>(zone.overlapCells())) {
      JsonArray coordinates = new JsonArray();
      coordinates.add(cell.x());
      coordinates.add(cell.y());
      coordinates.add(cell.z());
      cells.add(coordinates);
    }
    encoded.add("cells", cells);
    return encoded;
  }

  private static JsonArray encodeEdge(EdgeId edge) {
    JsonArray encoded = new JsonArray();
    encoded.add(edge.a().value());
    encoded.add(edge.b().value());
    return encoded;
  }

  private static InterlockingZoneInfo decodeZone(JsonObject encoded) {
    String key = requireString(encoded.get("key"));
    if (!key.startsWith("interlocking:")) {
      throw new IllegalArgumentException("Zone key 类型不受支持");
    }
    EdgeId first = decodeEdge(encoded.getAsJsonArray("firstEdge"));
    EdgeId second = decodeEdge(encoded.getAsJsonArray("secondEdge"));
    Set<RailFootprintCell> cells = new TreeSet<>();
    for (JsonElement encodedCell : encoded.getAsJsonArray("cells")) {
      JsonArray coordinates = encodedCell.getAsJsonArray();
      if (coordinates.size() != 3) {
        throw new IllegalArgumentException("Zone 坐标必须包含三个整数");
      }
      cells.add(
          new RailFootprintCell(
              requireInteger(coordinates.get(0)),
              requireInteger(coordinates.get(1)),
              requireInteger(coordinates.get(2))));
    }
    if (cells.isEmpty()) {
      throw new IllegalArgumentException("Zone 必须包含局部检测坐标");
    }
    return new InterlockingZoneInfo(key, first, second, cells);
  }

  private static EdgeId decodeEdge(JsonArray encoded) {
    if (encoded == null || encoded.size() != 2) {
      throw new IllegalArgumentException("Edge 必须包含两个端点");
    }
    return EdgeId.undirected(
        NodeId.of(requireString(encoded.get(0))), NodeId.of(requireString(encoded.get(1))));
  }

  private static int requireInteger(JsonElement element) {
    if (element == null || !element.isJsonPrimitive()) {
      throw new IllegalArgumentException("JSON 值不是整数");
    }
    JsonPrimitive primitive = element.getAsJsonPrimitive();
    if (!primitive.isNumber()) {
      throw new IllegalArgumentException("JSON 值不是整数");
    }
    return primitive.getAsBigDecimal().intValueExact();
  }

  private static boolean requireBoolean(JsonElement element) {
    if (element == null || !element.isJsonPrimitive()) {
      throw new IllegalArgumentException("JSON 值不是布尔值");
    }
    JsonPrimitive primitive = element.getAsJsonPrimitive();
    if (!primitive.isBoolean()) {
      throw new IllegalArgumentException("JSON 值不是布尔值");
    }
    return primitive.getAsBoolean();
  }

  private static String requireString(JsonElement element) {
    if (element == null || !element.isJsonPrimitive()) {
      throw new IllegalArgumentException("JSON 值不是字符串");
    }
    JsonPrimitive primitive = element.getAsJsonPrimitive();
    if (!primitive.isString()) {
      throw new IllegalArgumentException("JSON 值不是字符串");
    }
    String value = primitive.getAsString();
    if (value.isBlank()) {
      throw new IllegalArgumentException("JSON 字符串不能为空");
    }
    return value;
  }
}
