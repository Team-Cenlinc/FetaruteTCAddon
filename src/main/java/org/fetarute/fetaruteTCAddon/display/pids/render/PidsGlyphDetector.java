package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Objects;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;

/**
 * 按文字内容选中日文字形版本。
 *
 * <ul>
 *   <li>含假名：日文
 *   <li>简体专用字多于繁体专用字：简体；反之：繁体
 *   <li>两者都没有或一样多（“湖”“站”这类简繁同形的字）：用默认版本
 * </ul>
 *
 * <p>默认版本为日文时，只含繁体专用字的文字仍按日文：日文汉字多与繁体同形（如“澤”），没有假名的日文地名分不出来。
 *
 * <p>间隔号“・”与长音“ー”在中文名里也会出现，不算假名。
 *
 * <p>简繁专用字取自 OpenCC 的单字简繁对照表：只出现在简体一侧的为简体专用字，只出现在繁体一侧的为繁体专用字。表约四千行，启动时解析一次， 判定只是逐字查位图。
 */
public final class PidsGlyphDetector {

  /** OpenCC 单字简繁对照表（原样内置）。 */
  public static final String RESOURCE = "pids/text/STCharacters.txt";

  private static final int KATAKANA_MIDDLE_DOT = 0x30FB;
  private static final int PROLONGED_SOUND_MARK = 0x30FC;

  private final BitSet simplifiedOnly;
  private final BitSet traditionalOnly;

  private PidsGlyphDetector(BitSet simplifiedOnly, BitSet traditionalOnly) {
    this.simplifiedOnly = simplifiedOnly;
    this.traditionalOnly = traditionalOnly;
  }

  /** 内置对照表，全进程解析一次。 */
  public static PidsGlyphDetector builtIn() {
    return BuiltIn.DETECTOR;
  }

  private static final class BuiltIn {
    private static final PidsGlyphDetector DETECTOR =
        load(PidsGlyphDetector.class.getClassLoader()::getResourceAsStream);
  }

  /**
   * @param resources 按资源路径打开文件
   * @throws IllegalStateException 缺少对照表
   */
  public static PidsGlyphDetector load(Function<String, InputStream> resources) {
    try (InputStream stream = resources.apply(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("缺少内置简繁对照表: " + RESOURCE);
      }
      return parse(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
    } catch (IOException ex) {
      throw new UncheckedIOException("读取简繁对照表失败: " + RESOURCE, ex);
    }
  }

  /** 解析 OpenCC 格式：每行“简体字 Tab 繁体字（空格分隔）”，{@code #} 开头为注释。 */
  static PidsGlyphDetector parse(BufferedReader reader) throws IOException {
    BitSet simplified = new BitSet();
    BitSet traditional = new BitSet();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      int tab = line.indexOf('\t');
      if (tab <= 0) {
        continue;
      }
      line.substring(0, tab).codePoints().forEach(simplified::set);
      line.substring(tab + 1).codePoints().filter(cp -> cp != ' ').forEach(traditional::set);
    }
    BitSet simplifiedOnly = (BitSet) simplified.clone();
    simplifiedOnly.andNot(traditional);
    BitSet traditionalOnly = (BitSet) traditional.clone();
    traditionalOnly.andNot(simplified);
    return new PidsGlyphDetector(simplifiedOnly, traditionalOnly);
  }

  /**
   * @param text 一段文字（一个站名、一个状态词）
   * @param fallback 判断不出时的版本
   */
  public PidsGlyphForm detect(String text, PidsGlyphForm fallback) {
    Objects.requireNonNull(fallback, "fallback");
    if (text == null || text.isEmpty()) {
      return fallback;
    }
    int simplified = 0;
    int traditional = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (isKana(cp)) {
        return PidsGlyphForm.JA;
      }
      if (simplifiedOnly.get(cp)) {
        simplified++;
      } else if (traditionalOnly.get(cp)) {
        traditional++;
      }
    }
    if (simplified > traditional) {
      return PidsGlyphForm.ZH_HANS;
    }
    if (traditional > simplified) {
      return fallback == PidsGlyphForm.JA && simplified == 0
          ? PidsGlyphForm.JA
          : PidsGlyphForm.ZH_HANT;
    }
    return fallback;
  }

  private static boolean isKana(int cp) {
    if (cp == KATAKANA_MIDDLE_DOT || cp == PROLONGED_SOUND_MARK) {
      return false;
    }
    return (cp >= 0x3040 && cp <= 0x30FF)
        || (cp >= 0x31F0 && cp <= 0x31FF)
        || (cp >= 0xFF66 && cp <= 0xFF9F);
  }
}
