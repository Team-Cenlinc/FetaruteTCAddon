package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.*;

import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.ArrivingClassifier;
import org.junit.jupiter.api.Test;

public class ArrivingClassifierTest {

  @Test
  void classify_remainingEdges_le2_isArriving() {
    ArrivingClassifier classifier = new ArrivingClassifier();
    assertTrue(classifier.classify(2, false).arriving());
    assertTrue(classifier.classify(1, false).arriving());
    assertTrue(classifier.classify(0, false).arriving());
  }

  @Test
  void classify_hardStop_vetoesArriving() {
    ArrivingClassifier classifier = new ArrivingClassifier();
    assertFalse(classifier.classify(1, true).arriving());
  }
}
