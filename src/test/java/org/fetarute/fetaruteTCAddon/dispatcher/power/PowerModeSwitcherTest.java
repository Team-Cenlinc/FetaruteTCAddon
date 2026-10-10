package org.fetarute.fetaruteTCAddon.dispatcher.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RoutePowerModes;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainPower;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * 多模车按交路备注 POWER 换模。
 *
 * <p>夹具交路：A（首站，POWER ptg5）→ B → W1（不停车，POWER ptg6）→ C → W2（不停车，POWER diesel）→ D → E（POWER ptg6）→
 * F（终到）。
 */
class PowerModeSwitcherTest {

  private final UUID routeUuid = UUID.randomUUID();
  private final RouteId routeId = new RouteId("SURC:WS:WS-X");
  private final RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
  private final List<String> logs = new ArrayList<>();
  private final PowerModeSwitcher switcher =
      new PowerModeSwitcher(() -> Optional.of(cache), () -> PowerSupply.PTG5, logs::add);

  PowerModeSwitcherTest() {
    List<RouteStop> stops =
        List.of(
            stop(0, false, "ptg5"),
            stop(1, false, null),
            stop(2, true, "ptg6"),
            stop(3, false, null),
            stop(4, true, "diesel"),
            stop(5, false, null),
            stop(6, false, "ptg6"),
            stop(7, false, null));
    when(cache.listStops(routeId)).thenReturn(stops);
    RouteDefinition definition =
        new RouteDefinition(
            routeId, List.of(NodeId.of("SURC:S:A:1"), NodeId.of("SURC:S:F:1")), Optional.empty());
    when(cache.findById(routeUuid)).thenReturn(Optional.of(definition));
  }

  private RouteStop stop(int sequence, boolean pass, String power) {
    return new RouteStop(
        routeUuid,
        sequence,
        Optional.empty(),
        Optional.of("SURC:S:N" + sequence + ":1"),
        Optional.empty(),
        pass ? RouteStopPassType.PASS : RouteStopPassType.STOP,
        power == null ? Optional.empty() : Optional.of("POWER:" + power));
  }

  /** 带标签的列车：标签读写落在一个列表上。 */
  static TrainProperties train(String name, String... tags) {
    List<String> backing = new ArrayList<>(List.of(tags));
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(name);
    when(properties.hasTags()).thenAnswer(invocation -> !backing.isEmpty());
    when(properties.getTags()).thenAnswer(invocation -> List.copyOf(backing));
    doAnswer(
            invocation -> {
              for (Object argument : invocation.getArguments()) {
                if (argument instanceof String[] many) {
                  backing.addAll(List.of(many));
                } else if (argument instanceof String one) {
                  backing.add(one);
                }
              }
              return null;
            })
        .when(properties)
        .addTags(any(String[].class));
    doAnswer(
            invocation -> {
              for (Object argument : invocation.getArguments()) {
                if (argument instanceof String[] many) {
                  backing.removeAll(List.of(many));
                } else if (argument instanceof String one) {
                  backing.remove(one);
                }
              }
              return null;
            })
        .when(properties)
        .removeTags(any(String[].class));
    return properties;
  }

  private static TrainProperties multiMode(String name) {
    return train(name, TrainConfigResolver.TAG_TRAIN_POWER + "=ptg5,ptg6,diesel");
  }

  private int pass(TrainProperties properties, int index, boolean dwelling) {
    return switcher.onRouteNode(properties.getTrainName(), properties, routeId, index, dwelling);
  }

  @Test
  void electricSwitchesHappenOnTheMoveAndDieselWaitsForAStop() {
    TrainProperties properties = multiMode("ws-1");

    assertEquals(0, pass(properties, 1, true));
    assertEquals(PowerSupply.PTG5, TrainPower.of(properties, PowerSupply.PTG5));

    assertEquals(0, pass(properties, 2, false), "电对电经过即换，不多停");
    assertEquals(PowerSupply.PTG6, TrainPower.of(properties, PowerSupply.PTG5));

    assertEquals(0, pass(properties, 4, false), "涉及内燃，不停车的点上不换");
    assertEquals(PowerSupply.PTG6, TrainPower.of(properties, PowerSupply.PTG5));

    assertEquals(RoutePowerModes.DIESEL_SWITCH_SECONDS, pass(properties, 5, true));
    assertEquals(PowerSupply.DIESEL, TrainPower.of(properties, PowerSupply.PTG5));
    assertEquals(25, switcher.takeStationDwell("WS-1"), "车站停站取走多停的秒数");
    assertEquals(0, switcher.takeStationDwell("ws-1"), "只取一次");

    assertEquals(25, pass(properties, 6, true), "从内燃换回电力同样停站换");
    assertEquals(PowerSupply.PTG6, TrainPower.of(properties, PowerSupply.PTG5));
    assertTrue(logs.stream().anyMatch(line -> line.startsWith("POWER_MODE_SWITCH train=ws-1")));
  }

  @Test
  void aModeTheTrainDoesNotHaveIsIgnored() {
    TrainProperties properties = train("ws-2", TrainConfigResolver.TAG_TRAIN_POWER + "=ptg5,ptg6");
    pass(properties, 3, true);

    assertEquals(0, pass(properties, 5, true));
    assertEquals(0, pass(properties, 5, true));

    assertEquals(PowerSupply.PTG6, TrainPower.of(properties, PowerSupply.PTG5), "照旧用 ptg6");
    assertEquals(
        1,
        logs.stream().filter(line -> line.contains("reason=unsupported:diesel")).count(),
        "同车同原因只记一次");
  }

  @Test
  void singleModeTrainsAreLeftAlone() {
    TrainProperties properties = train("ws-3", TrainConfigResolver.TAG_TRAIN_POWER + "=ptg5");

    assertEquals(0, pass(properties, 5, true));

    assertTrue(TrainPower.recordedActive(properties).isEmpty());
    assertTrue(logs.isEmpty());
  }

  /** 手动驾驶：simulation 级由驾驶员换，本类只把这一段要求的模式交给会话；standard 级照常换并通知会话。 */
  @Test
  void manualDrivingDecidesWhoSwitches() {
    List<String> heard = new ArrayList<>();
    boolean[] simulation = {true};
    switcher.setDriverControl(
        new PowerModeSwitcher.DriverControl() {
          @Override
          public boolean driven(String trainName) {
            return true;
          }

          @Override
          public boolean driverSwitches(String trainName, PowerSupply sectionMode) {
            heard.add("section:" + sectionMode.key());
            return simulation[0];
          }

          @Override
          public void switched(String trainName, PowerSupply from, PowerSupply to) {
            heard.add("switched:" + from.key() + ">" + to.key());
          }
        });
    TrainProperties properties = multiMode("ws-4");

    pass(properties, 3, true);
    assertEquals(PowerSupply.PTG5, TrainPower.of(properties, PowerSupply.PTG5), "驾驶员还没换");
    assertEquals(List.of("section:ptg6"), heard);

    simulation[0] = false;
    pass(properties, 3, true);
    assertEquals(PowerSupply.PTG6, TrainPower.of(properties, PowerSupply.PTG5));
    assertEquals(List.of("section:ptg6", "section:ptg6", "switched:ptg5>ptg6"), heard);
  }

  /** 换模那一站要多停的秒数：同一节点的重复到达事件不清掉它，车开到别的节点才作废。 */
  @Test
  void stationDwellSurvivesRepeatedArrivalsAtTheSameNode() {
    TrainProperties properties = multiMode("ws-6");
    pass(properties, 3, true);

    assertEquals(25, pass(properties, 5, true));
    assertEquals(0, pass(properties, 5, true), "已经是内燃，这一次不再换");
    assertEquals(25, switcher.takeStationDwell("ws-6"), "重复的到达事件没把它清掉");

    assertEquals(25, pass(properties, 6, true));
    assertEquals(0, pass(properties, 7, true));
    assertEquals(0, switcher.takeStationDwell("ws-6"), "车开到别的节点，上一站的秒数作废");
  }

  /** 有人驾驶时每个节点都把这一段的要求交给会话：交路还没写 POWER、或车上没有这种方式时交 null。 */
  @Test
  void theDriverSessionIsToldTheSectionModeAtEveryNode() {
    List<String> heard = new ArrayList<>();
    switcher.setDriverControl(
        new PowerModeSwitcher.DriverControl() {
          @Override
          public boolean driven(String trainName) {
            return true;
          }

          @Override
          public boolean driverSwitches(String trainName, PowerSupply sectionMode) {
            heard.add(sectionMode == null ? "none" : sectionMode.key());
            return false;
          }

          @Override
          public void switched(String trainName, PowerSupply from, PowerSupply to) {}
        });
    RouteId plain = new RouteId("SURC:WS:WS-PLAIN");
    when(cache.listStops(plain)).thenReturn(List.of(stop(0, false, null), stop(1, false, null)));
    TrainProperties twoModes = train("ws-7", TrainConfigResolver.TAG_TRAIN_POWER + "=ptg5,ptg6");

    switcher.onRouteNode("ws-7", twoModes, plain, 1, true);
    pass(twoModes, 5, true);
    pass(twoModes, 6, true);

    assertEquals(List.of("none", "none", "ptg6"), heard, "没写 POWER 与车上没有 diesel 时都交 null");
  }

  /** 出车或接续：按交路起点应当用的模式换好（站内，内燃也能换）。 */
  @Test
  void dispatchSetsTheModeOfTheRouteStart() {
    TrainProperties properties = multiMode("ws-5");
    TrainPower.setActive(properties, PowerSupply.DIESEL);
    List<Long> later = new ArrayList<>();
    switcher.setLaterRunner((ticks, task) -> later.add(ticks));
    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.exists("ws-5")).thenReturn(true);
      store.when(() -> TrainPropertiesStore.get("ws-5")).thenReturn(properties);

      switcher.onDispatched(ticket(), "ws-5");
    }

    assertEquals(PowerSupply.PTG5, TrainPower.of(properties, PowerSupply.PTG5));
    assertEquals(List.of(20L), later, "刚生成的车附件多半没挂载：稍后再按当前模式补一次升降弓");
  }

  private SpawnTicket ticket() {
    Instant now = Instant.parse("2026-10-10T12:00:00Z");
    return new SpawnTicket(
        UUID.randomUUID(),
        new SpawnService(
            new SpawnServiceKey(routeUuid),
            UUID.randomUUID(),
            "SURC",
            UUID.randomUUID(),
            "SURC",
            UUID.randomUUID(),
            "WS",
            routeUuid,
            "WS-X",
            Duration.ofMinutes(10),
            "SURC:D:DEP:1"),
        now,
        now,
        now,
        0,
        0L,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        TripSource.SCHEDULED,
        0,
        Optional.empty());
  }
}
