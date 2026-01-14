package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;

/**
 * 运行时推进点监听：waypoint/autostation/depot/switcher 触发占用判定与下一跳下发。
 *
 * <p>对 MEMBER_ENTER 仅处理车头触发，避免多节车厢重复推进。
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
    String type = safeType(event);
    if (type.isEmpty()) {
      return;
    }
    Optional<SignNodeDefinition> definitionOpt = resolveDefinition(event, type);
    definitionOpt.ifPresent(def -> dispatchService.handleProgressTrigger(event, def));
  }

  private Optional<SignNodeDefinition> resolveDefinition(SignActionEvent event, String type) {
    if (type.equals("switcher")) {
      if (event.getTrackedSign() != null) {
        return SwitcherSignDefinitionParser.parse(event.getTrackedSign());
      }
      Sign sign = event.getSign();
      return SwitcherSignDefinitionParser.parse(sign);
    }
    if (event.getTrackedSign() != null) {
      return NodeSignDefinitionParser.parse(event.getTrackedSign());
    }
    return NodeSignDefinitionParser.parse(event.getSign());
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

  private String safeType(SignActionEvent event) {
    String line = event.getLine(1);
    if (line == null) {
      return "";
    }
    return line.trim().toLowerCase(Locale.ROOT);
  }
}
