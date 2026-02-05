package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Line.metadata 中与自动发车相关的字段解析。
 *
 * <p>约定字段：
 *
 * <ul>
 *   <li>{@code spawn_depots}: 线路可用的 depot 列表（支持字符串或对象）
 *   <li>{@code spawn_max_trains}: 线路最大车数（可选）
 * </ul>
 */
public final class LineSpawnMetadata {

  public static final String KEY_DEPOTS = "spawn_depots";
  public static final String KEY_MAX_TRAINS = "spawn_max_trains";

  private LineSpawnMetadata() {}

  /**
   * 解析线路的 depot 列表。
   *
   * <p>支持以下格式：
   *
   * <ul>
   *   <li>字符串列表：{@code ["OP:D:DEPOT:1", "OP:D:DEPOT:2"]}
   *   <li>对象列表：{@code [{"nodeId":"OP:D:DEPOT:1","weight":2},{"nodeId":"OP:D:DEPOT:2"}]}
   *   <li>单字符串：{@code "OP:D:DEPOT:1"}
   * </ul>
   *
   * <p>对象格式支持字段：{@code nodeId}/{@code node}/{@code id}、{@code weight}、{@code enabled}。 {@code
   * enabled=false} 会忽略该条目。
   *
   * @param metadata Line.metadata
   * @return 解析后的 depot 列表
   */
  public static List<SpawnDepot> parseDepots(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return List.of();
    }
    Object raw = metadata.get(KEY_DEPOTS);
    if (raw == null) {
      return List.of();
    }
    List<SpawnDepot> out = new ArrayList<>();
    if (raw instanceof String s) {
      addDepot(out, s, 1, true);
      return List.copyOf(out);
    }
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item == null) {
          continue;
        }
        if (item instanceof String s) {
          addDepot(out, s, 1, true);
          continue;
        }
        if (item instanceof Map<?, ?> map) {
          parseDepotMap(out, map);
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * 解析线路最大车数（显式配置）。
   *
   * @param metadata Line.metadata
   * @return maxTrains；不存在或无效时返回 empty
   */
  public static OptionalInt parseMaxTrains(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return OptionalInt.empty();
    }
    Object raw = metadata.get(KEY_MAX_TRAINS);
    Integer value = parseInt(raw);
    if (value == null || value <= 0) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(value);
  }

  /**
   * 将 depot 列表写回 metadata 所需的结构。
   *
   * @param depots 线路 depot 列表
   * @return 可写入 metadata 的列表结构
   */
  public static List<Map<String, Object>> toDepotMetadata(List<SpawnDepot> depots) {
    if (depots == null || depots.isEmpty()) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (SpawnDepot depot : depots) {
      if (depot == null) {
        continue;
      }
      Map<String, Object> entry = new HashMap<>();
      entry.put("nodeId", depot.nodeId());
      if (depot.weight() != 1) {
        entry.put("weight", depot.weight());
      }
      out.add(entry);
    }
    return List.copyOf(out);
  }

  private static void parseDepotMap(List<SpawnDepot> out, Map<?, ?> map) {
    if (out == null || map == null || map.isEmpty()) {
      return;
    }
    String nodeId = findString(map, "nodeId", "node", "id");
    if (nodeId == null || nodeId.isBlank()) {
      return;
    }
    Integer weight = parseInt(map.get("weight"));
    Optional<Boolean> enabled = parseBoolean(map.get("enabled"));
    boolean ok = enabled.orElse(true);
    addDepot(out, nodeId, weight == null ? 1 : weight, ok);
  }

  private static void addDepot(List<SpawnDepot> out, String nodeId, int weight, boolean enabled) {
    if (out == null || nodeId == null) {
      return;
    }
    if (!enabled) {
      return;
    }
    String trimmed = nodeId.trim();
    if (trimmed.isBlank()) {
      return;
    }
    if (weight <= 0) {
      return;
    }
    out.add(new SpawnDepot(trimmed, weight));
  }

  private static String findString(Map<?, ?> map, String... keys) {
    if (map == null || keys == null) {
      return null;
    }
    for (String key : keys) {
      if (key == null) {
        continue;
      }
      Object value = map.get(key);
      if (value instanceof String s) {
        String trimmed = s.trim();
        if (!trimmed.isBlank()) {
          return trimmed;
        }
      }
    }
    return null;
  }

  private static Integer parseInt(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Integer i) {
      return i;
    }
    if (raw instanceof Long l) {
      if (l > Integer.MAX_VALUE) {
        return Integer.MAX_VALUE;
      }
      return l.intValue();
    }
    if (raw instanceof Number n) {
      return n.intValue();
    }
    if (raw instanceof String s) {
      String trimmed = s.trim();
      if (trimmed.isBlank()) {
        return null;
      }
      try {
        return Integer.parseInt(trimmed);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  private static Optional<Boolean> parseBoolean(Object raw) {
    if (raw == null) {
      return Optional.empty();
    }
    if (raw instanceof Boolean b) {
      return Optional.of(b);
    }
    if (raw instanceof String s) {
      String t = s.trim().toLowerCase(Locale.ROOT);
      if ("true".equals(t)) {
        return Optional.of(true);
      }
      if ("false".equals(t)) {
        return Optional.of(false);
      }
    }
    return Optional.empty();
  }

  /** 从 metadata 中读取整数值（若不存在或无效则 empty）。 */
  public static Optional<Integer> readInt(Map<String, Object> metadata, String key) {
    Objects.requireNonNull(key, "key");
    if (metadata == null || metadata.isEmpty()) {
      return Optional.empty();
    }
    Integer value = parseInt(metadata.get(key));
    return value == null ? Optional.empty() : Optional.of(value);
  }
}
