package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopMarkIndex;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 停车位置标牌子：{@code [train]} / {@code stopmark} / {@code carriage:4}。
 *
 * <p>牌子本身不对经过的列车做任何事：车站停站时沿股道找到它，让对应节数的列车车头停在这里（见 {@link StopMarkIndex}）。 建牌时只检查写法并清空缓存。
 */
public final class StopMarkSignAction extends SignAction {

  /** 建停车位置标的权限：标志会改变经过这里的所有自动运行列车的停车位置。 */
  public static final String PERMISSION = "fetarute.sign.stopmark";

  private final StopMarkIndex index;
  private final LocaleManager locale;

  public StopMarkSignAction(StopMarkIndex index, LocaleManager locale) {
    this.index = Objects.requireNonNull(index, "index");
    this.locale = locale;
  }

  @Override
  public boolean match(SignActionEvent info) {
    return info.isType(StopMarkSign.TYPE);
  }

  @Override
  public void execute(SignActionEvent info) {
    // 停车位置由车站停站时读取，列车经过时什么也不做。
  }

  @Override
  public boolean build(SignChangeActionEvent event) {
    if (!event.isTrainSign() && !event.isCartSign()) {
      return false;
    }
    if (event.getPlayer() != null && !event.getPlayer().hasPermission(PERMISSION)) {
      if (locale != null) {
        event.getPlayer().sendMessage(locale.component("sign.stopmark.no-permission"));
      }
      return false;
    }
    index.invalidate();
    Optional<StopMarkSign> sign = StopMarkSign.parse(event.getLine(2), event.getLine(3));
    if (event.getPlayer() != null && locale != null) {
      event
          .getPlayer()
          .sendMessage(
              sign.map(
                      parsed ->
                          locale.component(
                              "sign.stopmark.created", Map.of("carriages", parsed.describe())))
                  .orElseGet(() -> locale.component("sign.stopmark.invalid")));
    }
    return true;
  }

  @Override
  public void destroy(SignActionEvent info) {
    index.invalidate();
  }
}
