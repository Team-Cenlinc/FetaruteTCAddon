package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;

/**
 * 运营 route 在 metadata 里显式指定的出库/回库走行线路（直通运转用）。
 *
 * <p>直通 route 的终点落在别的 operator 甚至别的 company 的车站上，本 operator 名下没有从那里回库的 RETURN 线路， 编表会把班次以
 * NO_RETURN_ACCESS 取消。于是允许运营 route 在 metadata 里写明"从外方的这条线路出库/回库"： 键 {@link #KEY_CREATE_ROUTE} 与
 * {@link #KEY_RETURN_ROUTE}，值形如 {@code <company>/<operator>/<line>/<route>}，各段都是 code。
 *
 * <p>被引用的线路必须本身就是对应类型（CREATE/RETURN），否则编表时报"类型不符"；它还必须是可发车服务，否则运行时发不出票。 这里只做字符串层面的解析，不碰存储。
 */
public final class TimetableRouteMetadata {

  /** 运营 route 指定的出库走行线路。 */
  public static final String KEY_CREATE_ROUTE = "timetable_create_route";

  /** 运营 route 指定的回库走行线路。 */
  public static final String KEY_RETURN_ROUTE = "timetable_return_route";

  private TimetableRouteMetadata() {}

  /** 一条走行线路的四段 code 引用；各段只去空白，大小写按 code 存储原样——仓储按 code 精确匹配，不能替用户改写。 */
  public record RouteRef(String company, String operator, String line, String route) {
    public RouteRef {
      company = normalize(company);
      operator = normalize(operator);
      line = normalize(line);
      route = normalize(route);
      if (company.isEmpty() || operator.isEmpty() || line.isEmpty() || route.isEmpty()) {
        throw new IllegalArgumentException("走行线路引用四段都不能为空");
      }
    }

    /** 存进 metadata 的形态。 */
    public String format() {
      return company + "/" + operator + "/" + line + "/" + route;
    }

    private static String normalize(String raw) {
      return raw == null ? "" : raw.trim();
    }
  }

  /** 解析 {@code <company>/<operator>/<line>/<route>}；段数不对或有空段时返回空。 */
  public static Optional<RouteRef> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String[] parts = raw.trim().split("/", -1);
    if (parts.length != 4) {
      return Optional.empty();
    }
    for (String part : parts) {
      if (part == null || part.isBlank()) {
        return Optional.empty();
      }
    }
    return Optional.of(new RouteRef(parts[0], parts[1], parts[2], parts[3]));
  }

  /** 读 metadata 里某个键的走行线路引用；键不存在、不是字符串或格式不对都返回空。 */
  public static Optional<RouteRef> read(Map<String, Object> metadata, String key) {
    if (metadata == null || key == null) {
      return Optional.empty();
    }
    Object raw = metadata.get(key);
    return raw instanceof String text ? parse(text) : Optional.empty();
  }

  /** 键对应的走行线路类型：出库键对应 CREATE、回库键对应 RETURN。 */
  public static RouteOperationType typeOf(String key) {
    Objects.requireNonNull(key, "key");
    return switch (key) {
      case KEY_CREATE_ROUTE -> RouteOperationType.CREATE;
      case KEY_RETURN_ROUTE -> RouteOperationType.RETURN;
      default -> throw new IllegalArgumentException("不是走行线路键: " + key);
    };
  }
}
