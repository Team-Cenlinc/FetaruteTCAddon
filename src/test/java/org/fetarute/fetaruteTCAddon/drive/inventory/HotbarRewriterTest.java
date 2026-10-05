package org.fetarute.fetaruteTCAddon.drive.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class HotbarRewriterTest {

  private static final UnaryOperator<String> KEEP = item -> item;
  private final HotbarRewriter<String> rewriter =
      new HotbarRewriter<>(List.of("P3", "P2", "P1", "N", "B1", "B2", "B3", "B4", "EB"));

  @Test
  void hotbarSlotsOfThePlayerWindowAreRecognised() {
    assertFalse(rewriter.isHotbarSlot(0, 35));
    assertTrue(rewriter.isHotbarSlot(0, 36));
    assertTrue(rewriter.isHotbarSlot(0, 44));
    assertFalse(rewriter.isHotbarSlot(0, 45));
    assertFalse(rewriter.isHotbarSlot(1, 40), "其它窗口的槽位不是玩家快捷栏");
  }

  @Test
  void directInventoryUpdatesUseSlotsZeroToEight() {
    assertTrue(rewriter.isHotbarSlot(HotbarRewriter.DIRECT_INVENTORY_WINDOW, 0));
    assertTrue(rewriter.isHotbarSlot(HotbarRewriter.DIRECT_INVENTORY_WINDOW, 8));
    assertFalse(rewriter.isHotbarSlot(HotbarRewriter.DIRECT_INVENTORY_WINDOW, 9));
    assertEquals("B2", rewriter.itemFor(HotbarRewriter.DIRECT_INVENTORY_WINDOW, 5, "real", KEEP));
  }

  @Test
  void aHotbarSlotUpdateIsReplacedByTheMatchingDriveItem() {
    assertEquals("P3", rewriter.itemFor(0, 36, "real sword", KEEP));
    assertEquals("N", rewriter.itemFor(0, 39, "real sword", KEEP));
    assertEquals("EB", rewriter.itemFor(0, 44, "real sword", KEEP));
  }

  @Test
  void otherSlotUpdatesPassThroughUntouched() {
    String original = "real";
    assertSame(original, rewriter.itemFor(0, 12, original, KEEP));
    assertSame(original, rewriter.itemFor(3, 40, original, KEEP));
  }

  @Test
  void replacementsAreCopiedSoPacketsNeverShareOneItem() {
    int[] copies = {0};
    UnaryOperator<String> counting =
        item -> {
          copies[0]++;
          return item + "'";
        };

    assertEquals("P2'", rewriter.itemFor(0, 37, "real", counting));
    assertEquals(1, copies[0]);
  }

  @Test
  void aFullWindowUpdateGetsTheHotbarReplacedAndNothingElse() {
    List<String> window = new ArrayList<>();
    for (int i = 0; i < HotbarRewriter.PLAYER_WINDOW_SIZE; i++) {
      window.add("real" + i);
    }

    List<String> rewritten = rewriter.contentsFor(0, window, KEEP);

    assertEquals(HotbarRewriter.PLAYER_WINDOW_SIZE, rewritten.size());
    for (int i = 0; i < rewritten.size(); i++) {
      if (i >= 36 && i <= 44) {
        assertEquals(
            List.of("P3", "P2", "P1", "N", "B1", "B2", "B3", "B4", "EB").get(i - 36),
            rewritten.get(i));
      } else {
        assertEquals("real" + i, rewritten.get(i));
      }
    }
    assertEquals("real36", window.get(36), "不能改动传入的原列表");
  }

  @Test
  void windowsOtherThanThePlayerWindowAreNotRewritten() {
    List<String> window = List.of("a", "b", "c");

    assertSame(window, rewriter.contentsFor(2, window, KEEP));
    assertSame(window, rewriter.contentsFor(0, window, KEEP), "槽位不足 45 格的内容原样放行");
  }

  @Test
  void needsExactlyNineDriveItems() {
    assertThrows(IllegalArgumentException.class, () -> new HotbarRewriter<>(List.of("a", "b")));
  }

  private static final List<String> DRIVE_ITEMS =
      List.of("P3", "P2", "P1", "N", "B1", "B2", "B3", "B4", "EB");

  @Test
  void theHotbarShownInsideAnOpenMenuWindowIsRewrittenToo() {
    HotbarRewriter<String> inMenu = new HotbarRewriter<>(DRIVE_ITEMS, () -> 9);

    // 菜单窗口：上半部分 9 格 + 主背包 27 格 + 快捷栏 9 格，快捷栏在 36–44。
    assertTrue(inMenu.isHotbarSlot(5, 36));
    assertTrue(inMenu.isHotbarSlot(5, 44));
    assertFalse(inMenu.isHotbarSlot(5, 35), "主背包不改写");
    assertFalse(inMenu.isHotbarSlot(5, 45));
    assertEquals("B4", inMenu.itemFor(5, 43, "real", KEEP));
  }

  @Test
  void aWindowUpdateOfTheMenuWindowGetsItsLastNineSlotsReplaced() {
    HotbarRewriter<String> inMenu = new HotbarRewriter<>(DRIVE_ITEMS, () -> 9);
    List<String> window = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      window.add("real" + i);
    }

    List<String> rewritten = inMenu.contentsFor(7, window, KEEP);

    assertEquals("real35", rewritten.get(35));
    assertEquals("P3", rewritten.get(36));
    assertEquals("EB", rewritten.get(44));
    assertEquals("real36", window.get(36), "不能改动传入的原列表");
  }

  @Test
  void withoutAnOpenMenuOtherWindowsAreLeftAlone() {
    HotbarRewriter<String> noMenu = new HotbarRewriter<>(DRIVE_ITEMS, () -> 0);
    List<String> window = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      window.add("real" + i);
    }

    assertFalse(noMenu.isHotbarSlot(5, 40));
    assertSame(window, noMenu.contentsFor(5, window, KEEP));
  }

  @Test
  void aWindowOfTheWrongSizeIsNotMistakenForTheMenu() {
    HotbarRewriter<String> inMenu = new HotbarRewriter<>(DRIVE_ITEMS, () -> 9);
    List<String> chest = new ArrayList<>();
    for (int i = 0; i < 63; i++) {
      chest.add("real" + i);
    }

    assertSame(chest, inMenu.contentsFor(5, chest, KEEP), "27 格箱子加背包是 63 格，不是我们的菜单");
  }

  @Test
  void thePlayerWindowIsUnaffectedByTheMenuSize() {
    HotbarRewriter<String> inMenu = new HotbarRewriter<>(DRIVE_ITEMS, () -> 9);

    assertEquals("P3", inMenu.itemFor(0, 36, "real", KEEP));
    assertFalse(inMenu.isHotbarSlot(0, 35));
  }

  @Test
  void rewritingInPlaceChangesOnlyTheHotbarOfTheGivenList() {
    List<String> window = new ArrayList<>();
    for (int i = 0; i < HotbarRewriter.PLAYER_WINDOW_SIZE; i++) {
      window.add("real" + i);
    }
    List<String> sameList = window;

    boolean rewritten = rewriter.rewriteInPlace(0, window, KEEP);

    assertTrue(rewritten);
    assertSame(sameList, window);
    assertEquals("real35", window.get(35));
    assertEquals("P3", window.get(36));
    assertEquals("EB", window.get(44));
    assertEquals("real45", window.get(45), "副手槽位不改写");
  }

  @Test
  void rewritingInPlaceCopiesEveryDriveItem() {
    List<String> window = new ArrayList<>();
    for (int i = 0; i < HotbarRewriter.PLAYER_WINDOW_SIZE; i++) {
      window.add("real" + i);
    }
    int[] copies = {0};

    rewriter.rewriteInPlace(
        0,
        window,
        item -> {
          copies[0]++;
          return item + "'";
        });

    assertEquals(9, copies[0]);
    assertEquals("N'", window.get(39));
  }

  @Test
  void rewritingInPlaceLeavesOtherWindowsAndShortListsAlone() {
    List<String> other = new ArrayList<>(List.of("a", "b", "c"));
    List<String> shortWindow = new ArrayList<>(List.of("x", "y"));

    assertFalse(rewriter.rewriteInPlace(2, other, KEEP));
    assertFalse(rewriter.rewriteInPlace(0, shortWindow, KEEP));
    assertFalse(rewriter.rewriteInPlace(0, null, KEEP));
    assertEquals(List.of("a", "b", "c"), other);
    assertEquals(List.of("x", "y"), shortWindow);
  }

  @Test
  void rewritingInPlaceReachesTheHotbarOfAnOpenMenuWindow() {
    HotbarRewriter<String> inMenu = new HotbarRewriter<>(DRIVE_ITEMS, () -> 9);
    List<String> window = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      window.add("real" + i);
    }

    assertTrue(inMenu.rewriteInPlace(7, window, KEEP));

    assertEquals("real35", window.get(35));
    assertEquals("P3", window.get(36));
    assertEquals("EB", window.get(44));
  }

  @Test
  void rewritingAnImmutableListFailsLoudlyInsteadOfSilentlyLeakingRealItems() {
    List<String> window = new ArrayList<>();
    for (int i = 0; i < HotbarRewriter.PLAYER_WINDOW_SIZE; i++) {
      window.add("real" + i);
    }

    assertThrows(
        UnsupportedOperationException.class,
        () -> rewriter.rewriteInPlace(0, List.copyOf(window), KEEP));
  }

  @Test
  void aRewrittenWindowIsConfirmedAndARealOneIsNot() {
    List<String> window = new ArrayList<>();
    for (int i = 0; i < HotbarRewriter.PLAYER_WINDOW_SIZE; i++) {
      window.add("real" + i);
    }

    assertFalse(rewriter.showsDriveItems(0, window, String::equals));
    rewriter.rewriteInPlace(0, window, KEEP);
    assertTrue(rewriter.showsDriveItems(0, window, String::equals));
  }

  @Test
  void windowsThatNeedNoRewriteCountAsConfirmed() {
    assertTrue(rewriter.showsDriveItems(3, List.of("a", "b"), String::equals));
    assertTrue(rewriter.showsDriveItems(0, null, String::equals));
  }
}
