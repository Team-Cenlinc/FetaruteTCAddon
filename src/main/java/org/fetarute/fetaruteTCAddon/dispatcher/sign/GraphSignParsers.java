package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.Optional;
import org.bukkit.block.Sign;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;

/** 建图时把一块牌子认成图节点：本插件的节点牌子、TrainCarts 的 switcher 牌子、MyWorlds 传送门牌子，依次尝试。 */
public final class GraphSignParsers {

  private static volatile boolean portalsEnabled;

  private GraphSignParsers() {}

  /** 跨世界开关（{@code graph.cross-world}）：关闭时传送门牌子不进图。配置加载时设置。 */
  public static void setPortalsEnabled(boolean enabled) {
    portalsEnabled = enabled;
  }

  public static boolean portalsEnabled() {
    return portalsEnabled;
  }

  /** 解析 TrainCarts 跟踪到的牌子。 */
  public static Optional<SignNodeDefinition> parse(TrackedSign tracked) {
    return NodeSignDefinitionParser.parse(tracked)
        .or(() -> SwitcherSignDefinitionParser.parse(tracked))
        .or(() -> portalsEnabled ? PortalSignDefinitionParser.parse(tracked) : Optional.empty());
  }

  /** 解析真实牌子。 */
  public static Optional<SignNodeDefinition> parse(Sign sign) {
    return NodeSignDefinitionParser.parse(sign)
        .or(() -> SwitcherSignDefinitionParser.parse(sign))
        .or(() -> portalsEnabled ? PortalSignDefinitionParser.parse(sign) : Optional.empty());
  }

  /** 道岔与传送门节点的坐标取自 ID 里的轨道方块；其余节点用牌子推得的位置。 */
  public static Optional<RailBlockPos> railPosOf(SignNodeDefinition definition) {
    if (definition.nodeType() == NodeType.SWITCHER) {
      return SwitcherSignDefinitionParser.tryParseRailPos(definition.nodeId());
    }
    if (definition.nodeType() == NodeType.PORTAL) {
      return PortalSignDefinitionParser.tryParseRailPos(definition.nodeId());
    }
    return Optional.empty();
  }
}
