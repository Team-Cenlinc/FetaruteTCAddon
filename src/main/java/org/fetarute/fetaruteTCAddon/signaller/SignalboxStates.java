package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerPlatformDesk;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 由一座信号楼此刻的状态拼出控制台的一帧：列车表、按钮可用与否、入口信号、预显示、可改停的股道。只读，不改信号楼。
 *
 * <p>列车表的范围：在区内（有区内占用，或最近经过的节点在区内）、正接近（下一个途经点在区内）、在入口等、有许可或被扣着的车。 晚点直接读时刻表已有的数，不在这里算 ETA。
 */
final class SignalboxStates {

  /** 列车表最多给几行（布局画几行由布局定）。 */
  static final int MAX_ROWS = 12;

  private final SignallerService service;
  private final SignalBox box;
  private final ZoneMap map;
  private final Instant now;

  SignalboxStates(SignallerService service, SignalBox box, ZoneMap map, Instant now) {
    this.service = service;
    this.box = box;
    this.map = map;
    this.now = now;
  }

  SignalboxState build() {
    boolean staffed = box.duty != null;
    List<Row> rows = rows();
    Optional<Row> selected =
        box.selected == null
            ? Optional.empty()
            : rows.stream().filter(row -> row.key().equals(box.selected)).findFirst();
    Optional<RouteAhead> selectedAhead =
        selected.isPresent() && map != null
            ? service.ahead(map, selected.get().row().trainName())
            : Optional.empty();
    Set<EdgeId> preview = new LinkedHashSet<>();
    selectedAhead.ifPresent(
        ahead -> {
          ahead.entry().ifPresent(preview::add);
          preview.addAll(ahead.zoneEdges());
        });
    Set<EdgeId> pending = new LinkedHashSet<>();
    if (map != null) {
      for (Map.Entry<String, SignalBox.Permit> entry : box.permits.entrySet()) {
        if (entry.getValue().state != SignalBox.PermitState.PENDING) {
          continue;
        }
        service
            .ahead(map, entry.getValue().trainName)
            .ifPresent(
                ahead -> {
                  ahead.entry().ifPresent(pending::add);
                  pending.addAll(ahead.zoneEdges());
                });
      }
    }
    preview.removeAll(pending);
    Optional<SignalboxState.Call> call =
        staffed && !box.calls.isEmpty()
            ? Optional.of(
                new SignalboxState.Call(
                    box.calls.peekFirst().trainName(), box.calls.peekFirst().driverName()))
            : Optional.empty();
    Optional<SignalboxState.Notice> notice =
        box.notice != null && now.isBefore(box.notice.until())
            ? Optional.of(new SignalboxState.Notice(box.notice.kind(), box.notice.train()))
            : Optional.empty();
    return new SignalboxState(
        box.manual() ? SignalboxState.Mode.MANUAL : SignalboxState.Mode.AUTO,
        staffed ? Optional.of(box.duty.playerName) : Optional.empty(),
        selected.map(row -> row.row().trainName()),
        rows.stream().limit(MAX_ROWS).map(Row::row).toList(),
        buttons(selected, selectedAhead),
        call,
        staffed && box.replying && !box.calls.isEmpty(),
        notice,
        preview,
        pending,
        signals(),
        selected.map(row -> platforms(row.row().trainName())).orElse(List.of()));
  }

  /** 列车表一行，连同它的键与排序用的量。 */
  record Row(String key, SignalboxState.TrainRow row, int order, Instant since) {}

  private List<Row> rows() {
    if (map == null) {
      return List.of();
    }
    SignallerWorld world = service.world();
    Map<String, RouteProgressRegistry.RouteProgressEntry> progress = world.progress();
    Map<String, RouteProgressRegistry.RouteProgressEntry> byKey = new HashMap<>();
    for (RouteProgressRegistry.RouteProgressEntry entry : progress.values()) {
      byKey.put(SignallerService.key(entry.trainName()), entry);
    }
    Set<String> keys = new LinkedHashSet<>();
    for (Map.Entry<String, RouteProgressRegistry.RouteProgressEntry> entry : byKey.entrySet()) {
      RouteProgressRegistry.RouteProgressEntry value = entry.getValue();
      if (value.lastPassedGraphNode().filter(map::inside).isPresent()
          || value.nextTarget().filter(map::inside).isPresent()
          || service.presence(box, entry.getKey()).isPresent()) {
        keys.add(entry.getKey());
      }
    }
    for (Map.Entry<String, SignalBox.Waiting> entry : box.waiting.entrySet()) {
      if (!service.stale(entry.getValue(), now)) {
        keys.add(entry.getKey());
      }
    }
    keys.addAll(box.permits.keySet());
    keys.addAll(box.holds.keySet());
    RailGraph graph = map.graph();
    SignallerConfig settings = service.settings();
    List<Row> rows = new ArrayList<>();
    for (String key : keys) {
      RouteProgressRegistry.RouteProgressEntry entry = byKey.get(key);
      String name = nameOf(key, entry);
      SignallerWorld.TrainInfo info = world.info(name);
      Optional<String> destination =
          entry == null || entry.routeUuid() == null
              ? Optional.empty()
              : world
                  .route(entry.routeUuid())
                  .flatMap((RouteDefinition route) -> RouteAhead.destination(graph, route));
      Status status = status(key, entry, name, settings);
      SignalBox.Permit permit = box.permits.get(key);
      boolean autoPass =
          (permit != null && permit.source == SignalBox.Source.AUTO_PASS)
              || Optional.ofNullable(box.waiting.get(key))
                  .flatMap(waiting -> waiting.route)
                  .filter(box.autoPasses::contains)
                  .isPresent();
      rows.add(
          new Row(
              key,
              new SignalboxState.TrainRow(
                  name,
                  info.label(),
                  info.lineColor(),
                  destination,
                  status.status(),
                  status.seconds(),
                  info.delaySeconds(),
                  status.progress(),
                  autoPass),
              order(status.status()),
              status.since()));
    }
    rows.sort(
        Comparator.comparingInt(Row::order)
            .thenComparing(Row::since)
            .thenComparing(row -> row.row().label()));
    return rows;
  }

  /** 列车名：有进度时取进度里的名字，否则取等待、许可或扣车记下的名字。 */
  private String nameOf(String key, RouteProgressRegistry.RouteProgressEntry entry) {
    if (entry != null) {
      return entry.trainName();
    }
    SignalBox.Waiting waiting = box.waiting.get(key);
    if (waiting != null) {
      return waiting.trainName;
    }
    SignalBox.Permit permit = box.permits.get(key);
    if (permit != null) {
      return permit.trainName;
    }
    SignalBox.Hold hold = box.holds.get(key);
    return hold != null ? hold.trainName() : key;
  }

  private record Status(
      SignalboxState.RowStatus status, long seconds, double progress, Instant since) {}

  private Status status(
      String key,
      RouteProgressRegistry.RouteProgressEntry entry,
      String name,
      SignallerConfig settings) {
    SignalBox.Hold hold = box.holds.get(key);
    if (hold != null) {
      return new Status(SignalboxState.RowStatus.HELD, seconds(hold.since()), 0.0, hold.since());
    }
    SignalBox.Permit permit = box.permits.get(key);
    SignalBox.Waiting waiting = box.waiting.get(key);
    if (permit == null && waiting != null && !service.stale(waiting, now) && box.manual()) {
      double progress =
          settings.autoRouteSeconds() <= 0
              ? 0.0
              : Math.min(
                  1.0,
                  Duration.between(waiting.since, now).toMillis()
                      / (settings.autoRouteSeconds() * 1000.0));
      return new Status(
          SignalboxState.RowStatus.WAITING, seconds(waiting.since), progress, waiting.since);
    }
    // 车身在不在区内按最近经过的节点认：进路锁闭的车也有区内占用，但人还在入口外
    boolean inside = entry != null && entry.lastPassedGraphNode().filter(map::inside).isPresent();
    boolean dwelling = inside && service.world().dwelling(name);
    if (permit != null && permit.state == SignalBox.PermitState.PENDING) {
      return new Status(SignalboxState.RowStatus.ROUTE_PENDING, 0, 0.0, permit.granted);
    }
    if (permit != null && !inside) {
      return new Status(SignalboxState.RowStatus.ROUTE_SET, 0, 0.0, permit.granted);
    }
    if (dwelling) {
      return new Status(SignalboxState.RowStatus.DWELLING, 0, 0.0, Instant.MAX);
    }
    if (inside || service.presence(box, key).isPresent()) {
      return new Status(SignalboxState.RowStatus.IN_ZONE, 0, 0.0, Instant.MAX);
    }
    return new Status(SignalboxState.RowStatus.APPROACHING, 0, 0.0, Instant.MAX);
  }

  private long seconds(Instant since) {
    return Math.max(0L, Duration.between(since, now).getSeconds());
  }

  private static int order(SignalboxState.RowStatus status) {
    return switch (status) {
      case WAITING -> 0;
      case ROUTE_PENDING -> 1;
      case APPROACHING -> 2;
      case ROUTE_SET -> 3;
      case HELD -> 4;
      case DWELLING -> 5;
      case IN_ZONE -> 6;
    };
  }

  private Map<SignalboxState.Button, Boolean> buttons(
      Optional<Row> selected, Optional<RouteAhead> ahead) {
    Map<SignalboxState.Button, Boolean> buttons = new EnumMap<>(SignalboxState.Button.class);
    for (SignalboxState.Button button : SignalboxState.Button.values()) {
      buttons.put(button, false);
    }
    if (box.duty == null) {
      return buttons;
    }
    buttons.put(SignalboxState.Button.MODE, true);
    if (selected.isEmpty()) {
      return buttons;
    }
    String key = selected.get().key();
    SignalboxState.RowStatus status = selected.get().row().status();
    SignalBox.Permit permit = box.permits.get(key);
    boolean inZone =
        status == SignalboxState.RowStatus.IN_ZONE
            || status == SignalboxState.RowStatus.DWELLING
            || status == SignalboxState.RowStatus.HELD;
    buttons.put(SignalboxState.Button.SET_ROUTE, box.manual() && permit == null && !inZone);
    buttons.put(
        SignalboxState.Button.CANCEL_ROUTE,
        permit != null && permit.state == SignalBox.PermitState.PENDING);
    buttons.put(
        SignalboxState.Button.HOLD,
        status == SignalboxState.RowStatus.DWELLING && !box.holds.containsKey(key));
    buttons.put(SignalboxState.Button.RELEASE, box.holds.containsKey(key));
    boolean routeKnown =
        (permit != null && permit.route.isPresent())
            || Optional.ofNullable(box.waiting.get(key)).flatMap(w -> w.route).isPresent()
            || ahead.flatMap(RouteAhead::entry).isPresent();
    buttons.put(SignalboxState.Button.AUTO_PASS, box.manual() && routeKnown);
    return buttons;
  }

  private List<SignalboxState.EntrySignal> signals() {
    if (map == null) {
      return List.of();
    }
    List<SignalboxState.EntrySignal> out = new ArrayList<>();
    // 最近经过的节点已在区内的车：锁闭许可的车进了区，入口就不再亮绿
    Set<String> insideKeys = new HashSet<>();
    for (RouteProgressRegistry.RouteProgressEntry entry : service.world().progress().values()) {
      if (entry.lastPassedGraphNode().filter(map::inside).isPresent()) {
        insideKeys.add(SignallerService.key(entry.trainName()));
      }
    }
    List<EdgeId> exits = new ArrayList<>(map.exits().keySet());
    exits.sort(Comparator.naturalOrder());
    Set<EdgeId> autoPassEntries = new HashSet<>();
    for (SignalBox.Route route : box.autoPasses) {
      autoPassEntries.add(route.entry());
    }
    for (EdgeId exit : exits) {
      Optional<SignalBox.Waiting> waiting =
          box.waiting.values().stream()
              .filter(w -> !service.stale(w, now))
              .filter(w -> w.route.map(r -> r.entry().equals(exit)).orElse(false))
              .filter(w -> !box.permits.containsKey(SignallerService.key(w.trainName)))
              .min(Comparator.comparing(w -> w.since));
      SignalboxState.Aspect aspect;
      if (!box.manual()) {
        aspect = SignalboxState.Aspect.PROCEED;
      } else if (permitAt(exit, SignalBox.PermitState.PENDING, insideKeys)) {
        aspect = SignalboxState.Aspect.PENDING;
      } else if (waiting.isPresent()) {
        aspect = SignalboxState.Aspect.STOP;
      } else if (permitAt(exit, SignalBox.PermitState.LOCKED, insideKeys)) {
        aspect = SignalboxState.Aspect.PROCEED;
      } else {
        aspect = SignalboxState.Aspect.STOP;
      }
      out.add(
          new SignalboxState.EntrySignal(
              exit, aspect, waiting.map(w -> w.trainName), autoPassEntries.contains(exit)));
    }
    return out;
  }

  /** 这个入口有没有这种状态的许可（锁闭的只算车还没进区的）。 */
  private boolean permitAt(EdgeId exit, SignalBox.PermitState state, Set<String> insideKeys) {
    for (Map.Entry<String, SignalBox.Permit> entry : box.permits.entrySet()) {
      SignalBox.Permit permit = entry.getValue();
      if (permit.state != state || permit.route.map(r -> !r.entry().equals(exit)).orElse(true)) {
        continue;
      }
      if (state == SignalBox.PermitState.PENDING || !insideKeys.contains(entry.getKey())) {
        return true;
      }
    }
    return false;
  }

  private List<SignalboxState.PlatformChoice> platforms(String trainName) {
    if (map == null) {
      return List.of();
    }
    Optional<SignallerPlatformDesk.Options> options;
    try {
      options = service.world().platforms().options(trainName);
    } catch (RuntimeException ex) {
      return List.of();
    }
    if (options.isEmpty() || options.get().candidates().size() < 2) {
      return List.of();
    }
    List<SignalboxState.PlatformChoice> out = new ArrayList<>();
    Optional<NodeId> current = options.get().current();
    for (NodeId node : options.get().candidates()) {
      YardArea.Track track = map.tracks().get(node);
      if (track == null) {
        // 候选股道不全在本控制区里：不在这块控制台上改
        return List.of();
      }
      out.add(
          new SignalboxState.PlatformChoice(
              node, track.number(), current.map(node::equals).orElse(false)));
    }
    return out;
  }
}
