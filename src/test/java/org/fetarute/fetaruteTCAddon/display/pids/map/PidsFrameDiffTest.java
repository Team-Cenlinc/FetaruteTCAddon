package org.fetarute.fetaruteTCAddon.display.pids.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsFrameDiff.Region;
import org.junit.jupiter.api.Test;

/** 帧差：每块一个外接矩形，未变的块不出现。 */
class PidsFrameDiffTest {

  private static final int WIDTH = 384;
  private static final int HEIGHT = 128;

  @Test
  void firstFrameWritesEveryTileWhole() {
    assertEquals(
        List.of(
            new Region(0, 0, 128, 128), new Region(128, 0, 128, 128), new Region(256, 0, 128, 128)),
        PidsFrameDiff.changed(null, new byte[WIDTH * HEIGHT], WIDTH, HEIGHT));
  }

  @Test
  void identicalFramesWriteNothing() {
    byte[] frame = new byte[WIDTH * HEIGHT];

    assertTrue(PidsFrameDiff.changed(frame, frame.clone(), WIDTH, HEIGHT).isEmpty());
  }

  @Test
  void eachTileGetsTheBoundingBoxOfItsOwnChanges() {
    byte[] before = new byte[WIDTH * HEIGHT];
    byte[] after = before.clone();
    set(after, 10, 20);
    set(after, 30, 5);
    // 跨块的两个点各归各块
    set(after, 127, 64);
    set(after, 128, 64);

    assertEquals(
        List.of(new Region(10, 5, 118, 60), new Region(128, 64, 1, 1)),
        PidsFrameDiff.changed(before, after, WIDTH, HEIGHT));
  }

  @Test
  void scatteredRegionsAreWrittenTickByTick() {
    assertTrue(
        PidsFrameDiff.writeTogether(
            PidsFrameDiff.changed(null, new byte[WIDTH * HEIGHT], WIDTH, HEIGHT)),
        "整页切换一次写完");
    assertTrue(
        PidsFrameDiff.writeTogether(
            List.of(new Region(120, 10, 8, 10), new Region(128, 10, 8, 10))),
        "跨块相邻的两处一次写完");
    assertFalse(
        PidsFrameDiff.writeTogether(
            List.of(new Region(4, 4, 10, 10), new Region(370, 110, 10, 10))),
        "屏幕两头各一处分 tick 写，免得把中间的地图也带上");
  }

  @Test
  void cropCopiesRowsOfTheRegion() {
    byte[] frame = new byte[WIDTH * HEIGHT];
    set(frame, 200, 7);
    set(frame, 201, 8);

    byte[] crop = PidsFrameDiff.crop(frame, WIDTH, new Region(200, 7, 2, 2));

    assertArrayEquals(new byte[] {1, 0, 0, 1}, crop);
  }

  private static void set(byte[] frame, int x, int y) {
    frame[y * WIDTH + x] = 1;
  }
}
