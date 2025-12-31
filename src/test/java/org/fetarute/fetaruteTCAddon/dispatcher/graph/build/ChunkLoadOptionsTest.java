package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ChunkLoadOptionsTest {

  @Test
  void disabledFactoryReturnsZeros() {
    ChunkLoadOptions options = ChunkLoadOptions.disabled();
    assertFalse(options.enabled());
    assertEquals(0, options.maxChunks());
    assertEquals(0, options.maxConcurrentLoads());
  }

  @Test
  void disabledConstructorNormalizesValues() {
    ChunkLoadOptions options = new ChunkLoadOptions(false, 123, 456);
    assertFalse(options.enabled());
    assertEquals(0, options.maxChunks());
    assertEquals(0, options.maxConcurrentLoads());
  }

  @Test
  void enabledRequiresPositiveNumbers() {
    assertThrows(IllegalArgumentException.class, () -> new ChunkLoadOptions(true, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new ChunkLoadOptions(true, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new ChunkLoadOptions(true, -1, 1));
    assertThrows(IllegalArgumentException.class, () -> new ChunkLoadOptions(true, 1, -1));
  }

  @Test
  void enabledKeepsProvidedValues() {
    ChunkLoadOptions options = new ChunkLoadOptions(true, 256, 4);
    assertTrue(options.enabled());
    assertEquals(256, options.maxChunks());
    assertEquals(4, options.maxConcurrentLoads());
  }
}

