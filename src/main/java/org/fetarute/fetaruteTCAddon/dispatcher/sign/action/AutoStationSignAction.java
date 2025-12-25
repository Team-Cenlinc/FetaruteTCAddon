package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * AutoStation 牌子（站点行为节点）。
 *
 * <p>用于承载“停站/开关门/站台行为”等语义，因此只接受站点本体（4 段 {@code Operator:S:Station:Track}）。
 * 站咽喉属于图节点（Waypoint）职责，不应使用 AutoStation 牌子注册。
 */
public final class AutoStationSignAction extends AbstractNodeSignAction {

  public AutoStationSignAction(
      SignNodeRegistry registry, Consumer<String> debugLogger, LocaleManager locale) {
    super("autostation", registry, NodeType.STATION, debugLogger, locale);
  }

  @Override
  protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
    return super.parseDefinition(info)
        .filter(
            definition ->
                definition
                    .waypointMetadata()
                    .map(metadata -> metadata.kind() == WaypointKind.STATION)
                    .orElse(false));
  }
}
