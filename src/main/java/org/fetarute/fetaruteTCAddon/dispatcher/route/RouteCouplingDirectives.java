package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;

/**
 * 交路备注里的加解编指令：解编 {@code DECP} 与联挂 {@code COPL}，附在停车的那一站下面，在该站停车时执行。纯计算。
 *
 * <ul>
 *   <li>{@code DECP FRONT|REAR:<节数>:GOTO:<交路>}：按运行方向从车头（FRONT）或车尾（REAR）切下这么多节，去跑同一交路组里从本站始发的那条交路；
 *       {@code …:IDLE} 就地成待命车；{@code …:RETURN} 回库。没被指定的部分继续本交路。
 *   <li>{@code COPL FROM:<交路>[:WAIT:<秒>]}：在本站与同一交路组里终到本站的那条交路的车联挂；不写 WAIT 取配置。
 * </ul>
 *
 * <p>交路只写同一交路组内的交路码（同一条线）。一站至多一条 DECP、一条 COPL。
 */
public final class RouteCouplingDirectives {

  /** 解编指令前缀。 */
  public static final String DECOUPLE = "DECP";

  /** 联挂指令前缀。 */
  public static final String COUPLE = "COPL";

  /** 没配 {@code spawn_group} 的交路所在的组（与编表一致）。 */
  public static final String DEFAULT_GROUP = "default";

  /** 解编、联挂那一站多停的秒数的缺省值（{@code coupler.decouple-seconds} / {@code couple-seconds}）。 */
  public static final int DEFAULT_COUPLING_SECONDS = 30;

  private static volatile int decoupleSeconds = DEFAULT_COUPLING_SECONDS;

  private static volatile int coupleSeconds = DEFAULT_COUPLING_SECONDS;

  /**
   * 解编、联挂那一站多停的秒数：编表、到站预测与现场停站都读这里，配置加载时设置。改了要重新编表。
   *
   * @param decouple 解编加时
   * @param couple 联挂加时
   */
  public static void configureDwell(int decouple, int couple) {
    decoupleSeconds = Math.max(0, decouple);
    coupleSeconds = Math.max(0, couple);
  }

  /** 解编加时。 */
  public static int decoupleSeconds() {
    return decoupleSeconds;
  }

  /** 联挂加时。 */
  public static int coupleSeconds() {
    return coupleSeconds;
  }

  /** 各停靠点因加解编多停的秒数：写了 DECP 的站加解编加时，写了 COPL 的站加联挂加时（不停车的站不加）。与换模加时一样，编表、到站预测与现场停站同一口径。 */
  public static int[] extraDwellPlan(List<RouteStop> stops) {
    int[] out = new int[stops == null ? 0 : stops.size()];
    for (int i = 0; i < out.length; i++) {
      RouteStop stop = stops.get(i);
      if (stop == null || stop.passType() == RouteStopPassType.PASS) {
        continue;
      }
      if (decouple(stop).isPresent()) {
        out[i] += decoupleSeconds;
      }
      if (couple(stop).isPresent()) {
        out[i] += coupleSeconds;
      }
    }
    return out;
  }

  private RouteCouplingDirectives() {}

  /** 从哪一头切（按运行方向）。 */
  public enum End {
    FRONT,
    REAR
  }

  /** 切下的部分去哪。 */
  public enum Action {
    /** 去跑同组的另一条交路。 */
    GOTO,
    /** 就地成待命车。 */
    IDLE,
    /** 回库。 */
    RETURN
  }

  /**
   * 解编。
   *
   * @param end 从哪一头切
   * @param cars 切下几节
   * @param action 切下的部分去哪
   * @param target {@link Action#GOTO} 的交路码；其余为空
   */
  public record Decouple(End end, int cars, Action action, Optional<String> target) {

    public Decouple {
      Objects.requireNonNull(end, "end");
      Objects.requireNonNull(action, "action");
      target = target == null ? Optional.empty() : target;
    }
  }

  /**
   * 联挂。
   *
   * @param from 终到本站、挂上来的那条交路的交路码
   * @param waitSeconds 最多等多少秒；不写时为空（取配置）
   */
  public record Couple(String from, OptionalInt waitSeconds) {

    public Couple {
      Objects.requireNonNull(from, "from");
      waitSeconds = waitSeconds == null ? OptionalInt.empty() : waitSeconds;
    }
  }

  /**
   * 一次解析的结果：成功时 {@code value} 有值，否则 {@code error} 是语言键末段。
   *
   * @param value 解析出的指令
   * @param error 写错时的原因（语言键末段）
   */
  public record Parsed<T>(Optional<T> value, Optional<String> error) {

    static <T> Parsed<T> ok(T value) {
      return new Parsed<>(Optional.of(value), Optional.empty());
    }

    static <T> Parsed<T> fail(String error) {
      return new Parsed<>(Optional.empty(), Optional.of(error));
    }
  }

  /** 这一站写的 DECP 原文（前缀之后）；没写时为空。 */
  public static Optional<String> rawDecouple(RouteStop stop) {
    return mentions(stop, DECOUPLE) ? RouteStopDirectives.target(stop, DECOUPLE) : Optional.empty();
  }

  /** 这一站写的 COPL 原文（前缀之后）；没写时为空。 */
  public static Optional<String> rawCouple(RouteStop stop) {
    return mentions(stop, COUPLE) ? RouteStopDirectives.target(stop, COUPLE) : Optional.empty();
  }

  /** 这一站的解编；没写或写错时为空。 */
  public static Optional<Decouple> decouple(RouteStop stop) {
    return rawDecouple(stop).flatMap(raw -> parseDecouple(raw).value());
  }

  /** 这一站的联挂；没写或写错时为空。 */
  public static Optional<Couple> couple(RouteStop stop) {
    return rawCouple(stop).flatMap(raw -> parseCouple(raw).value());
  }

  /** 备注里有没有这个字样：先粗查再按指令解析，绝大多数停靠点不用走正则。 */
  private static boolean mentions(RouteStop stop, String prefix) {
    return stop != null
        && stop.notes()
            .filter(notes -> notes.toUpperCase(Locale.ROOT).contains(prefix))
            .isPresent();
  }

  /** 解析 DECP 的参数：{@code FRONT|REAR:<n>:GOTO:<交路>|IDLE|RETURN}。 */
  public static Parsed<Decouple> parseDecouple(String raw) {
    String[] parts = split(raw);
    if (parts.length < 3) {
      return Parsed.fail("decouple-syntax");
    }
    End end;
    try {
      end = End.valueOf(parts[0].toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return Parsed.fail("decouple-end");
    }
    int cars;
    try {
      cars = Integer.parseInt(parts[1]);
    } catch (NumberFormatException ex) {
      return Parsed.fail("decouple-cars");
    }
    if (cars <= 0) {
      return Parsed.fail("decouple-cars");
    }
    Action action;
    try {
      action = Action.valueOf(parts[2].toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return Parsed.fail("decouple-action");
    }
    if (action == Action.GOTO) {
      if (parts.length != 4 || parts[3].isBlank()) {
        return Parsed.fail("decouple-goto-missing");
      }
      return Parsed.ok(new Decouple(end, cars, action, Optional.of(parts[3])));
    }
    if (parts.length != 3) {
      return Parsed.fail("decouple-syntax");
    }
    return Parsed.ok(new Decouple(end, cars, action, Optional.empty()));
  }

  /** 解析 COPL 的参数：{@code FROM:<交路>[:WAIT:<秒>]}。 */
  public static Parsed<Couple> parseCouple(String raw) {
    String[] parts = split(raw);
    if (parts.length < 2 || !"FROM".equalsIgnoreCase(parts[0]) || parts[1].isBlank()) {
      return Parsed.fail("couple-syntax");
    }
    if (parts.length == 2) {
      return Parsed.ok(new Couple(parts[1], OptionalInt.empty()));
    }
    if (parts.length != 4 || !"WAIT".equalsIgnoreCase(parts[2])) {
      return Parsed.fail("couple-syntax");
    }
    try {
      int wait = Integer.parseInt(parts[3]);
      if (wait < 0) {
        return Parsed.fail("couple-wait");
      }
      return Parsed.ok(new Couple(parts[1], OptionalInt.of(wait)));
    } catch (NumberFormatException ex) {
      return Parsed.fail("couple-wait");
    }
  }

  private static String[] split(String raw) {
    if (raw == null || raw.isBlank()) {
      return new String[0];
    }
    String[] parts = raw.trim().split(":", -1);
    for (int i = 0; i < parts.length; i++) {
      parts[i] = parts[i].trim();
    }
    return parts;
  }

  // ---------------------------------------------------------------- 校验

  /**
   * 一条交路的校验问题。
   *
   * @param key 语言键末段（{@code command.route.define.<key>}）
   * @param params 占位符
   */
  public record Issue(String key, Map<String, String> params) {

    public Issue {
      Objects.requireNonNull(key, "key");
      params = Map.copyOf(params);
    }
  }

  /**
   * 只看本交路就能判的问题：写法、写在哪一站。
   *
   * <ul>
   *   <li>写法认不出；
   *   <li>写在首站、末站或不停车（PASS）的站上；
   *   <li>一站写了两条 DECP 或两条 COPL（只认第一条，第二条报错）；
   *   <li>切车头就地待命（{@code FRONT:n:IDLE}）：解下的车头停在站台前端，本交路往前开就被它挡死。
   * </ul>
   */
  public static List<Issue> validateLocal(List<RouteStop> stops) {
    List<Issue> issues = new ArrayList<>();
    if (stops == null) {
      return issues;
    }
    for (int i = 0; i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      String seq = String.valueOf(stop.sequence());
      Optional<String> decouple = rawDecouple(stop);
      Optional<String> couple = rawCouple(stop);
      if (decouple.isEmpty() && couple.isEmpty()) {
        continue;
      }
      decouple
          .flatMap(raw -> parseDecouple(raw).error())
          .ifPresent(
              error -> issues.add(new Issue(error, Map.of("seq", seq, "value", decouple.get()))));
      couple
          .flatMap(raw -> parseCouple(raw).error())
          .ifPresent(
              error -> issues.add(new Issue(error, Map.of("seq", seq, "value", couple.get()))));
      if (i == 0 || i == stops.size() - 1) {
        issues.add(new Issue("coupling-at-end", Map.of("seq", seq)));
      } else if (stop.passType() == RouteStopPassType.PASS) {
        issues.add(new Issue("coupling-at-pass", Map.of("seq", seq)));
      }
      if (count(stop, DECOUPLE) > 1 || count(stop, COUPLE) > 1) {
        issues.add(new Issue("coupling-duplicate", Map.of("seq", seq)));
      }
      decouple(stop)
          .filter(parsed -> parsed.end() == End.FRONT && parsed.action() == Action.IDLE)
          .ifPresent(parsed -> issues.add(new Issue("decouple-front-idle", Map.of("seq", seq))));
    }
    return issues;
  }

  /** 这一站备注里写了几条这个指令。 */
  private static int count(RouteStop stop, String prefix) {
    int count = 0;
    for (String line : stop.notes().orElse("").replace('\r', '\n').split("\n", -1)) {
      String trimmed = line.trim().toUpperCase(Locale.ROOT);
      if (trimmed.equals(prefix)
          || trimmed.startsWith(prefix + " ")
          || trimmed.startsWith(prefix + ":")) {
        count++;
      }
    }
    return count;
  }

  /**
   * 一条交路的概况，供跨交路校验。
   *
   * @param code 交路码
   * @param group 交路组（metadata {@code spawn_group}，没配为 {@link #DEFAULT_GROUP}）
   * @param stops 停靠表
   */
  public record RouteRef(String code, String group, List<RouteStop> stops) {

    public RouteRef {
      Objects.requireNonNull(code, "code");
      group = group == null || group.isBlank() ? DEFAULT_GROUP : group.trim();
      stops = List.copyOf(stops);
    }
  }

  /** metadata 里的交路组名。 */
  public static String groupOf(Map<String, Object> metadata) {
    Object raw = metadata == null ? null : metadata.get("spawn_group");
    String name = raw == null ? "" : String.valueOf(raw).trim();
    return name.isEmpty() ? DEFAULT_GROUP : name;
  }

  /**
   * 跨交路的问题：GOTO / FROM 指向的交路要在同一条线、同一交路组；GOTO 的交路首站是解编站，FROM 的交路末站是联挂站。
   *
   * @param self 本交路
   * @param lineRoutes 同一条线上的全部交路（含本交路）
   */
  public static List<Issue> validateTargets(RouteRef self, List<RouteRef> lineRoutes) {
    List<Issue> issues = new ArrayList<>();
    if (self == null || lineRoutes == null) {
      return issues;
    }
    for (RouteStop stop : self.stops()) {
      String seq = String.valueOf(stop.sequence());
      decouple(stop)
          .flatMap(Decouple::target)
          .ifPresent(
              code ->
                  checkTarget(self, stop, code, lineRoutes, true)
                      .ifPresent(
                          key -> issues.add(new Issue(key, Map.of("seq", seq, "route", code)))));
      couple(stop)
          .ifPresent(
              couple ->
                  checkTarget(self, stop, couple.from(), lineRoutes, false)
                      .ifPresent(
                          key ->
                              issues.add(
                                  new Issue(key, Map.of("seq", seq, "route", couple.from())))));
    }
    return issues;
  }

  private static Optional<String> checkTarget(
      RouteRef self, RouteStop at, String code, List<RouteRef> lineRoutes, boolean decouple) {
    Optional<RouteRef> target =
        lineRoutes.stream().filter(route -> route.code().equalsIgnoreCase(code)).findFirst();
    if (target.isEmpty()) {
      return Optional.of("coupling-target-missing");
    }
    if (target.get().code().equalsIgnoreCase(self.code())) {
      return Optional.of("coupling-target-self");
    }
    if (!target.get().group().equalsIgnoreCase(self.group())) {
      return Optional.of("coupling-target-group");
    }
    List<RouteStop> stops = target.get().stops();
    if (stops.isEmpty()) {
      return Optional.of("coupling-target-missing");
    }
    RouteStop end = decouple ? stops.get(0) : stops.get(stops.size() - 1);
    if (!sameStation(at, end)) {
      return Optional.of(decouple ? "decouple-target-start" : "couple-target-end");
    }
    return Optional.empty();
  }

  /**
   * 两个停靠点是不是同一站：都写了车站时比车站；否则按路径点节点比站（同站不同站台算同一站，与待命车复用 {@link TerminalKeyResolver#matches} 同一口径）。
   */
  static boolean sameStation(RouteStop first, RouteStop second) {
    if (first.stationId().isPresent() && second.stationId().isPresent()) {
      return first.stationId().get().equals(second.stationId().get());
    }
    return first.waypointNodeId().isPresent()
        && second.waypointNodeId().isPresent()
        && TerminalKeyResolver.matches(first.waypointNodeId().get(), second.waypointNodeId().get());
  }
}
