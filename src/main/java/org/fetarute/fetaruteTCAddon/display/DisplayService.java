package org.fetarute.fetaruteTCAddon.display;

/**
 * 展示层服务入口：负责启动/停止 HUD、站牌等 UI 组件。
 *
 * <p>注意：该层只做“展示”，不改变调度状态机。
 */
public interface DisplayService {

  /** 启动展示层（注册监听/启动刷新任务）。 */
  void start();

  /** 停止展示层（取消任务并释放资源）。 */
  void stop();
}
