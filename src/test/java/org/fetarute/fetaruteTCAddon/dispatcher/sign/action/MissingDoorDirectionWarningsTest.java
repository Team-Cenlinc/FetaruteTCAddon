package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 第 4 行漏写开门方向的站台：每个节点只告警一次，显式 NONE 与有效方向不告警。 */
class MissingDoorDirectionWarningsTest {

  @Test
  void blankLineWarnsOncePerNode() {
    MissingDoorDirectionWarnings warnings = new MissingDoorDirectionWarnings();

    Optional<String> first = warnings.onStop("SURC:S:ZKW:1", "", "world 10 64 20");

    assertTrue(first.isPresent());
    String line = first.get();
    assertTrue(line.contains("node=SURC:S:ZKW:1"), line);
    assertTrue(line.contains("第4行=\"\"（空行）"), line);
    assertTrue(line.contains("@ world 10 64 20"), line);
    assertTrue(line.contains("NONE"), line);
    assertTrue(warnings.onStop("SURC:S:ZKW:1", "", "world 10 64 20").isEmpty());
    assertTrue(warnings.onStop("SURC:S:ZKW:1", null, "world 10 64 20").isEmpty());
  }

  @Test
  void eachNodeWarnsSeparately() {
    MissingDoorDirectionWarnings warnings = new MissingDoorDirectionWarnings();

    assertTrue(warnings.onStop("SURC:S:WSD:2", "", null).isPresent());
    assertTrue(warnings.onStop("SURC:S:WSD:3", "", null).isPresent());
    assertTrue(warnings.onStop("SURC:S:WSD:2", "", null).isEmpty());
  }

  @Test
  void unrecognisedTextIsQuotedInTheWarning() {
    MissingDoorDirectionWarnings warnings = new MissingDoorDirectionWarnings();

    String line = warnings.onStop("SURC:S:ZKW:2", "left", "").orElseThrow();

    assertTrue(line.contains("第4行=\"left\"（无法识别）"), line);
    assertTrue(!line.contains(" @ "), line);
  }

  @Test
  void explicitNoneAndValidDirectionsNeverWarn() {
    MissingDoorDirectionWarnings warnings = new MissingDoorDirectionWarnings();

    assertTrue(warnings.onStop("SURC:S:A:1", "NONE", null).isEmpty());
    assertTrue(warnings.onStop("SURC:S:A:2", " none ", null).isEmpty());
    assertTrue(warnings.onStop("SURC:S:A:3", "BOTH", null).isEmpty());
    assertTrue(warnings.onStop("SURC:S:A:4", "se", null).isEmpty());
  }

  /** 写着 NONE 时停站不告警，之后第 4 行改成空行仍要告警：去重只记已告警过的节点。 */
  @Test
  void onlyWarnedNodesAreRemembered() {
    MissingDoorDirectionWarnings warnings = new MissingDoorDirectionWarnings();

    assertTrue(warnings.onStop("SURC:S:B:1", "NONE", null).isEmpty());
    assertTrue(warnings.onStop("SURC:S:B:1", "", null).isPresent());
  }

  @Test
  void missingNodeIdIsIgnored() {
    MissingDoorDirectionWarnings warnings = new MissingDoorDirectionWarnings();

    assertTrue(warnings.onStop(null, "", null).isEmpty());
    assertTrue(warnings.onStop(" ", "", null).isEmpty());
  }
}
