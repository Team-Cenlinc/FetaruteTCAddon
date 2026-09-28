package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.junit.jupiter.api.Test;

class RuntimeDispatchApproachSpeedTest {

  @Test
  void approachPreviewRatioStartsBeforeConfiguredWindow() {
    double windowBlocks = 96.0;
    double previewBlocks = RuntimeDispatchService.APPROACH_PREVIEW_DISTANCE_BLOCKS;

    assertEquals(
        0.0, RuntimeTrainController.approachPreviewRatio(windowBlocks, previewBlocks, 160));
    assertTrue(RuntimeTrainController.approachPreviewRatio(windowBlocks, previewBlocks, 140) > 0.0);
    assertEquals(1.0, RuntimeTrainController.approachPreviewRatio(windowBlocks, previewBlocks, 96));
  }

  @Test
  void approachPreviewSpeedLimitFallsSmoothlyAsDistanceShrinks() {
    double normalSpeed = 28.8;
    double approachSpeed = 6.0;
    double windowBlocks = 96.0;
    double previewBlocks = RuntimeDispatchService.APPROACH_PREVIEW_DISTANCE_BLOCKS;

    double farRatio = RuntimeTrainController.approachPreviewRatio(windowBlocks, previewBlocks, 150);
    double midRatio = RuntimeTrainController.approachPreviewRatio(windowBlocks, previewBlocks, 128);
    double boundaryRatio =
        RuntimeTrainController.approachPreviewRatio(windowBlocks, previewBlocks, 96);
    double farLimit =
        RuntimeTrainController.approachPreviewSpeedLimit(normalSpeed, approachSpeed, farRatio);
    double midLimit =
        RuntimeTrainController.approachPreviewSpeedLimit(normalSpeed, approachSpeed, midRatio);
    double boundaryLimit =
        RuntimeTrainController.approachPreviewSpeedLimit(normalSpeed, approachSpeed, boundaryRatio);

    assertTrue(farLimit < normalSpeed);
    assertTrue(farLimit > midLimit);
    assertTrue(midLimit > boundaryLimit);
    assertEquals(approachSpeed, boundaryLimit, 1.0e-6);
  }

  @Test
  void approachEnvelopeIsMonotonicAsDistanceShrinks() {
    ConfigManager.RuntimeSettings runtime = runtimeSettings();
    double normalSpeed = 28.8;
    double approachSpeed = 6.0;
    double previous = normalSpeed;

    for (long distance : new long[] {180L, 150L, 128L, 110L, 96L, 80L}) {
      double cap =
          RuntimeTrainController.resolveApproachSpeedEnvelope(
              normalSpeed,
              approachSpeed,
              1.0,
              OptionalLong.of(distance),
              OptionalLong.of(20L),
              4,
              runtime,
              RuntimeDispatchService.APPROACH_PREVIEW_DISTANCE_BLOCKS);
      assertTrue(cap <= previous + 1.0e-6, "cap must not rise when distance shrinks");
      previous = cap;
    }
  }

  @Test
  void approachConstraintMatchesCycleEnvelopeAndShiftsOnlyTheTriggerDistance() {
    ConfigManager.RuntimeSettings runtime = runtimeSettings();
    double preview = RuntimeDispatchService.APPROACH_PREVIEW_DISTANCE_BLOCKS;
    var constraint =
        RuntimeTrainController.approachConstraint(
            28.8, 6.0, 1.0, 150L, OptionalLong.of(40L), 4, runtime, preview);

    for (double traveled : new double[] {0.0, 12.0, 30.0, 70.0}) {
      double expected =
          RuntimeTrainController.resolveApproachSpeedEnvelope(
              28.8,
              6.0,
              1.0,
              OptionalLong.of((long) Math.floor(150.0 - traveled)),
              OptionalLong.of(40L),
              4,
              runtime,
              preview);
      assertEquals(
          expected < 28.8 ? expected : Double.POSITIVE_INFINITY,
          constraint.limitBps(traveled),
          1.0e-9,
          "traveled=" + traveled);
    }
  }

  @Test
  void leadInBrakingReachesTheBoundaryEnvelopeWithoutAStep() {
    // 实服 80 km/h 线：22.22 bps、进站 10、末边 40 格、减速度 1.0。区界处区内包络 ≈ 18.3，旧做法进区那一拍一刀切。
    ConfigManager.RuntimeSettings runtime = runtimeSettings();
    double preview = RuntimeDispatchService.APPROACH_PREVIEW_DISTANCE_BLOCKS;
    long boundary = (long) Math.ceil(runtime.approachWindowBlocks() + preview) - 1L;
    OptionalLong targetEdge = OptionalLong.of(40L);

    double inside =
        RuntimeTrainController.resolveApproachSpeedEnvelope(
            22.22, 10.0, 1.0, OptionalLong.of(boundary), targetEdge, 4, runtime, preview);
    double justOutside =
        RuntimeTrainController.resolveApproachSpeedLimit(
            22.22, 10.0, 1.0, boundary + 1L, targetEdge, 4, runtime, preview);
    assertTrue(inside < 22.22 - 3.0, "本例区界处包络应明显低于线路速度，否则测不到这一刀");
    assertEquals(Math.sqrt(inside * inside + 2.0), justOutside, 1.0e-9);

    double previous = 22.22;
    for (long distance = 400L; distance >= 60L; distance--) {
      double limit =
          RuntimeTrainController.resolveApproachSpeedLimit(
              22.22, 10.0, 1.0, distance, targetEdge, 4, runtime, preview);
      assertTrue(limit <= previous + 1.0e-9, "距离缩短时限速不得回升，distance=" + distance);
      assertTrue(previous - limit < 0.25, "每前进 1 格限速变化应很小，distance=" + distance);
      previous = limit;
    }
  }

  @Test
  void leadInFollowsConfiguredDecelerationAndLeavesFarTrainsAlone() {
    ConfigManager.RuntimeSettings runtime = runtimeSettings();
    double preview = RuntimeDispatchService.APPROACH_PREVIEW_DISTANCE_BLOCKS;
    OptionalLong targetEdge = OptionalLong.of(40L);

    double at200 =
        RuntimeTrainController.resolveApproachSpeedLimit(
            22.22, 10.0, 0.8, 200L, targetEdge, 4, runtime, preview);
    double at199 =
        RuntimeTrainController.resolveApproachSpeedLimit(
            22.22, 10.0, 0.8, 199L, targetEdge, 4, runtime, preview);
    assertEquals(2.0 * 0.8, at200 * at200 - at199 * at199, 1.0e-9);
    assertEquals(
        22.22,
        RuntimeTrainController.resolveApproachSpeedLimit(
            22.22, 10.0, 1.0, 400L, targetEdge, 4, runtime, preview),
        1.0e-12);
  }

  @Test
  void leadInBringsTrainToApproachSpeedAtTheBoundaryWhenTheTargetPointIsFartherOut() {
    // 末边比整个进站区还长：区内全程进站限速（旧行为），区外导入制动在区界降到进站限速，而不是进区一刀切。
    ConfigManager.RuntimeSettings runtime = runtimeSettings();
    double preview = RuntimeDispatchService.APPROACH_PREVIEW_DISTANCE_BLOCKS;
    long boundary = (long) Math.ceil(runtime.approachWindowBlocks() + preview) - 1L;
    OptionalLong targetEdge = OptionalLong.of(300L);

    assertEquals(
        10.0,
        RuntimeTrainController.resolveApproachSpeedLimit(
            22.22, 10.0, 1.0, boundary, targetEdge, 4, runtime, preview),
        1.0e-9);
    assertEquals(
        Math.sqrt(100.0 + 2.0 * 41.0),
        RuntimeTrainController.resolveApproachSpeedLimit(
            22.22, 10.0, 1.0, boundary + 41L, targetEdge, 4, runtime, preview),
        1.0e-9);
  }

  @Test
  void approachConstraintUsesTheSameLimitOutsideTheZone() {
    ConfigManager.RuntimeSettings runtime = runtimeSettings();
    double preview = RuntimeDispatchService.APPROACH_PREVIEW_DISTANCE_BLOCKS;
    var constraint =
        RuntimeTrainController.approachConstraint(
            22.22, 10.0, 1.0, 260L, OptionalLong.of(40L), 4, runtime, preview);

    for (double traveled : new double[] {0.0, 55.5, 100.0, 101.0, 150.0}) {
      long remaining = (long) Math.floor(260.0 - traveled);
      double expected =
          RuntimeTrainController.resolveApproachSpeedLimit(
              22.22, 10.0, 1.0, remaining, OptionalLong.of(40L), 4, runtime, preview);
      // 不收紧时给 +∞：它同时是推进放行的保持约束，“不设限”不能写成周期取样时的基础速度。
      assertEquals(
          expected < 22.22 ? expected : Double.POSITIVE_INFINITY,
          constraint.limitBps(traveled),
          1.0e-9,
          "traveled=" + traveled);
    }
    assertEquals(Double.POSITIVE_INFINITY, constraint.limitBps(0.0), "260 格处导入制动尚未收紧");
    assertTrue(constraint.limitBps(150.0) < 22.22);
  }

  private static ConfigManager.RuntimeSettings runtimeSettings() {
    return new ConfigManager.RuntimeSettings(
        20,
        10,
        2,
        1,
        1,
        3,
        4.0,
        6.0,
        3.5,
        true,
        SpeedCurveType.PHYSICS,
        1.0,
        0.0,
        0.2,
        60,
        true,
        true,
        2.0,
        8.0,
        0.0,
        1.0,
        1.0,
        3,
        true,
        10,
        Optional.empty(),
        false,
        10,
        Optional.empty(),
        false,
        10,
        Optional.empty());
  }
}
