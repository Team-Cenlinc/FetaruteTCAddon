package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
