package org.fetarute.fetaruteTCAddon.drive.driver;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DriverControlTags;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverInterrupt;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;

/**
 * 哪些调度列车由驾驶员控制。
 *
 * <p>以列车属性对象的身份为准（改名、同一编组过门都不受影响）；属性对象被换掉时，按车名找到绑定、再核对列车标签里的驾驶员一致后重新挂上。
 * 标签存在但这里没有绑定的车一律按自动运行处理。只在服务器主线程调用。
 */
public final class DriverControlRegistry implements ControlAuthority {

  /** 调度层的停车、销毁要求与交还请求由驾驶会话处理。 */
  public interface Handler {
    void onInterrupt(DriverLink link, DriverInterrupt interrupt);

    void onHandbackRequested(DriverLink link, String reason);
  }

  private static final Handler NO_HANDLER =
      new Handler() {
        @Override
        public void onInterrupt(DriverLink link, DriverInterrupt interrupt) {}

        @Override
        public void onHandbackRequested(DriverLink link, String reason) {}
      };

  private final Map<TrainProperties, DriverLink> byProperties = new IdentityHashMap<>();
  private final Map<String, DriverLink> byName = new HashMap<>();
  private Handler handler = NO_HANDLER;

  public void setHandler(Handler handler) {
    this.handler = handler == null ? NO_HANDLER : handler;
  }

  /** 把列车交给驾驶员，并写入驾驶员标签。 */
  public void bind(TrainProperties properties, DriverLink link) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(link, "link");
    link.rebind(properties);
    byProperties.put(properties, link);
    byName.put(link.trainName(), link);
    DriverControlTags.write(properties, link.playerId());
  }

  /** 解除绑定并清掉驾驶员标签。 */
  public void unbind(DriverLink link) {
    if (link == null) {
      return;
    }
    byProperties.values().removeIf(existing -> existing == link);
    byName.remove(link.trainName(), link);
    TrainProperties properties = link.properties();
    if (properties != null) {
      DriverControlTags.clear(properties);
    }
  }

  /** 这列车的控制链路；没有绑定时为空。 */
  public Optional<DriverLink> linkOf(TrainProperties properties) {
    return Optional.ofNullable(resolve(properties));
  }

  /** 全部控制链路。 */
  public List<DriverLink> links() {
    return new ArrayList<>(byName.values());
  }

  public boolean isEmpty() {
    return byName.isEmpty();
  }

  private DriverLink resolve(TrainProperties properties) {
    if (properties == null || byName.isEmpty()) {
      return null;
    }
    DriverLink link = byProperties.get(properties);
    if (link != null) {
      return link;
    }
    String name = properties.getTrainName();
    link = name == null ? null : byName.get(name);
    if (link == null) {
      return null;
    }
    DriverLink found = link;
    if (DriverControlTags.driver(properties).filter(found.playerId()::equals).isEmpty()) {
      return null;
    }
    // 属性对象被换掉了（例如区块重载）：按车名与驾驶员标签确认后重新挂上。
    byProperties.values().removeIf(existing -> existing == found);
    byProperties.put(properties, found);
    found.rebind(properties);
    return found;
  }

  @Override
  public boolean isDriverControlled(TrainProperties properties) {
    DriverLink link = resolve(properties);
    return link != null && link.controlsPhysically();
  }

  @Override
  public boolean isDriverControlledName(String trainName) {
    return trainName != null && byName.containsKey(trainName);
  }

  @Override
  public void publish(TrainProperties properties, DriverDirective directive) {
    DriverLink link = resolve(properties);
    if (link != null && directive != null) {
      link.acceptDirective(directive);
    }
  }

  @Override
  public void interrupt(TrainProperties properties, DriverInterrupt interrupt) {
    DriverLink link = resolve(properties);
    if (link != null && interrupt != null) {
      handler.onInterrupt(link, interrupt);
    }
  }

  @Override
  public void beginStationStop(TrainProperties properties, DriverStationStop stop) {
    DriverLink link = resolve(properties);
    if (link != null && stop != null) {
      link.beginStationStop(stop);
    }
  }

  @Override
  public void requestHandback(String trainName, String reason) {
    DriverLink link = trainName == null ? null : byName.get(trainName);
    if (link != null) {
      handler.onHandbackRequested(link, reason);
    }
  }
}
