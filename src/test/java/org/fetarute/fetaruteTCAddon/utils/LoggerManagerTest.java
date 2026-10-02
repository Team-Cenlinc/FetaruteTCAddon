package org.fetarute.fetaruteTCAddon.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class LoggerManagerTest {

  @Test
  void debugSinkFollowsDebugSwitch() {
    LoggerManager manager = new LoggerManager(Logger.getLogger("LoggerManagerTest"));
    DiagnosticSink sink = manager.debugSink();

    assertFalse(sink.enabled());
    manager.setDebugEnabled(true);
    assertTrue(sink.enabled());
    manager.setDebugEnabled(false);
    assertFalse(DiagnosticSink.enabled(sink));
  }

  @Test
  void plainConsumerCountsAsEnabled() {
    assertTrue(DiagnosticSink.enabled(message -> {}));
    assertTrue(DiagnosticSink.enabled(null));
  }
}
