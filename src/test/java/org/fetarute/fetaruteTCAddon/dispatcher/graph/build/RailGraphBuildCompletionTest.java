package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RailGraphBuildCompletionTest {

  @Test
  void canReplaceComponentsOnlyWhenComplete() {
    assertTrue(RailGraphBuildCompletion.COMPLETE.canReplaceComponents());
    assertFalse(RailGraphBuildCompletion.PARTIAL_UNLOADED_CHUNKS.canReplaceComponents());
    assertFalse(RailGraphBuildCompletion.PARTIAL_MAX_CHUNKS.canReplaceComponents());
    assertFalse(RailGraphBuildCompletion.PARTIAL_FAILED_CHUNK_LOADS.canReplaceComponents());
  }
}

