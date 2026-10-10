package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 叫来的车按需降速：平时全速，追近前车才压线路限速，最低七成；后车追近时不降；不是叫来的车不降。 */
class CalledTrainPacerTest {

  private static final double DELTA = 1.0e-9;
  private static final Instant NOW = Instant.now();

  @Test
  void factorFollowsTheGapToTheTrainAhead() {
    assertEquals(
        1.0, CalledTrainPacer.factorFor(OptionalLong.of(600), 8.0, 60), DELTA, "75 秒，够远不降");
    assertEquals(
        1.0, CalledTrainPacer.factorFor(OptionalLong.of(480), 8.0, 60), DELTA, "正好 60 秒不降");
    assertEquals(0.8, CalledTrainPacer.factorFor(OptionalLong.of(384), 8.0, 60), DELTA, "48 秒");
    assertEquals(
        CalledTrainPacer.MIN_FACTOR,
        CalledTrainPacer.factorFor(OptionalLong.of(100), 8.0, 60),
        DELTA,
        "再近也只降到七成，余下交给信号");
    assertEquals(1.0, CalledTrainPacer.factorFor(OptionalLong.empty(), 8.0, 60), DELTA, "前方没有阻塞");
    assertEquals(
        1.0, CalledTrainPacer.factorFor(OptionalLong.of(10), 0.5, 60), DELTA, "停着、刚起步时不按车速折算");
    assertEquals(
        1.0, CalledTrainPacer.factorFor(OptionalLong.of(10), 8.0, 0), DELTA, "跟车间隔为 0 时不降速");
  }

  @Test
  void onlyCalledTrainsArePaced() {
    CalledTrainPacer pacer = pacer();

    pacer.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(240), 8.0, NOW);
    pacer.observe("plain-1", properties(), none(), OptionalLong.of(240), 8.0, NOW);

    assertEquals(0.7, pacer.factor("called-1"), DELTA);
    assertEquals(1.0, pacer.factor("plain-1"), DELTA, "表定与按间隔发的车不受影响");

    pacer.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.empty(), 8.0, NOW);
    assertEquals(1.0, pacer.factor("called-1"), DELTA, "前车走远、前瞻没有阻塞就恢复全速");
  }

  /** 后车被叫来的车挡着、折算间隔不到 min-lead：叫来的车不降速，免得把后车也拖慢。 */
  @Test
  void aCloseTrainBehindKeepsTheCalledTrainAtFullSpeed() {
    CalledTrainPacer pacer = pacer();

    pacer.observe("follower", properties(), blockedBy("called-1"), OptionalLong.of(400), 8.0, NOW);
    pacer.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(240), 8.0, NOW);
    assertEquals(1.0, pacer.factor("called-1"), DELTA, "后车 50 秒之内：不降");

    Instant later = NOW.plus(CalledTrainPacer.REAR_HOLD).plusSeconds(1);
    pacer.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(240), 8.0, later);
    assertEquals(0.7, pacer.factor("called-1"), DELTA, "后车的观测过期后照常降");

    CalledTrainPacer far = pacer();
    far.observe("follower", properties(), blockedBy("called-1"), OptionalLong.of(2000), 8.0, NOW);
    far.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(240), 8.0, NOW);
    assertEquals(0.7, far.factor("called-1"), DELTA, "后车 250 秒开外：照常降");
  }

  /** 前瞻窗口外的后车：按到站预计判出身后有车追近时同样不降；判定出错时当作追近。 */
  @Test
  void aForecastCloseTrainBehindAlsoKeepsFullSpeed() {
    CalledTrainPacer pacer = pacer();
    pacer.setRearCheck(name -> name.equals("called-1"));
    pacer.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(240), 8.0, NOW);
    assertEquals(1.0, pacer.factor("called-1"), DELTA);

    CalledTrainPacer failing = pacer();
    failing.setRearCheck(
        name -> {
          throw new IllegalStateException("boom");
        });
    failing.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(240), 8.0, NOW);
    assertEquals(1.0, failing.factor("called-1"), DELTA);
  }

  /** 倍率过期按协调器的时钟判，不按墙钟。 */
  @Test
  void factorExpiresOnTheInjectedClock() {
    Instant[] clock = {Instant.parse("2020-01-01T00:00:00Z")};
    CalledTrainPacer pacer = new CalledTrainPacer(message -> {}, () -> clock[0]);
    pacer.setSettings(new CalledTrainPacer.Settings(60, 120));
    pacer.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(240), 8.0, clock[0]);
    assertEquals(0.7, pacer.factor("called-1"), DELTA, "注入时钟远早于墙钟，也不算过期");

    clock[0] = clock[0].plus(CalledTrainPacer.FACTOR_TTL).plusSeconds(1);
    assertEquals(1.0, pacer.factor("called-1"), DELTA, "注入时钟过了有效期才作废");
  }

  @Test
  void disabledSettingsNeverSlow() {
    CalledTrainPacer pacer = new CalledTrainPacer(message -> {}, () -> NOW);
    pacer.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(100), 8.0, NOW);
    assertEquals(1.0, pacer.factor("called-1"), DELTA);

    CalledTrainPacer enabled = pacer();
    enabled.observe(
        "called-1", properties("FTA_CALL=x@SURC:PPK"), none(), OptionalLong.of(100), 8.0, NOW);
    enabled.setSettings(null);
    assertEquals(1.0, enabled.factor("called-1"), DELTA, "关掉后立即恢复");
  }

  private static CalledTrainPacer pacer() {
    CalledTrainPacer pacer = new CalledTrainPacer(message -> {}, () -> NOW);
    pacer.setSettings(new CalledTrainPacer.Settings(60, 120));
    return pacer;
  }

  private static OccupancyDecision none() {
    return new OccupancyDecision(true, NOW, SignalAspect.PROCEED, List.of());
  }

  private static OccupancyDecision blockedBy(String trainName) {
    return new OccupancyDecision(
        false,
        NOW,
        SignalAspect.STOP,
        List.of(
            new OccupancyClaim(
                OccupancyResource.forNode(NodeId.of("OP:A:B:1:001")),
                trainName,
                Optional.empty(),
                NOW,
                Duration.ZERO,
                Optional.empty())));
  }

  private static TrainProperties properties(String... tags) {
    TrainProperties properties = mock(TrainProperties.class);
    List<String> list = new ArrayList<>(List.of(tags));
    when(properties.hasTags()).thenReturn(!list.isEmpty());
    when(properties.getTags()).thenReturn(list);
    return properties;
  }
}
