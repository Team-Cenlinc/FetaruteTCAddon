package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.offline.train.OfflineGroupManager;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 恢复只认可真实在线或离线车辆，不能把遗留属性和模板当作车辆。 */
class TrainCartsTimetableDutyStoreTest {
  @Test
  void restoresOfflineVehiclesButRejectsOrphanPropertiesAndRollbackRemnants() {
    var offline = properties("offline", true);
    var orphan = properties("orphan", false);
    var rollback = properties("rollback", true);
    when(rollback.getTags())
        .thenReturn(
            Set.of(
                TrainCartsTimetableDutyStore.TAG_DUTY + "=token",
                TrainSpawnTagInitializer.TAG_MATERIALIZED_ROLLBACK_PENDING + "=yes"));
    try (var store = mockStatic(TrainPropertiesStore.class)) {
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(offline, orphan, rollback));
      assertEquals(Map.of("offline", "token"), new TrainCartsTimetableDutyStore().read());
      store.verify(() -> TrainPropertiesStore.get("orphan"), never());
    }
  }

  private static TrainProperties properties(String name, boolean offline) {
    var properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(name);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags()).thenReturn(Set.of(TrainCartsTimetableDutyStore.TAG_DUTY + "=token"));
    when(properties.getHolder()).thenReturn(null);
    var tc = mock(TrainCarts.class);
    var groups = mock(OfflineGroupManager.class);
    when(properties.getTrainCarts()).thenReturn(tc);
    when(tc.getOfflineGroups()).thenReturn(groups);
    when(groups.contains(name)).thenReturn(offline);
    return properties;
  }
}
