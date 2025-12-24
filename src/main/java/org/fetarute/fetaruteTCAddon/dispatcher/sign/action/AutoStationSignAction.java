package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/** 自动站台牌子，区别于 TC 原生 Station，专注调度层节点与 waypoint 接管。 */
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
