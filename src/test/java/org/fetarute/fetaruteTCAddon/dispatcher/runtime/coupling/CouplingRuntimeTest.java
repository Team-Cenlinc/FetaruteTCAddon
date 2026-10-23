package org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 调度改名的通知：同步转给拆挂服务，监听方出错不影响改名。 */
class CouplingRuntimeTest {

  @Test
  void aRenameIsForwardedToTheListenerAtOnce() {
    CouplingRuntime runtime = new CouplingRuntime(name -> {});
    List<String> seen = new ArrayList<>();
    runtime.setRenameListener((from, to) -> seen.add(from + "->" + to));

    runtime.renamed("SURC-MT-LH-0035", "SURC-MT-LN-5986");
    runtime.renamed("SURC-MT-LN-5986", "SURC-MT-LN-5986");
    runtime.renamed(null, "SURC-MT-LN-5986");

    assertEquals(List.of("SURC-MT-LH-0035->SURC-MT-LN-5986"), seen, "同名与缺名不通知");
  }

  @Test
  void aFailingListenerDoesNotBreakTheRenameAndCanBeCleared() {
    CouplingRuntime runtime = new CouplingRuntime(name -> {});
    runtime.setRenameListener(
        (from, to) -> {
          throw new IllegalStateException("boom");
        });
    runtime.renamed("A", "B");

    List<String> seen = new ArrayList<>();
    runtime.setRenameListener(null);
    runtime.renamed("A", "B");
    assertTrue(seen.isEmpty());
  }
}
