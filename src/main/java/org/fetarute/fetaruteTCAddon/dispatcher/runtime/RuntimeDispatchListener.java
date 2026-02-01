package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.GroupCreateEvent;
import com.bergerkiller.bukkit.tc.events.GroupLinkEvent;
import com.bergerkiller.bukkit.tc.events.GroupRemoveEvent;
import com.bergerkiller.bukkit.tc.events.GroupUnloadEvent;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.Optional;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;

/**
 * 运行时推进点监听：waypoint/depot/switcher 触发占用判定与下一跳下发。
 *
 * <p>对 MEMBER_ENTER 仅处理车头触发，避免多节车厢重复推进；Waypoint 停站仅在 GROUP_ENTER 触发，避免过早点刹。
 *
 * <p>AutoStation（STATION 类型）节点不由此监听器推进 routeIndex，而是由 {@link
 * RuntimeDispatchService#handleStationArrival} 在列车停稳后处理，避免列车跳过站点。
 *
 * <p>列车卸载/移除事件会主动释放占用，防止资源遗留。
 */
public final class RuntimeDispatchListener implements Listener {

  private final RuntimeDispatchService dispatchService;

  public RuntimeDispatchListener(RuntimeDispatchService dispatchService) {
    this.dispatchService = dispatchService;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSignAction(SignActionEvent event) {
    if (event == null) {
      return;
    }
    SignActionType action = event.getAction();
    if (action != SignActionType.GROUP_ENTER && action != SignActionType.MEMBER_ENTER) {
      return;
    }
    if (action == SignActionType.MEMBER_ENTER && !isHeadMember(event)) {
      return;
    }
    if (!event.hasGroup()) {
      return;
    }
    Optional<SignNodeDefinition> definitionOpt = resolveDefinition(event);
    if (definitionOpt.isEmpty()) {
      return;
    }
    SignNodeDefinition definition = definitionOpt.get();
    // Waypoint MEMBER_ENTER：仅更新 lastPassedGraphNode（用于 arriving 判定），不推进 routeIndex
    if (action == SignActionType.MEMBER_ENTER && definition.nodeType() == NodeType.WAYPOINT) {
      dispatchService.updateLastPassedGraphNode(event, definition);
      return;
    }
    // STATION 类型节点（AutoStation）不由此监听器推进 routeIndex：
    // 由 AutoStation.handleStop() -> handleStationArrival() 在列车停稳后处理，
    // 避免 handleProgressTrigger 提前推进导致列车跳过站点。
    if (definition.nodeType() == NodeType.STATION) {
      return;
    }
    dispatchService.handleProgressTrigger(event, definition);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGroupLink(GroupLinkEvent event) {
    if (event == null) {
      return;
    }
    refreshGroup(event.getGroup1());
    refreshGroup(event.getGroup2());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onGroupCreate(GroupCreateEvent event) {
    refreshGroup(event != null ? event.getGroup() : null);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onGroupUnload(GroupUnloadEvent event) {
    handleGroupRemoved(event != null ? event.getGroup() : null);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onGroupRemove(GroupRemoveEvent event) {
    handleGroupRemoved(event != null ? event.getGroup() : null);
  }

  private void handleGroupRemoved(MinecartGroup group) {
    if (group == null || group.getProperties() == null) {
      return;
    }
    String trainName = group.getProperties().getTrainName();
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    dispatchService.handleTrainRemoved(trainName);
  }

  private void refreshGroup(MinecartGroup group) {
    if (group == null || !group.isValid()) {
      return;
    }
    dispatchService.handleSignalTick(group);
  }

  private Optional<SignNodeDefinition> resolveDefinition(SignActionEvent event) {
    if (event == null) {
      return Optional.empty();
    }
    if (event.getTrackedSign() != null) {
      Optional<SignNodeDefinition> switcher =
          SwitcherSignDefinitionParser.parse(event.getTrackedSign());
      if (switcher.isPresent()) {
        return switcher;
      }
      return NodeSignDefinitionParser.parse(event.getTrackedSign());
    }
    Sign sign = event.getSign();
    Optional<SignNodeDefinition> switcher = SwitcherSignDefinitionParser.parse(sign);
    if (switcher.isPresent()) {
      return switcher;
    }
    return NodeSignDefinitionParser.parse(sign);
  }

  private boolean isHeadMember(SignActionEvent event) {
    if (!event.hasGroup()) {
      return false;
    }
    MinecartGroup group = event.getGroup();
    if (group == null) {
      return false;
    }
    MinecartMember<?> member = event.getMember();
    if (member == null) {
      return false;
    }
    return group.head() == member;
  }
}
