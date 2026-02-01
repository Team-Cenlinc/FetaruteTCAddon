package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * DYNAMIC stop 匹配工具：解析 DYNAMIC 规范并判断 NodeId 是否匹配。
 *
 * <h3>DYNAMIC 格式</h3>
 *
 * <pre>{@code
 * DYNAMIC:OP:TYPE:NAME:[FROM:TO]
 * }</pre>
 *
 * <p>各部分含义：
 *
 * <ul>
 *   <li>{@code OP}：运营商代码（如 SURC）
 *   <li>{@code TYPE}：节点类型，{@code S} 表示 Station，{@code D} 表示 Depot
 *   <li>{@code NAME}：站点/车库名称（如 PPK、OFL）
 *   <li>{@code [FROM:TO]}：轨道范围（如 {@code [1:3]} 表示轨道 1-3）
 * </ul>
 *
 * <h3>格式示例</h3>
 *
 * <ul>
 *   <li>{@code DYNAMIC:SURC:S:PPK:[1:3]} - Station PPK，轨道范围 1-3
 *   <li>{@code DYNAMIC:SURC:D:OFL:[1:2]} - Depot OFL，轨道范围 1-2
 *   <li>{@code DYNAMIC:SURC:S:PPK} - Station PPK，默认轨道 1（无范围限制）
 *   <li>{@code DYNAMIC:SURC:PPK:[1:3]} - 旧格式兼容（默认 Station 类型）
 * </ul>
 *
 * <h3>匹配规则</h3>
 *
 * <p>NodeId（如 {@code SURC:S:PPK:2}）匹配 DYNAMIC 规范的条件：
 *
 * <ol>
 *   <li>{@code operatorCode} 匹配（忽略大小写）
 *   <li>{@code nodeType} 匹配（S/D）
 *   <li>{@code nodeName} 匹配（忽略大小写）
 *   <li>{@code track} 在 {@code [fromTrack, toTrack]} 范围内
 * </ol>
 *
 * <h3>咽喉节点区分</h3>
 *
 * <p>本工具区分站点本体（4 段）与咽喉节点（5 段）：
 *
 * <ul>
 *   <li>{@code OP:S:STATION:TRACK} - 站点本体（4 段）
 *   <li>{@code OP:S:STATION:TRACK:SEQ} - 站咽喉（5 段）
 * </ul>
 *
 * <p>容错匹配时，咽喉与站点本体不会互相匹配，避免 waypoint 被误认为是车站 stop。
 *
 * @see DynamicSpec
 * @see #parseDynamicSpec(RouteStop)
 * @see #matches(NodeId, DynamicSpec)
 */
public final class DynamicStopMatcher {

  /** 动作指令前缀正则：CHANGE/DYNAMIC/ACTION/CRET/DSTY */
  private static final Pattern ACTION_PREFIX_PATTERN =
      Pattern.compile("^(CHANGE|DYNAMIC|ACTION|CRET|DSTY)\\b", Pattern.CASE_INSENSITIVE);

  private DynamicStopMatcher() {}

  /**
   * 判断 stop 是否为 DYNAMIC 类型（notes 中包含 DYNAMIC 指令）。
   *
   * @param stop RouteStop
   * @return true 如果 stop 的 notes 包含 DYNAMIC 指令
   */
  public static boolean isDynamicStop(RouteStop stop) {
    if (stop == null || stop.notes().isEmpty()) {
      return false;
    }
    Optional<String> target = findDirectiveTarget(stop, "DYNAMIC");
    return target.isPresent();
  }

  /**
   * 解析 stop 中的 DYNAMIC 规范。
   *
   * @param stop RouteStop
   * @return DYNAMIC 规范（如果存在）
   */
  public static Optional<DynamicSpec> parseDynamicSpec(RouteStop stop) {
    if (stop == null || stop.notes().isEmpty()) {
      return Optional.empty();
    }
    Optional<String> target = findDirectiveTarget(stop, "DYNAMIC");
    if (target.isEmpty()) {
      return Optional.empty();
    }
    return parseDynamicSpec(target.get());
  }

  /**
   * 解析 DYNAMIC 规范字符串。
   *
   * @param raw 原始字符串（可能带有 DYNAMIC: 前缀）
   * @return 解析结果
   */
  public static Optional<DynamicSpec> parseDynamicSpec(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    // 去掉可能的 DYNAMIC: 前缀
    if (trimmed.regionMatches(true, 0, "DYNAMIC", 0, "DYNAMIC".length())) {
      String rest = trimmed.substring("DYNAMIC".length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      trimmed = rest;
    }
    if (trimmed.isBlank()) {
      return Optional.empty();
    }

    // 解析格式: OP:S:STATION:[range] 或 OP:D:DEPOT:[range] 或 OP:STATION:[range]
    String[] parts = trimmed.split(":", -1);
    if (parts.length < 2) {
      return Optional.empty();
    }

    String operatorCode;
    String nodeType; // "S" or "D"
    String nodeName;
    String rangeRaw;

    if (parts.length >= 3 && (parts[1].equalsIgnoreCase("S") || parts[1].equalsIgnoreCase("D"))) {
      // 新格式: OP:S:STATION:... 或 OP:D:DEPOT:...
      operatorCode = parts[0].trim();
      nodeType = parts[1].trim().toUpperCase(Locale.ROOT);
      nodeName = parts[2].trim();
      // 剩余部分作为 range（可能是 "1" 或 "[1:3]" 或 "1:3"）
      if (parts.length > 3) {
        rangeRaw = String.join(":", java.util.Arrays.copyOfRange(parts, 3, parts.length)).trim();
      } else {
        rangeRaw = "";
      }
    } else {
      // 旧格式兼容: OP:STATION:[range]
      operatorCode = parts[0].trim();
      nodeType = "S"; // 默认 Station
      nodeName = parts[1].trim();
      if (parts.length > 2) {
        rangeRaw = String.join(":", java.util.Arrays.copyOfRange(parts, 2, parts.length)).trim();
      } else {
        rangeRaw = "";
      }
    }

    if (operatorCode.isBlank() || nodeName.isBlank()) {
      return Optional.empty();
    }

    // 解析范围
    int fromTrack = 1;
    int toTrack = Integer.MAX_VALUE; // 默认无限制
    if (!rangeRaw.isBlank()) {
      String normalizedRange = rangeRaw.trim();
      if (normalizedRange.startsWith("[") && normalizedRange.endsWith("]")) {
        normalizedRange = normalizedRange.substring(1, normalizedRange.length() - 1).trim();
      }
      if (!normalizedRange.isBlank()) {
        int colon = normalizedRange.indexOf(':');
        if (colon < 0) {
          OptionalInt single = parsePositiveInt(normalizedRange);
          if (single.isEmpty()) {
            return Optional.empty();
          }
          fromTrack = single.getAsInt();
          toTrack = single.getAsInt();
        } else {
          OptionalInt from = parsePositiveInt(normalizedRange.substring(0, colon));
          OptionalInt to = parsePositiveInt(normalizedRange.substring(colon + 1));
          if (from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
          }
          fromTrack = from.getAsInt();
          toTrack = to.getAsInt();
        }
      }
    }
    if (fromTrack <= 0 || toTrack <= 0) {
      return Optional.empty();
    }
    int start = Math.min(fromTrack, toTrack);
    int end = Math.max(fromTrack, toTrack);
    return Optional.of(new DynamicSpec(operatorCode, nodeType, nodeName, start, end));
  }

  /**
   * 判断实际 nodeId 是否匹配 DYNAMIC 规范。
   *
   * <p>匹配规则：
   *
   * <ul>
   *   <li>operator 必须匹配（忽略大小写）
   *   <li>nodeType 必须匹配（S/D）
   *   <li>nodeName 必须匹配（忽略大小写）
   *   <li>track 必须在 [fromTrack, toTrack] 范围内
   * </ul>
   *
   * @param nodeId 实际 nodeId（如 "SURC:D:OFL:1"）
   * @param spec DYNAMIC 规范
   * @return true 如果匹配
   */
  public static boolean matches(NodeId nodeId, DynamicSpec spec) {
    if (nodeId == null || spec == null || nodeId.value() == null) {
      return false;
    }
    return matches(nodeId.value(), spec);
  }

  /**
   * 判断实际 nodeId 字符串是否匹配 DYNAMIC 规范。
   *
   * @param nodeIdValue 实际 nodeId 值（如 "SURC:D:OFL:1"）
   * @param spec DYNAMIC 规范
   * @return true 如果匹配
   */
  public static boolean matches(String nodeIdValue, DynamicSpec spec) {
    if (nodeIdValue == null || nodeIdValue.isBlank() || spec == null) {
      return false;
    }
    String[] parts = nodeIdValue.split(":", -1);
    if (parts.length < 4) {
      return false;
    }

    String operator = parts[0].trim();
    String nodeType = parts[1].trim().toUpperCase(Locale.ROOT);
    String nodeName = parts[2].trim();
    String trackStr = parts[3].trim();

    // 检查 operator
    if (!operator.equalsIgnoreCase(spec.operatorCode())) {
      return false;
    }
    // 检查 nodeType
    if (!nodeType.equals(spec.nodeType())) {
      return false;
    }
    // 检查 nodeName
    if (!nodeName.equalsIgnoreCase(spec.nodeName())) {
      return false;
    }
    // 检查 track
    OptionalInt trackOpt = parsePositiveInt(trackStr);
    if (trackOpt.isEmpty()) {
      return false;
    }
    int track = trackOpt.getAsInt();
    return track >= spec.fromTrack() && track <= spec.toTrack();
  }

  /**
   * 判断实际 nodeId 是否匹配 stop（支持 DYNAMIC 和普通 nodeId）。
   *
   * @param nodeId 实际 nodeId
   * @param stop RouteStop
   * @return true 如果匹配
   */
  public static boolean matchesStop(NodeId nodeId, RouteStop stop) {
    if (nodeId == null || stop == null) {
      return false;
    }
    // 先检查普通 waypointNodeId 匹配
    if (stop.waypointNodeId().isPresent()) {
      String waypointId = stop.waypointNodeId().get();
      if (nodeId.value() != null && nodeId.value().equalsIgnoreCase(waypointId)) {
        return true;
      }
      // 支持同站不同轨道的容错匹配（但咽喉 vs 站点本体不允许）
      boolean nodeIsThroat = isThroatFormat(nodeId.value());
      boolean stopIsThroat = isThroatFormat(waypointId);
      if (nodeIsThroat != stopIsThroat) {
        // 咽喉 vs 站点本体：不允许容错匹配
        // 继续检查 DYNAMIC
      } else {
        Optional<String> nodeKey = extractStationKey(nodeId.value());
        Optional<String> stopKey = extractStationKey(waypointId);
        if (nodeKey.isPresent() && stopKey.isPresent() && nodeKey.get().equals(stopKey.get())) {
          return true;
        }
      }
    }
    // 检查 DYNAMIC 匹配
    Optional<DynamicSpec> specOpt = parseDynamicSpec(stop);
    if (specOpt.isPresent()) {
      return matches(nodeId, specOpt.get());
    }
    return false;
  }

  /**
   * 判断 NodeId 值是否为咽喉格式（5 段，且第二段是 S/D）。
   *
   * <p>格式：{@code Operator:S/D:Name:Track:Seq}
   */
  private static boolean isThroatFormat(String nodeIdValue) {
    if (nodeIdValue == null || nodeIdValue.isBlank()) {
      return false;
    }
    String[] parts = nodeIdValue.split(":");
    if (parts.length != 5) {
      return false;
    }
    String typeMarker = parts[1].toUpperCase(Locale.ROOT);
    return "S".equals(typeMarker) || "D".equals(typeMarker);
  }

  /**
   * 从 nodeId 中提取 Station/Depot 级别的 key（不含 track）。
   *
   * <p>格式：{@code OP:S:STATION} 或 {@code OP:D:DEPOT}
   *
   * @param nodeIdValue nodeId 值
   * @return Station/Depot key
   */
  public static Optional<String> extractStationKey(String nodeIdValue) {
    if (nodeIdValue == null || nodeIdValue.isBlank()) {
      return Optional.empty();
    }
    String[] parts = nodeIdValue.split(":", -1);
    if (parts.length < 4) {
      return Optional.empty();
    }
    String operator = parts[0].trim().toLowerCase(Locale.ROOT);
    String nodeType = parts[1].trim().toLowerCase(Locale.ROOT);
    String nodeName = parts[2].trim().toLowerCase(Locale.ROOT);
    if (operator.isEmpty() || nodeName.isEmpty()) {
      return Optional.empty();
    }
    if (!"s".equals(nodeType) && !"d".equals(nodeType)) {
      return Optional.empty();
    }
    return Optional.of(operator + ":" + nodeType + ":" + nodeName);
  }

  /**
   * 从 DYNAMIC 规范中生成 Station/Depot 级别的 key。
   *
   * @param spec DYNAMIC 规范
   * @return Station/Depot key
   */
  public static String specToStationKey(DynamicSpec spec) {
    if (spec == null) {
      return "";
    }
    return spec.operatorCode().toLowerCase(Locale.ROOT)
        + ":"
        + spec.nodeType().toLowerCase(Locale.ROOT)
        + ":"
        + spec.nodeName().toLowerCase(Locale.ROOT);
  }

  private static Optional<String> findDirectiveTarget(RouteStop stop, String prefix) {
    if (stop == null || prefix == null || prefix.isBlank() || stop.notes().isEmpty()) {
      return Optional.empty();
    }
    String raw = stop.notes().orElse("");
    for (String line : raw.split("[\r\n]+")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      // 特殊处理 "CRET DYNAMIC:..." 格式
      if ("DYNAMIC".equalsIgnoreCase(prefix)) {
        // 检查是否包含 DYNAMIC
        int dynamicIdx = trimmed.toUpperCase(Locale.ROOT).indexOf("DYNAMIC");
        if (dynamicIdx >= 0) {
          String afterDynamic = trimmed.substring(dynamicIdx + "DYNAMIC".length()).trim();
          if (afterDynamic.startsWith(":")) {
            afterDynamic = afterDynamic.substring(1).trim();
          }
          if (!afterDynamic.isBlank()) {
            return Optional.of(afterDynamic);
          }
        }
        continue;
      }
      String normalizedAction = normalizeActionLine(trimmed);
      String actualPrefix = firstSegment(normalizedAction).trim();
      if (!actualPrefix.equalsIgnoreCase(prefix)) {
        continue;
      }
      String rest = normalizedAction.substring(actualPrefix.length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      if (rest.isBlank()) {
        continue;
      }
      return Optional.of(rest);
    }
    return Optional.empty();
  }

  private static String firstSegment(String line) {
    if (line == null || line.isEmpty()) {
      return "";
    }
    int idx = 0;
    while (idx < line.length()) {
      char ch = line.charAt(idx);
      if (Character.isWhitespace(ch) || ch == ':') {
        break;
      }
      idx++;
    }
    return line.substring(0, idx);
  }

  private static String normalizeActionLine(String line) {
    String trimmed = line.trim();
    java.util.regex.Matcher matcher = ACTION_PREFIX_PATTERN.matcher(trimmed);
    if (!matcher.find()) {
      return trimmed;
    }
    String prefix = matcher.group(1).toUpperCase(Locale.ROOT);
    String rest = trimmed.substring(prefix.length()).trim();
    if (rest.isEmpty()) {
      return prefix;
    }
    if (rest.startsWith(":")) {
      rest = rest.substring(1).trim();
    }
    if ("CRET".equals(prefix) || "DSTY".equals(prefix)) {
      return rest.isEmpty() ? prefix : prefix + " " + rest;
    }
    return prefix + ":" + rest;
  }

  private static OptionalInt parsePositiveInt(String raw) {
    if (raw == null) {
      return OptionalInt.empty();
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return OptionalInt.empty();
    }
    try {
      int value = Integer.parseInt(trimmed);
      return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
    } catch (NumberFormatException ex) {
      return OptionalInt.empty();
    }
  }

  /**
   * DYNAMIC 规范。
   *
   * @param operatorCode 运营商代码
   * @param nodeType 节点类型："S" 表示 Station，"D" 表示 Depot
   * @param nodeName 站点/车库名称
   * @param fromTrack 起始轨道号
   * @param toTrack 结束轨道号
   */
  public record DynamicSpec(
      String operatorCode, String nodeType, String nodeName, int fromTrack, int toTrack) {
    public DynamicSpec {
      Objects.requireNonNull(operatorCode, "operatorCode");
      Objects.requireNonNull(nodeType, "nodeType");
      Objects.requireNonNull(nodeName, "nodeName");
    }

    /** 是否为 Depot 类型。 */
    public boolean isDepot() {
      return "D".equalsIgnoreCase(nodeType);
    }

    /** 是否为 Station 类型。 */
    public boolean isStation() {
      return "S".equalsIgnoreCase(nodeType);
    }

    /**
     * 生成 placeholder nodeId（用于 spawn plan 和 layover 匹配）。
     *
     * <p>格式：{@code OP:S/D:NAME:fromTrack}
     *
     * @return placeholder nodeId
     */
    public String toPlaceholderNodeId() {
      return operatorCode
          + ":"
          + nodeType.toUpperCase(Locale.ROOT)
          + ":"
          + nodeName
          + ":"
          + fromTrack;
    }

    /**
     * 生成 Station/Depot 级别的 terminalKey（用于 layover 匹配，不含 track）。
     *
     * <p>格式：{@code op:s/d:name}（小写）
     *
     * @return terminalKey
     */
    public String toTerminalKey() {
      return operatorCode.toLowerCase(Locale.ROOT)
          + ":"
          + nodeType.toLowerCase(Locale.ROOT)
          + ":"
          + nodeName.toLowerCase(Locale.ROOT);
    }
  }
}
