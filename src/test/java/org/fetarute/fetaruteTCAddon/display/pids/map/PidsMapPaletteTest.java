package org.fetarute.fetaruteTCAddon.display.pids.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bergerkiller.bukkit.common.map.MapColorPalette;
import java.awt.image.BufferedImage;
import java.util.List;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.junit.jupiter.api.Test;

/** 地图调色板换算：主题色原样命中，线路色取 CIELAB 最近色，整帧换算与逐像素一致。 */
class PidsMapPaletteTest {

  private static final PidsMapPalette PALETTE = PidsMapPalette.minecraft();

  @Test
  void themeColorsAreOnThePalette() {
    for (PidsTheme theme : List.of(PidsTheme.DARK, PidsTheme.LIGHT)) {
      for (int color :
          List.of(
              theme.background(),
              theme.panel(),
              theme.text(),
              theme.muted(),
              theme.outline(),
              theme.inverseBackground(),
              theme.inverseText(),
              theme.inverseMuted(),
              theme.amber(),
              theme.red(),
              theme.info(),
              theme.green(),
              PidsTheme.INK,
              PidsTheme.PAPER)) {
        assertEquals(
            color, PALETTE.rgb(PALETTE.code(color)), () -> Integer.toHexString(color) + " 不在调色板上");
      }
    }
  }

  @Test
  void lineColorsTakeThePerceptuallyNearestEntry() {
    int coral = 0xE5534B;
    byte bkc = MapColorPalette.getColor(0xE5, 0x53, 0x4B);
    byte ours = PALETTE.code(coral);

    assertNotEquals(0xFF0000, PALETTE.rgb(ours), "不应落到纯红");
    assertTrue(
        PidsMapPalette.distance(PidsMapPalette.lab(coral), PidsMapPalette.lab(PALETTE.rgb(ours)))
            <= PidsMapPalette.distance(
                PidsMapPalette.lab(coral), PidsMapPalette.lab(PALETTE.rgb(bkc))));
  }

  @Test
  void convertMatchesPerPixelCodes() {
    BufferedImage frame = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
    int[] colors = {0x191919, 0x191919, 0xF6A000, 0xD920D9, 0x70DEEE, 0x191919};
    frame.setRGB(0, 0, 3, 2, colors, 0, 3);
    byte[] out = new byte[6];

    PALETTE.convert(frame, out);

    byte[] expected = new byte[6];
    for (int i = 0; i < colors.length; i++) {
      expected[i] = PALETTE.code(colors[i]);
    }
    assertArrayEquals(expected, out);
  }

  @Test
  // 渲染器产出的 INT_RGB 整图直接读底层数组；结果必须与走 getRGB 的其他图像类型一致，帧里颜色多到要扩表也一样。
  void directPixelReadMatchesTheGenericPath() {
    int width = 40;
    int height = 30;
    BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    BufferedImage argb = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int color = ((x * 37) << 16) | ((y * 53) << 8) | ((x * y) & 0xFF);
        rgb.setRGB(x, y, color);
        argb.setRGB(x, y, 0xFF000000 | color);
      }
    }
    byte[] direct = new byte[width * height];
    byte[] generic = new byte[width * height];

    PALETTE.convert(rgb, direct);
    PALETTE.convert(argb, generic);

    assertArrayEquals(generic, direct);
  }

  @Test
  void rejectsForeignCodesAndShortBuffers() {
    PidsMapPalette tiny = new PidsMapPalette(new byte[] {4}, new int[] {0x000000});

    assertEquals(4, tiny.code(0xFFFFFF));
    assertThrows(IllegalArgumentException.class, () -> tiny.rgb((byte) 5));
    assertThrows(
        IllegalArgumentException.class,
        () -> tiny.convert(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), new byte[3]));
  }
}
