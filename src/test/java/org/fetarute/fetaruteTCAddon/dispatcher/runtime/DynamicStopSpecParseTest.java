package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 测试 {@link RuntimeDispatchService} 中的 DYNAMIC stop 规范解析。
 *
 * <p>通过反射测试私有方法 {@code parseDynamicStopSpec}。
 */
class DynamicStopSpecParseTest {

  @Test
  void parseNewFormatStationWithRange() throws Exception {
    // DYNAMIC:OP:S:STATION:[1:3] -> 去掉 DYNAMIC: 后变成 OP:S:STATION:[1:3]
    Optional<?> result = invokeParse("OP:S:STATION:[1:3]");
    assertTrue(result.isPresent());
    Object spec = result.get();
    assertEquals("OP", getField(spec, "operatorCode"));
    assertEquals("S", getField(spec, "nodeType"));
    assertEquals("STATION", getField(spec, "nodeName"));
    assertEquals(1, getField(spec, "fromTrack"));
    assertEquals(3, getField(spec, "toTrack"));
  }

  @Test
  void parseNewFormatDepotWithRange() throws Exception {
    // DYNAMIC:OP:D:DEPOT:[2:4]
    Optional<?> result = invokeParse("OP:D:DEPOT:[2:4]");
    assertTrue(result.isPresent());
    Object spec = result.get();
    assertEquals("OP", getField(spec, "operatorCode"));
    assertEquals("D", getField(spec, "nodeType"));
    assertEquals("DEPOT", getField(spec, "nodeName"));
    assertEquals(2, getField(spec, "fromTrack"));
    assertEquals(4, getField(spec, "toTrack"));
  }

  @Test
  void parseNewFormatSingleTrack() throws Exception {
    // OP:S:STATION:2
    Optional<?> result = invokeParse("OP:S:STATION:2");
    assertTrue(result.isPresent());
    Object spec = result.get();
    assertEquals("OP", getField(spec, "operatorCode"));
    assertEquals("S", getField(spec, "nodeType"));
    assertEquals("STATION", getField(spec, "nodeName"));
    assertEquals(2, getField(spec, "fromTrack"));
    assertEquals(2, getField(spec, "toTrack"));
  }

  @Test
  void parseLegacyFormatWithRange() throws Exception {
    // 旧格式兼容: OP:STATION:[1:3] (无 S/D 类型标识)
    Optional<?> result = invokeParse("OP:STATION:[1:3]");
    assertTrue(result.isPresent());
    Object spec = result.get();
    assertEquals("OP", getField(spec, "operatorCode"));
    assertEquals("S", getField(spec, "nodeType")); // 默认 Station
    assertEquals("STATION", getField(spec, "nodeName"));
    assertEquals(1, getField(spec, "fromTrack"));
    assertEquals(3, getField(spec, "toTrack"));
  }

  @Test
  void parseLegacyFormatSingleTrack() throws Exception {
    // 旧格式: OP:STATION:2
    Optional<?> result = invokeParse("OP:STATION:2");
    assertTrue(result.isPresent());
    Object spec = result.get();
    assertEquals("OP", getField(spec, "operatorCode"));
    assertEquals("S", getField(spec, "nodeType"));
    assertEquals("STATION", getField(spec, "nodeName"));
    assertEquals(2, getField(spec, "fromTrack"));
    assertEquals(2, getField(spec, "toTrack"));
  }

  @Test
  void parseWithDynamicPrefix() throws Exception {
    // 带 DYNAMIC: 前缀（应被去掉）
    Optional<?> result = invokeParse("DYNAMIC:OP:S:STATION:[1:3]");
    assertTrue(result.isPresent());
    Object spec = result.get();
    assertEquals("OP", getField(spec, "operatorCode"));
    assertEquals("S", getField(spec, "nodeType"));
    assertEquals("STATION", getField(spec, "nodeName"));
  }

  @Test
  void parseWithRangeNoBrackets() throws Exception {
    // OP:S:STATION:1:3 (范围无方括号)
    Optional<?> result = invokeParse("OP:S:STATION:1:3");
    assertTrue(result.isPresent());
    Object spec = result.get();
    assertEquals(1, getField(spec, "fromTrack"));
    assertEquals(3, getField(spec, "toTrack"));
  }

  @Test
  void parseReturnsEmptyForInvalid() throws Exception {
    assertTrue(invokeParse("").isEmpty());
    assertTrue(invokeParse("   ").isEmpty());
    assertTrue(invokeParse(null).isEmpty());
    assertTrue(invokeParse("INVALID").isEmpty());
  }

  @Test
  void parseReverseRangeNormalized() throws Exception {
    // OP:S:STATION:[5:2] -> fromTrack=2, toTrack=5
    Optional<?> result = invokeParse("OP:S:STATION:[5:2]");
    assertTrue(result.isPresent());
    Object spec = result.get();
    assertEquals(2, getField(spec, "fromTrack"));
    assertEquals(5, getField(spec, "toTrack"));
  }

  @SuppressWarnings("unchecked")
  private Optional<?> invokeParse(String raw) throws Exception {
    Method method =
        RuntimeDispatchService.class.getDeclaredMethod("parseDynamicStopSpec", String.class);
    method.setAccessible(true);
    return (Optional<?>) method.invoke(null, raw);
  }

  private Object getField(Object obj, String fieldName) throws Exception {
    var method = obj.getClass().getMethod(fieldName);
    return method.invoke(obj);
  }
}
