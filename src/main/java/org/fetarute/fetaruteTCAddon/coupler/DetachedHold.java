package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;

/**
 * 解编解下、在站台待命的车的标记 {@code FTA_DECP_HOLD=<交路 UUID>|<路径点下标>|<站台节点>}。
 *
 * <p>解下的车照搬了主干的交路与进度（停在主干交路的中途站）。待命登记只在内存里：重启后没了登记，调度会把它当成停站的车、接着跑主干的交路。
 * 信号检查见到这个标记、车还停在记下的那一站时就把它重新登记成待命车（每辆车每个进程至多一次）。车被派上别的交路（交路或下标变了）后标记作废，见到就删。
 */
public final class DetachedHold {

  /** 标签键。 */
  public static final String TAG = "FTA_DECP_HOLD";

  private DetachedHold() {}

  /**
   * @param routeId 车停着时的交路
   * @param index 车停着时的路径点下标
   * @param node 站台节点
   */
  record Hold(UUID routeId, int index, NodeId node) {

    String format() {
      return routeId + "|" + index + "|" + node.value();
    }

    static Optional<Hold> parse(String raw) {
      if (raw == null) {
        return Optional.empty();
      }
      String[] parts = raw.split("\\|", 3);
      if (parts.length != 3 || parts[2].isBlank()) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            new Hold(
                UUID.fromString(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                NodeId.of(parts[2].trim())));
      } catch (IllegalArgumentException ex) {
        return Optional.empty();
      }
    }
  }

  /**
   * 本进程里已经登记过待命的解下的车（车名键）：重新登记只为重启后找回，同一进程里每辆车至多一次——派车时先撤待命登记、后改交路标签，
   * 中间的信号检查若又按旧标记登记回去，就把刚派出的车扣回待命池。
   */
  private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

  /** 给解下的车写上标记（这辆车本进程已登记待命，不再按标记重新登记）。 */
  static void mark(
      TrainProperties properties, String trainName, UUID routeId, int index, NodeId node) {
    TrainTagHelper.writeTag(properties, TAG, new Hold(routeId, index, node).format());
    REGISTERED.add(key(trainName));
  }

  private static String key(String trainName) {
    return trainName.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 信号检查里调用（车不在待命池时）：带着标记、还停在记下的那一站的车重新登记成待命车。
   *
   * @return 重新登记了
   */
  public static boolean restore(
      TrainProperties properties, String trainName, LayoverRegistry registry, Instant now) {
    if (properties == null || trainName == null || registry == null) {
      return false;
    }
    Optional<String> raw = TrainTagHelper.readTagValue(properties, TAG);
    if (raw.isEmpty() || REGISTERED.contains(key(trainName))) {
      return false;
    }
    Optional<Hold> hold = Hold.parse(raw.get());
    boolean current =
        hold.isPresent()
            && TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
                .filter(id -> id.trim().equalsIgnoreCase(hold.get().routeId().toString()))
                .isPresent()
            && TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
                .filter(index -> index == hold.get().index())
                .isPresent();
    if (!current) {
      TrainTagHelper.removeTagKey(properties, TAG);
      return false;
    }
    MinecartGroup group = holder(properties);
    if (group == null || group.isMoving()) {
      return false;
    }
    registry.register(
        trainName,
        TerminalKeyResolver.toTerminalKey(hold.get().node()),
        hold.get().node(),
        now == null ? Instant.now() : now,
        TrainLinker.trainLevelTags(properties));
    REGISTERED.add(key(trainName));
    return true;
  }

  private static MinecartGroup holder(TrainProperties properties) {
    try {
      return properties.getHolder();
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
