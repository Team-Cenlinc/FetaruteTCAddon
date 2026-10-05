package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** 轨迹快照不能因调用方修改输入或读取结果而改变，避免闭塞时间计算读到不一致的采样。 */
class RunTimeModelTrajectoryTest {

  @Test
  void modifyingInputArraysDoesNotChangeTheSnapshot() {
    double[] distance = {0.0, 10.0};
    double[] seconds = {0.0, 2.0};
    double[] speed = {0.0, 5.0};
    RunTimeModel.Trajectory trajectory = new RunTimeModel.Trajectory(distance, seconds, speed);

    distance[1] = 100.0;
    seconds[1] = 20.0;
    speed[1] = 50.0;

    assertArrayEquals(new double[] {0.0, 10.0}, trajectory.distance());
    assertArrayEquals(new double[] {0.0, 2.0}, trajectory.seconds());
    assertArrayEquals(new double[] {0.0, 5.0}, trajectory.speed());
  }

  @Test
  void modifyingReturnedArraysDoesNotChangeTheSnapshot() {
    RunTimeModel.Trajectory trajectory =
        new RunTimeModel.Trajectory(
            new double[] {0.0, 10.0}, new double[] {0.0, 2.0}, new double[] {0.0, 5.0});

    trajectory.distance()[1] = 100.0;
    trajectory.seconds()[1] = 20.0;
    trajectory.speed()[1] = 50.0;

    assertArrayEquals(new double[] {0.0, 10.0}, trajectory.distance());
    assertArrayEquals(new double[] {0.0, 2.0}, trajectory.seconds());
    assertArrayEquals(new double[] {0.0, 5.0}, trajectory.speed());
  }
}
