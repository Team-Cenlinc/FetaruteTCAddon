package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BossBarHudTemplateRendererTest {

  @Test
  void applyPlaceholdersReplacesKnownAndKeepsUnknown() {
    String template = "{line}-{next_station}-{unknown}";
    Map<String, String> placeholders = Map.of("line", "L1", "next_station", "Central");

    String resolved = BossBarHudTemplateRenderer.applyPlaceholders(template, placeholders);

    assertEquals("L1-Central-{unknown}", resolved);
  }
}
