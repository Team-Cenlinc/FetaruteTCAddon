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
