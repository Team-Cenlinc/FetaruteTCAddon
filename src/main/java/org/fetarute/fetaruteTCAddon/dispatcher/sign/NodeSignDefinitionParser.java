package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.EnumSet;
import java.util.Optional;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;

/** 从牌子方块解析节点定义（waypoint/autostation/depot）。 */
public final class NodeSignDefinitionParser {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private NodeSignDefinitionParser() {}

  public static Optional<SignNodeDefinition> parse(Sign sign) {
    if (sign == null) {
      return Optional.empty();
    }

    return parseFromSide(sign, Side.FRONT).or(() -> parseFromSide(sign, Side.BACK));
  }

  private static Optional<SignNodeDefinition> parseFromSide(Sign sign, Side side) {
    String trainHeader = safeLine(sign, side, 0).trim();
    SignActionHeader parsedHeader = SignActionHeader.parse(trainHeader);
    if (parsedHeader == null || (!parsedHeader.isTrain() && !parsedHeader.isCart())) {
      return Optional.empty();
    }
    String header = safeLine(sign, side, 1).trim().toLowerCase(java.util.Locale.ROOT);
    NodeType nodeType;
    EnumSet<WaypointKind> expectedKinds;
    switch (header) {
      case "waypoint" -> {
        nodeType = NodeType.WAYPOINT;
        expectedKinds =
            EnumSet.of(
                WaypointKind.INTERVAL, WaypointKind.STATION_THROAT, WaypointKind.DEPOT_THROAT);
      }
      case "autostation" -> {
        nodeType = NodeType.STATION;
        expectedKinds = EnumSet.of(WaypointKind.STATION);
      }
      case "depot" -> {
        nodeType = NodeType.DEPOT;
        expectedKinds = EnumSet.of(WaypointKind.DEPOT);
      }
      default -> {
        return Optional.empty();
      }
    }

    String line2 = safeLine(sign, side, 2);
    String line3 = safeLine(sign, side, 3);
    return parseNodeIdFromLines(line2, line3, nodeType)
        .filter(
            def ->
                def.waypointMetadata()
                    .map(metadata -> expectedKinds.contains(metadata.kind()))
                    .orElse(false));
  }

  /**
   * 解析 TrainCarts 的 {@link TrackedSign}（包含 TCCoasters 的 TrackNodeSign 虚拟牌子）。
   *
   * <p>注意：TCC 的虚拟牌子并不是 Bukkit 的 {@link Sign} tile entity，因此不能依赖 world.getTileEntities() 扫描； 必须通过
   * TrainCarts 的 {@link TrackedSign#getLine(int)} 读取文本。
   */
  public static Optional<SignNodeDefinition> parse(TrackedSign trackedSign) {
    if (trackedSign == null) {
      return Optional.empty();
    }
    SignActionHeader header = trackedSign.getHeader();
    if (header == null || (!header.isTrain() && !header.isCart())) {
      return Optional.empty();
    }

    String action = safeLine(trackedSign, 1).trim().toLowerCase(java.util.Locale.ROOT);
    NodeType nodeType;
    EnumSet<WaypointKind> expectedKinds;
    switch (action) {
      case "waypoint" -> {
        nodeType = NodeType.WAYPOINT;
        expectedKinds =
            EnumSet.of(
                WaypointKind.INTERVAL, WaypointKind.STATION_THROAT, WaypointKind.DEPOT_THROAT);
      }
      case "autostation" -> {
        nodeType = NodeType.STATION;
        expectedKinds = EnumSet.of(WaypointKind.STATION);
      }
      case "depot" -> {
        nodeType = NodeType.DEPOT;
        expectedKinds = EnumSet.of(WaypointKind.DEPOT);
      }
      default -> {
        return Optional.empty();
      }
    }

    String line2 = safeLine(trackedSign, 2);
    String line3 = safeLine(trackedSign, 3);
    return parseNodeIdFromLines(line2, line3, nodeType)
        .filter(
            def ->
                def.waypointMetadata()
                    .map(metadata -> expectedKinds.contains(metadata.kind()))
                    .orElse(false));
  }

  private static Optional<SignNodeDefinition> parseNodeIdFromLines(
      String line2, String line3, NodeType nodeType) {
    if (nodeType == null) {
      return Optional.empty();
    }

    // 牌子第 3/4 行经常因为长度限制被拆行，或因粘贴/编辑带上引号、括号等符号。
    // 这里做“宽松提取 + 逐个尝试解析”，确保在扫描/拆牌清理等场景下也能稳定从文本回退解析出 NodeId。
    java.util.List<String> candidates = new java.util.ArrayList<>(8);
    if (line2 != null && !line2.isBlank()) {
      candidates.add(line2);
    }
    if (line3 != null && !line3.isBlank()) {
      candidates.add(line3);
    }
    if (line2 != null && !line2.isBlank() && line3 != null && !line3.isBlank()) {
      candidates.add(line2.trim() + line3.trim());
    }

    if (line2 != null && !line2.isBlank()) {
      for (String token : line2.trim().split("\\s+")) {
        if (!token.isBlank()) {
          candidates.add(token);
        }
      }
    }
    if (line3 != null && !line3.isBlank()) {
      for (String token : line3.trim().split("\\s+")) {
        if (!token.isBlank()) {
          candidates.add(token);
        }
      }
    }

    for (String raw : candidates) {
      String normalized = normalizeToken(raw);
      Optional<SignNodeDefinition> parsed = SignTextParser.parseWaypointLike(normalized, nodeType);
      if (parsed.isPresent()) {
        return parsed;
      }
    }
    return Optional.empty();
  }

  private static String normalizeToken(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }

    int start = 0;
    int end = trimmed.length();
    while (start < end && shouldTrim(trimmed.charAt(start))) {
      start++;
    }
    while (end > start && shouldTrim(trimmed.charAt(end - 1))) {
      end--;
    }
    return trimmed.substring(start, end).trim();
  }

  private static boolean shouldTrim(char c) {
    if (Character.isLetterOrDigit(c)) {
      return false;
    }
    return c != ':' && c != '_' && c != '-';
  }

  private static String safeLine(TrackedSign trackedSign, int index) {
    try {
      String value = trackedSign.getLine(index);
      return value != null ? value : "";
    } catch (IndexOutOfBoundsException ex) {
      return "";
    }
  }

  /**
   * 安全读取牌子文本行，兼容 TrainCarts FakeSign 的旧 API。
   *
   * <p>若 SignSide#line 不可用则回退到旧的 Sign#getLine（仅正面）。
   */
  private static String safeLine(Sign sign, Side side, int index) {
    if (sign == null) {
      return "";
    }
    try {
      SignSide view = sign.getSide(side);
      return PLAIN_TEXT.serialize(view.line(index));
    } catch (AbstractMethodError | NoSuchMethodError ex) {
      // 兼容 TrainCarts 的 FakeSign 仍使用旧 Sign API。
      if (side != Side.FRONT) {
        return "";
      }
      return safeLegacyLine(sign, index);
    } catch (IndexOutOfBoundsException ex) {
      return "";
    }
  }

  /** 旧版 Sign API 的安全读取。 */
  private static String safeLegacyLine(Sign sign, int index) {
    try {
      return sign.getLine(index);
    } catch (IndexOutOfBoundsException ex) {
      return "";
    }
  }
}
