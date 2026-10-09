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
import org.fetarute.fetaruteTCAddon.drive.guard.GuardLink;

/**
 * 哪些调度列车由驾驶员控制，哪些车上有车掌。
 *
 * <p>以列车属性对象的身份为准（改名、同一编组过门都不受影响）；属性对象被换掉时，按车名找到绑定、再核对列车标签里的驾驶员一致后重新挂上。
 * 标签存在但这里没有绑定的车一律按自动运行处理。车掌只在内存里登记（不写标签），属性对象被换掉时按车名找回。只在服务器主线程调用。
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
  private final Map<TrainProperties, GuardLink> guardsByProperties = new IdentityHashMap<>();
  private final Map<String, GuardLink> guardsByName = new HashMap<>();
  private Handler handler = NO_HANDLER;
  private long atoConfirmTicks = 300L;
  private java.util.function.Predicate<String> awaitingDriver = trainName -> false;

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

  /** 让车掌上岗：这列车的车门归他。 */
  public void bindGuard(TrainProperties properties, GuardLink guard) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(guard, "guard");
    guard.rebind(properties);
    guardsByProperties.put(properties, guard);
    guardsByName.put(guard.trainName(), guard);
  }

  /** 车掌离岗。 */
  public void unbindGuard(GuardLink guard) {
    if (guard == null) {
      return;
    }
    guardsByProperties.values().removeIf(existing -> existing == guard);
    guardsByName.remove(guard.trainName(), guard);
  }

  /** 这列车上的车掌；没有时为空。 */
  public Optional<GuardLink> guardOf(TrainProperties properties) {
    return Optional.ofNullable(resolveGuard(properties));
  }

  /** 按车名找车掌（调度改名后按列车属性上的当前车名也认）。 */
  public Optional<GuardLink> guardOfName(String trainName) {
    if (trainName == null || guardsByName.isEmpty()) {
      return Optional.empty();
    }
    GuardLink guard = guardsByName.get(trainName);
    if (guard != null) {
      return Optional.of(guard);
    }
    for (GuardLink candidate : guardsByName.values()) {
      TrainProperties properties = candidate.properties();
      if (properties != null && trainName.equals(properties.getTrainName())) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private GuardLink resolveGuard(TrainProperties properties) {
    if (properties == null || guardsByName.isEmpty()) {
      return null;
    }
    GuardLink guard = guardsByProperties.get(properties);
    if (guard != null) {
      return guard;
    }
    GuardLink found = guardOfName(properties.getTrainName()).orElse(null);
    if (found == null) {
      return null;
    }
    // 属性对象被换掉了（例如区块重载）：按车名找回后重新挂上。
    guardsByProperties.values().removeIf(existing -> existing == found);
    guardsByProperties.put(properties, found);
    found.rebind(properties);
    return found;
  }

  @Override
  public boolean guardOperatesDoors(TrainProperties properties) {
    return resolveGuard(properties) != null;
  }

  @Override
  public boolean holdForGuard(TrainProperties properties, boolean exitOpen) {
    GuardLink guard = resolveGuard(properties);
    return guard != null && guard.holdDeparture(exitOpen);
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
    // 车掌扣着：拉下了紧急停车（车停住后不替它起步，直到车掌解除或到时限），或终点站折返等车掌换到车尾端。
    GuardLink guard = resolveGuard(properties);
    if (guard != null && guard.holdsTrain()) {
      return true;
    }
    if (link != null) {
      return link.controlsPhysically() || link.cabHold();
    }
    // 停着等驾驶员上车接班的车：调度照常给许可，但任何发车路径都不替它起步；驾驶员上车后由他开走，
    // 等到时限由驾驶侧放行并强制刷新一次信号，交回自动运行。
    return properties != null && awaitingDriver(properties.getTrainName());
  }

  /** 人工驾驶的驾驶员亲手开关车门；车上有车掌时车门归车掌。 */
  @Override
  public boolean driverOperatesDoors(TrainProperties properties) {
    DriverLink link = resolve(properties);
    return link != null && link.controlsPhysically() && resolveGuard(properties) == null;
  }

  @Override
  public boolean isDriverControlledName(String trainName) {
    DriverLink link = byCurrentName(trainName);
    return (link != null && (link.controlsPhysically() || link.cabHold()))
        || guardOfName(trainName).map(GuardLink::holdsTrain).orElse(false);
  }

  @Override
  public boolean hasDriver(String trainName) {
    return byCurrentName(trainName) != null;
  }

  @Override
  public boolean awaitingTurnback(String trainName) {
    DriverLink link = byCurrentName(trainName);
    if (link != null) {
      return link.turnbackPending();
    }
    return guardOfName(trainName).map(GuardLink::turnbackPending).orElse(false);
  }

  /** 按车名找链路；调度改名（例如终点待命复用）后按列车属性上的当前车名也认。 */
  private DriverLink byCurrentName(String trainName) {
    if (trainName == null || byName.isEmpty()) {
      return null;
    }
    DriverLink link = byName.get(trainName);
    if (link != null) {
      return link;
    }
    for (DriverLink candidate : byName.values()) {
      TrainProperties properties = candidate.properties();
      if (properties != null && trainName.equals(properties.getTrainName())) {
        return candidate;
      }
    }
    return null;
  }

  @Override
  public boolean holdDeparture(TrainProperties properties) {
    DriverLink link = resolve(properties);
    return link != null && link.holdDeparture(atoConfirmTicks);
  }

  /** 哪些列车正停着等驾驶员接班（按车名）。 */
  public void setAwaitingDriver(java.util.function.Predicate<String> awaiting) {
    this.awaitingDriver = awaiting == null ? trainName -> false : awaiting;
  }

  @Override
  public boolean takeTurnback(TrainProperties properties) {
    DriverLink link = resolve(properties);
    if (link != null) {
      return link.takeTurnback();
    }
    // 只有车掌的列车：放行那一拍同样只调头，等车掌换到车尾端再交回自动运行发车。
    GuardLink guard = resolveGuard(properties);
    return guard != null && guard.takeTurnback();
  }

  @Override
  public boolean awaitingDriver(String trainName) {
    return trainName != null && awaitingDriver.test(trainName);
  }

  /** ATO 下等驾驶员确认发车的上限（tick）。 */
  public void setAtoConfirmTicks(long ticks) {
    this.atoConfirmTicks = Math.max(0L, ticks);
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
  public void stationStopStarted(TrainProperties properties) {
    DriverLink link = resolve(properties);
    if (link != null) {
      link.stationStopStarted();
    }
  }

  /** 站台交出停站：交给控车的驾驶员（ATO 驾驶员不接，停站与车门由站台或车掌负责）与车上的车掌。 */
  @Override
  public void beginStationStop(TrainProperties properties, DriverStationStop stop) {
    if (stop == null) {
      return;
    }
    DriverLink link = resolve(properties);
    if (link != null && (link.controlsPhysically() || link.cabHold())) {
      link.beginStationStop(stop);
    }
    GuardLink guard = resolveGuard(properties);
    if (guard != null) {
      guard.beginStationStop(stop);
    }
  }

  @Override
  public void requestHandback(String trainName, String reason) {
    DriverLink link = byCurrentName(trainName);
    if (link != null) {
      handler.onHandbackRequested(link, reason);
    }
  }
}
