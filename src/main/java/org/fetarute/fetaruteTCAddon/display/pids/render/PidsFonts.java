package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;

/**
 * 站台屏像素字体（内置 Fusion Pixel）。
 *
 * <p>只有 10px、12px 两档原生字号；字号必须是其中一档的整数倍（10、12、20、24、30、36…），按整数倍渲染时笔画与像素对齐， 12 的倍数优先用 12px
 * 档。英文行固定用拉丁版；中文行用配置的字形版本，开启按内容判断时逐段文字选简体、繁体或日文字形（{@link PidsGlyphDetector}）。
 *
 * <p>字体文件按需加载、全进程只加载一次：每个文件数 MB，重载配置不应重复解析。
 *
 * <p>文字按“文本框顶边”定位，与设计稿 CSS {@code line-height: 1} 的摆法一致：基线位于顶边下方 {@link #baseline}。
 */
public final class PidsFonts {

  /** 文字所属的书写系统。 */
  public enum Script {
    /** 中日文行（也可含数字与拉丁字母）。 */
    CJK,
    /** 英文行。 */
    LATIN
  }

  private static final String RESOURCE = "pids/fonts/fusion-pixel-%dpx-proportional-%s.ttf";
  private static final String LATIN_SUFFIX = "latin";
  private static final String ELLIPSIS = "…";

  /** 码位低于它的字符（拉丁字母、数字与常用标点）用拉丁版字体。 */
  private static final int LATIN_LIMIT = 0x2000;

  private static final int LEFT_SINGLE_QUOTE = 0x2018;
  private static final int DOUBLE_HIGH_QUOTE = 0x201F;
  private static final int ELLIPSIS_CHAR = 0x2026;

  /** 原生字体，按资源路径缓存，跨实例共用。 */
  private static final ConcurrentMap<String, Font> BASE_FONTS = new ConcurrentHashMap<>();

  private final PidsGlyphForm cjkGlyphs;
  private final Optional<PidsGlyphDetector> detector;
  private final Function<String, InputStream> resources;
  private final ConcurrentMap<String, Font> sized = new ConcurrentHashMap<>();
  private final FontRenderContext context = new FontRenderContext(null, false, false);

  /**
   * @param cjkGlyphs 中文行的字形版本（开启按内容判断时为判断不出时的版本）
   * @param detector 按内容判断字形版本；为空时一律用 {@code cjkGlyphs}
   * @param resources 按资源路径打开字体文件
   */
  public PidsFonts(
      PidsGlyphForm cjkGlyphs,
      Optional<PidsGlyphDetector> detector,
      Function<String, InputStream> resources) {
    this.cjkGlyphs = Objects.requireNonNull(cjkGlyphs, "cjkGlyphs");
    this.detector = Objects.requireNonNull(detector, "detector");
    this.resources = Objects.requireNonNull(resources, "resources");
  }

  /** 从插件 jar 读取内置字体，中文行固定用一个字形版本。 */
  public static PidsFonts builtIn(PidsGlyphForm cjkGlyphs) {
    return builtIn(cjkGlyphs, false);
  }

  /**
   * 从插件 jar 读取内置字体。
   *
   * @param cjkGlyphs 默认字形版本
   * @param detectGlyphs 是否按每段文字的内容判断字形版本（{@link PidsGlyphDetector}）
   */
  public static PidsFonts builtIn(PidsGlyphForm cjkGlyphs, boolean detectGlyphs) {
    ClassLoader loader = PidsFonts.class.getClassLoader();
    return new PidsFonts(
        cjkGlyphs,
        detectGlyphs ? Optional.of(PidsGlyphDetector.builtIn()) : Optional.empty(),
        loader::getResourceAsStream);
  }

  /** 字号能否像素对齐渲染：必须是 10 或 12 的正整数倍。 */
  public static boolean supports(int size) {
    return size > 0 && (size % 12 == 0 || size % 10 == 0);
  }

  /**
   * 指定书写系统与字号的字体。
   *
   * @throws IllegalArgumentException 字号不是 10 或 12 的整数倍
   */
  public Font font(Script script, int size) {
    return font(script == Script.LATIN ? LATIN_SUFFIX : cjkGlyphs.fileSuffix(), size);
  }

  /**
   * 一段文字该用的字体：全是拉丁字符（含标点、数字）用拉丁版；否则按内容判断字形版本，未开启判断时用默认版本。
   *
   * @throws IllegalArgumentException 字号不是 10 或 12 的整数倍
   */
  public Font fontFor(String text, int size) {
    if (text.chars().allMatch(PidsFonts::latin)) {
      return font(LATIN_SUFFIX, size);
    }
    PidsGlyphForm form = detector.map(found -> found.detect(text, cjkGlyphs)).orElse(cjkGlyphs);
    return font(form.fileSuffix(), size);
  }

  /**
   * 可以用拉丁版字体画的字符：码位低于 U+2000 的，加上英文里常见的弯引号（U+2018–U+201F）与省略号（U+2026）。
   *
   * <p>省略号必须算在内：英文被省略后末尾带“…”，若因此换成中文字体，量宽与实际绘制就不是同一种字体（中文字体的“…”宽出 4 像素）。
   * 破折号“—”不算：到站列与回库色牌上的“—”按中文字体的全宽显示。
   */
  private static boolean latin(int c) {
    return c < LATIN_LIMIT
        || (c >= LEFT_SINGLE_QUOTE && c <= DOUBLE_HIGH_QUOTE)
        || c == ELLIPSIS_CHAR;
  }

  private Font font(String suffix, int size) {
    if (!supports(size)) {
      throw new IllegalArgumentException("像素字体只支持 10 或 12 的整数倍字号: " + size);
    }
    int nativeSize = size % 12 == 0 ? 12 : 10;
    String path = String.format(RESOURCE, nativeSize, suffix);
    return sized.computeIfAbsent(
        path + "@" + size,
        key -> BASE_FONTS.computeIfAbsent(path, this::load).deriveFont((float) size));
  }

  /** 文本宽度（像素）。 */
  public int width(Font font, String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return (int) Math.ceil(font.getStringBounds(text, context).getWidth() - 1e-6);
  }

  /** 基线到文本框顶边的距离：文本框高为字号，内容区（上伸部加下伸部）居中其中。 */
  public int baseline(Font font) {
    LineMetrics metrics = font.getLineMetrics("国", context);
    return Math.round((metrics.getAscent() - metrics.getDescent() + font.getSize()) / 2f);
  }

  /** 超出宽度时截断并补省略号；放得下时原样返回。 */
  public String ellipsize(Font font, String text, int maxWidth) {
    if (width(font, text) <= maxWidth) {
      return text;
    }
    int end = text.length();
    while (end > 0 && width(font, text.substring(0, end) + ELLIPSIS) > maxWidth) {
      end = text.offsetByCodePoints(end, -1);
    }
    return end == 0 ? "" : text.substring(0, end).stripTrailing() + ELLIPSIS;
  }

  /** 英文按词截断：超出宽度时退到最近的词边界再补省略号；一个词也放不下时按字符截断。 */
  public String ellipsizeWords(Font font, String text, int maxWidth) {
    if (width(font, text) <= maxWidth) {
      return text;
    }
    int cut = text.lastIndexOf(' ');
    while (cut > 0) {
      String candidate = text.substring(0, cut).stripTrailing() + ELLIPSIS;
      if (width(font, candidate) <= maxWidth) {
        return candidate;
      }
      cut = text.lastIndexOf(' ', cut - 1);
    }
    return ellipsize(font, text, maxWidth);
  }

  private Font load(String path) {
    try (InputStream stream = resources.apply(path)) {
      if (stream == null) {
        throw new IllegalStateException("缺少内置字体: " + path);
      }
      return Font.createFont(Font.TRUETYPE_FONT, stream);
    } catch (IOException ex) {
      throw new UncheckedIOException("读取内置字体失败: " + path, ex);
    } catch (FontFormatException ex) {
      throw new IllegalStateException("内置字体格式无效: " + path, ex);
    }
  }
}
