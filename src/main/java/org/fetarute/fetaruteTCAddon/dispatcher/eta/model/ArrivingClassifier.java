package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaConfidence;

/**
 * 进站（Arriving/Approaching）判定。
 *
 * <p>你们约定：Approaching=Arriving。
 *
 * <p>MVP 规则：
 *
 * <ul>
 *   <li>remainingEdgeCount <= 2 视为 arriving
 *   <li>若被 hardStop 阻塞则否决 arriving
 * </ul>
 */
public final class ArrivingClassifier {

  public record Arriving(boolean arriving, EtaConfidence confidence) {
    public Arriving {
      Objects.requireNonNull(confidence, "confidence");
    }
  }

  public Arriving classify(int remainingEdgeCount, boolean blockedByHardStop) {
    if (blockedByHardStop) {
      return new Arriving(false, EtaConfidence.MED);
    }
    if (remainingEdgeCount <= 0) {
      return new Arriving(true, EtaConfidence.HIGH);
    }
    if (remainingEdgeCount <= 2) {
      return new Arriving(true, EtaConfidence.HIGH);
    }
    return new Arriving(false, EtaConfidence.MED);
  }
}
