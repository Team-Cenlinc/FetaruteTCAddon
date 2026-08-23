package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeTrainHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsRuntimeHandle;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 负责执行“从 Depot 生成列车”的实现，可被单测替换。 */
public interface DepotSpawner {

  /**
   * 已经生成的物理编组及其延后初始化动作。
   *
   * <p>调用方必须先取得 {@link #train()} 并建立针对精确物理 identity 的回滚边界，再调用 {@link
   * #initialize()}。初始化异常不能让已存在的物理编组逃离发车事务。
   */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "RuntimeTrainHandle 是精确物理编组的可变控制句柄；发车事务必须保留同一实例，" + "以保证初始化、信号刷新和失败回滚操作同一列车。")
  record MaterializedSpawn(RuntimeTrainHandle train, Runnable initializer) {
    public MaterializedSpawn {
      Objects.requireNonNull(train, "train");
      initializer = initializer == null ? () -> {} : initializer;
    }

    /** 把 TrainCarts 实体适配为调度层可测试的运行时句柄。 */
    public MaterializedSpawn(MinecartGroup group, Runnable initializer) {
      this(new TrainCartsRuntimeHandle(Objects.requireNonNull(group, "group")), initializer);
    }

    /** 在调用方已经接管物理编组后执行所有可失败的标签与 warm-up 初始化。 */
    public void initialize() {
      initializer.run();
    }
  }

  /**
   * 尝试从指定 depot 生成列车。
   *
   * <p>实现必须在物理实体生成后立即返回；所有可能失败的后置初始化都封装到返回值，由调用方在 group-aware 事务中执行。
   *
   * @return 成功则返回物理编组与延后初始化动作
   */
  Optional<MaterializedSpawn> spawn(
      StorageProvider provider, SpawnTicket ticket, String trainName, Instant now);
}
