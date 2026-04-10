package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.GroupCreateEvent;
import com.bergerkiller.bukkit.tc.events.GroupLinkEvent;
import com.bergerkiller.bukkit.tc.events.GroupRemoveEvent;
import com.bergerkiller.bukkit.tc.events.GroupUnloadEvent;
import com.bergerkiller.bukkit.tc.events.MemberRemoveEvent;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
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

  /**
   * 编组拆分/脱挂兜底：一旦检测到 member 从编组移除，删除涉及的所有列车，避免“半编组”继续参与调度。
   *
   * <p>TrainCarts 没有专门的 split 事件，MemberRemoveEvent 是最稳定的异常编组信号。
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onMemberRemove(MemberRemoveEvent event) {
    if (event == null) {
      return;
    }
    MinecartMember<?> member = event.getMember();
    MinecartGroup sourceGroup = event.getGroup();
    MinecartGroup detachedGroup = member != null ? member.getGroup() : null;
    String splitDetail = buildUnexpectedSplitDetail(sourceGroup, detachedGroup, member);
    dispatchService.handleAbnormalGroup(sourceGroup, "unexpected-split-source", splitDetail);

    if (member == null) {
      return;
    }
    if (detachedGroup != null && detachedGroup != sourceGroup) {
      dispatchService.handleAbnormalGroup(detachedGroup, "unexpected-split-detached", splitDetail);
    }
  }

  private void handleGroupRemoved(MinecartGroup group) {
    if (group == null || group.getProperties() == null) {
      return;
    }
    String trainName = dispatchService.resolveManagedTrainName(group.getProperties()).orElse(null);
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

  private String buildUnexpectedSplitDetail(
      MinecartGroup sourceGroup, MinecartGroup detachedGroup, MinecartMember<?> member) {
    StringBuilder builder = new StringBuilder();
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "removedMemberPos", describeMember(member));
    RuntimeDiagnosticFormatter.appendKeyValue(
        builder, "sourceTrain", describeGroupName(sourceGroup));
    if (detachedGroup != null && detachedGroup != sourceGroup) {
      RuntimeDiagnosticFormatter.appendKeyValue(
          builder, "detachedTrain", describeGroupName(detachedGroup));
    }
    return builder.length() == 0 ? null : builder.toString();
  }

  private String describeGroupName(MinecartGroup group) {
    if (group == null) {
      return null;
    }
    TrainProperties properties = group.getProperties();
    if (properties == null) {
      return null;
    }
    return dispatchService
        .resolveTrackedTrainName(properties)
        .orElseGet(
            () -> {
              String rawName = properties.getTrainName();
              return rawName == null || rawName.isBlank() ? null : rawName.trim();
            });
  }

  private static String describeMember(MinecartMember<?> member) {
    if (member == null) {
      return null;
    }
    org.bukkit.block.Block block = member.getBlock(0, 0, 0);
    return block != null ? RuntimeDiagnosticFormatter.formatLocation(block.getLocation()) : null;
  }
}
