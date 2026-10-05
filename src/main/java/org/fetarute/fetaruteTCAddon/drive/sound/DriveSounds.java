package org.fetarute.fetaruteTCAddon.drive.sound;

import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 播放驾驶提示音。提示音只发给驾驶员本人；鸣笛在列车所在位置播放，附近的玩家都听得到。
 *
 * <p>只在服务器主线程调用。
 */
public final class DriveSounds {

  private volatile DriveSoundConfig config = DriveSoundConfig.defaults();

  public void configure(DriveSoundConfig newConfig) {
    this.config = Objects.requireNonNull(newConfig, "config");
  }

  public DriveSoundConfig config() {
    return config;
  }

  /** 给驾驶员本人播放一种提示音（被关闭时什么也不做）。 */
  public void play(Player player, DriveCue cue) {
    if (player == null || !player.isOnline()) {
      return;
    }
    config
        .spec(cue)
        .ifPresent(
            spec ->
                player.playSound(
                    player.getLocation(),
                    spec.sound(),
                    SoundCategory.MASTER,
                    spec.volume(),
                    spec.pitch()));
  }

  /** 依次播放。 */
  public void playAll(Player player, List<DriveCue> cues) {
    for (DriveCue cue : cues) {
      play(player, cue);
    }
  }

  /**
   * 在某处播放，附近的玩家都听得到（鸣笛）。
   *
   * @return 是否播放了（被关闭时为 {@code false}）
   */
  public boolean broadcast(Location at, DriveCue cue) {
    World world = at == null ? null : at.getWorld();
    if (world == null) {
      return false;
    }
    return config
        .spec(cue)
        .map(
            spec -> {
              world.playSound(at, spec.sound(), SoundCategory.PLAYERS, spec.volume(), spec.pitch());
              return true;
            })
        .orElse(false);
  }
}
