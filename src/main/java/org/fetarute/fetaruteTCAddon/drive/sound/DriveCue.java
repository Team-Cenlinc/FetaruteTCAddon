package org.fetarute.fetaruteTCAddon.drive.sound;

/**
 * 驾驶员听到的提示音。每种提示音在 {@code drive.yml} 的 {@code sounds} 段有同名的键，可改音效、音量与音高，音效留空即关闭。
 *
 * <p>声音不会被别的插件的动作栏消息顶掉，所以要紧的提醒（信号变严、防护介入、超速）都配了声音。
 */
public enum DriveCue {
  /** 信号变严，等驾驶员右键确认：每秒响一次，直到确认。 */
  SIGNAL_RESTRICTIVE("signal-restrictive", "minecraft:block.note_block.bit", 1.0f, 0.7f),
  /** 信号已确认。 */
  SIGNAL_CONFIRMED("signal-confirmed", "minecraft:ui.button.click", 0.6f, 1.2f),
  /** 信号转宽。 */
  SIGNAL_CLEAR("signal-clear", "minecraft:block.note_block.bell", 0.8f, 1.2f),
  /** 防护开始常用制动（ATP 制动）。 */
  ATP_BRAKE("atp-brake", "minecraft:block.note_block.pling", 1.0f, 0.5f),
  /** 防护施加紧急制动或强制停车。 */
  EMERGENCY("emergency", "minecraft:block.anvil.place", 0.8f, 0.7f),
  /** 超速进入红区：每半秒响一次，直到回落。 */
  OVERSPEED("overspeed", "minecraft:block.note_block.bit", 0.8f, 1.8f),
  /** 该开始制动了（每个目标一次）。 */
  BRAKE_ADVICE("brake-advice", "minecraft:block.note_block.chime", 1.0f, 1.0f),
  /** 在车站停妥，可以开门。 */
  DOORS_RELEASED("doors-released", "minecraft:block.note_block.chime", 0.8f, 1.6f),
  /** 发车信号。 */
  DEPART("depart", "minecraft:block.note_block.bell", 1.0f, 1.4f),
  /** ATO 等驾驶员右键确认发车。 */
  ATO_CONFIRM("ato-confirm", "minecraft:block.note_block.bell", 0.8f, 1.0f),
  /** 对标：停准。 */
  STOP_ACCURATE("stop-accurate", "minecraft:entity.player.levelup", 0.6f, 1.4f),
  /** 对标：在可开门范围内。 */
  STOP_ACCEPTED("stop-accepted", "minecraft:block.note_block.pling", 0.8f, 1.2f),
  /** 对标：越过窗口或停短。 */
  STOP_POOR("stop-poor", "minecraft:block.note_block.bass", 1.0f, 0.8f),
  /** 驾驶任务完成。 */
  TASK_COMPLETE("task-complete", "minecraft:ui.toast.challenge_complete", 0.8f, 1.0f),
  /** 警惕装置报警。 */
  VIGILANCE_WARNING("vigilance-warning", "minecraft:block.note_block.bell", 1.0f, 1.6f),
  /** 警惕装置超时，紧急制动。 */
  VIGILANCE_TRIPPED("vigilance-tripped", "minecraft:block.anvil.land", 0.6f, 0.8f),
  /** 鸣笛：附近的玩家都听得到。 */
  HORN("horn", "minecraft:item.goat_horn.sound.0", 4.0f, 1.0f);

  private final String configKey;
  private final String defaultSound;
  private final float defaultVolume;
  private final float defaultPitch;

  DriveCue(String configKey, String defaultSound, float defaultVolume, float defaultPitch) {
    this.configKey = configKey;
    this.defaultSound = defaultSound;
    this.defaultVolume = defaultVolume;
    this.defaultPitch = defaultPitch;
  }

  /** {@code sounds} 段里的键。 */
  public String configKey() {
    return configKey;
  }

  /** 内置默认音效。 */
  public DriveSoundConfig.Spec defaultSpec() {
    return new DriveSoundConfig.Spec(defaultSound, defaultVolume, defaultPitch);
  }
}
