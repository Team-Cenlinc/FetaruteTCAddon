package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * Waypoint 牌子（图节点）。
 *
 * <p>负责注册“区间点 + 咽喉”等纯图节点，供调度层/可达性诊断使用；不承载站台开关门/车库发车等行为语义。
 */
public final class WaypointSignAction extends AbstractNodeSignAction {

  public WaypointSignAction(
      SignNodeRegistry registry,
      Consumer<String> debugLogger,
      LocaleManager locale,
      SignNodeStorageSynchronizer storageSync) {
    super("waypoint", registry, NodeType.WAYPOINT, debugLogger, locale, storageSync);
  }

  public WaypointSignAction(
      SignNodeRegistry registry, Consumer<String> debugLogger, LocaleManager locale) {
    this(registry, debugLogger, locale, SignNodeStorageSynchronizer.noop());
  }

  @Override
  protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
    return super.parseDefinition(info)
        .filter(
            definition ->
                definition
                    .waypointMetadata()
                    .map(
                        metadata ->
                            metadata.kind() == WaypointKind.INTERVAL
                                || metadata.kind() == WaypointKind.STATION_THROAT
                                || metadata.kind() == WaypointKind.DEPOT_THROAT)
                    .orElse(false));
  }
}
