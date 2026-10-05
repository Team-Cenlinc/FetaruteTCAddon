package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedTrainsTest {

  @Test
  void aTrainWithoutAnyTagsIsNotManaged() {
    assertFalse(ManagedTrains.isFtaManaged(train()));
    assertFalse(ManagedTrains.isFtaManaged(null));
  }

  @Test
  void aRouteIdTagMakesItManaged() {
    assertTrue(ManagedTrains.isFtaManaged(train("FTA_ROUTE_ID=5f1c")));
  }

  @Test
  void anyOperatorLineOrRouteCodeTagMakesItManagedToo() {
    assertTrue(ManagedTrains.isFtaManaged(train("FTA_OPERATOR_CODE=PKHI")));
    assertTrue(ManagedTrains.isFtaManaged(train("FTA_LINE_CODE=WS")));
    assertTrue(ManagedTrains.isFtaManaged(train("FTA_ROUTE_CODE=WS-1")));
  }

  @Test
  void theLegacyTagNamesCountAsWell() {
    assertTrue(ManagedTrains.isFtaManaged(train("FTA_OPERATOR=PKHI")));
    assertTrue(ManagedTrains.isFtaManaged(train("FTA_LINE=WS")));
    assertTrue(ManagedTrains.isFtaManaged(train("FTA_ROUTE=WS-1")));
  }

  @Test
  void blankValuesAndUnrelatedTagsDoNotCount() {
    assertFalse(ManagedTrains.isFtaManaged(train("FTA_ROUTE_ID=")));
    assertFalse(ManagedTrains.isFtaManaged(train("FTA_LINE_CODE=   ")));
    assertFalse(
        ManagedTrains.isFtaManaged(train("FTA_TRAIN_TYPE=EMU", "freight", "FTA_TRAIN_MT=4M2T")));
    assertFalse(ManagedTrains.isFtaManaged(train("FTA_DRIVE_SLOWDOWN_ORIG=true")));
  }

  private static TrainProperties train(String... initialTags) {
    List<String> tags = new ArrayList<>(Arrays.asList(initialTags));
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
    when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
    doAnswer(inv -> null).when(properties).addTags(any(String[].class));
    return properties;
  }
}
