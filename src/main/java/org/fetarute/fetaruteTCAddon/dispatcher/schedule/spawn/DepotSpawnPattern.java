package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/**
 * 车库出车编组（TrainCarts spawn pattern）的来源：交路 metadata 的 {@value #ROUTE_METADATA_KEY} 优先，其次车库牌子第 4 行。
 *
 * <p>自动出库、手动出库与未发车 ETA 的车种推断共用同一套读法，避免三处口径分叉。
 */
public final class DepotSpawnPattern {

  /** 交路 metadata 中覆盖车库牌子编组的键。 */
  public static final String ROUTE_METADATA_KEY = "spawn_train_pattern";

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private DepotSpawnPattern() {}

  /** 交路 metadata 里写明的编组；未写或为空白时为空。 */
  public static Optional<String> fromRoute(Route route) {
    if (route == null) {
      return Optional.empty();
    }
    return route.metadata().get(ROUTE_METADATA_KEY) instanceof String raw
        ? normalize(raw)
        : Optional.empty();
  }

  /** 车库牌子第 4 行的编组，正面优先、其次背面；不是 {@code [train]/[cart] depot} 牌子时为空。 */
  public static Optional<String> fromSign(Sign sign) {
    if (sign == null) {
      return Optional.empty();
    }
    return fromSide(sign.getSide(Side.FRONT)).or(() -> fromSide(sign.getSide(Side.BACK)));
  }

  /**
   * 只在车库牌子所在区块已加载时读取编组，不为此加载区块。
   *
   * <p>估算类调用（ETA、站台屏）在主线程高频执行，同步加载区块会直接卡服；区块未加载时返回 {@link SignRead#unloaded()}，由调用方沿用上次读到的结果。
   *
   * @param registry 牌子注册表
   * @param depotId 车库节点
   */
  public static SignRead readLoaded(SignNodeRegistry registry, NodeId depotId) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(depotId, "depotId");
    Optional<SignNodeRegistry.SignNodeInfo> found =
        registry
            .findByNodeId(depotId, null)
            .filter(info -> info.definition().nodeType() == NodeType.DEPOT);
    if (found.isEmpty()) {
      return SignRead.missing();
    }
    SignNodeRegistry.SignNodeInfo info = found.get();
    World world = Bukkit.getWorld(info.worldId());
    if (world == null || !world.isChunkLoaded(info.x() >> 4, info.z() >> 4)) {
      return SignRead.unloaded();
    }
    return world.getBlockAt(info.x(), info.y(), info.z()).getState() instanceof Sign sign
        ? SignRead.loaded(fromSign(sign))
        : SignRead.missing();
  }

  private static Optional<String> fromSide(SignSide side) {
    SignActionHeader header = SignActionHeader.parse(PLAIN_TEXT.serialize(side.line(0)).trim());
    if (header == null || (!header.isTrain() && !header.isCart())) {
      return Optional.empty();
    }
    String type = PLAIN_TEXT.serialize(side.line(1)).trim().toLowerCase(Locale.ROOT);
    return "depot".equals(type) ? normalize(PLAIN_TEXT.serialize(side.line(3))) : Optional.empty();
  }

  private static Optional<String> normalize(String raw) {
    String trimmed = raw == null ? "" : raw.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  /**
   * 一次牌子读取的结果。
   *
   * @param loaded 牌子所在区块是否已加载；为 false 时 {@code pattern} 没有意义，表示“这次没读”
   * @param pattern 读到的编组；区块已加载但没有车库牌子或第 4 行为空时为空
   */
  public record SignRead(boolean loaded, Optional<String> pattern) {

    public SignRead {
      Objects.requireNonNull(pattern, "pattern");
    }

    /** 区块已加载，按牌子内容给出结果。 */
    public static SignRead loaded(Optional<String> pattern) {
      return new SignRead(true, pattern);
    }

    /** 车库未注册或位置上不是牌子：确定没有编组。 */
    public static SignRead missing() {
      return new SignRead(true, Optional.empty());
    }

    /** 区块未加载，这次没读。 */
    public static SignRead unloaded() {
      return new SignRead(false, Optional.empty());
    }
  }
}
