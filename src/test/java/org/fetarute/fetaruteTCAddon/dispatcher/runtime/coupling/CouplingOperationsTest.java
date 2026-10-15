package org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 插件自己发起的拆分/联挂登记：只在调用期间有效，按对象身份认。 */
class CouplingOperationsTest {

  @Test
  void groupsAreInvolvedOnlyWhileTheScopeIsOpen() {
    CouplingOperations operations = new CouplingOperations();
    Object source = new Object();
    Object other = new Object();

    try (CouplingOperations.Scope ignored = operations.begin(List.of(source))) {
      assertTrue(operations.involves(source));
      assertTrue(operations.involvesAny(other, source));
      assertFalse(operations.involves(other));
    }
    assertFalse(operations.involves(source), "关闭后撤销登记");
  }

  @Test
  void identityNotEqualityDecides() {
    CouplingOperations operations = new CouplingOperations();
    List<String> registered = new java.util.ArrayList<>(List.of("T-1"));
    List<String> sameName = new java.util.ArrayList<>(List.of("T-1"));

    try (CouplingOperations.Scope ignored = operations.begin(List.of(registered))) {
      assertTrue(operations.involves(registered));
      assertFalse(operations.involves(sameName), "同名的另一个编组不算");
    }
  }

  @Test
  void anInnerScopeDoesNotReleaseGroupsOfTheOuterOne() {
    CouplingOperations operations = new CouplingOperations();
    Object group = new Object();

    try (CouplingOperations.Scope outer = operations.begin(List.of(group))) {
      try (CouplingOperations.Scope inner = operations.begin(List.of(group))) {
        assertTrue(operations.involves(group));
      }
      assertTrue(operations.involves(group), "内层没加进新编组，关闭时不撤外层的");
    }
    assertFalse(operations.involves(group));
    assertFalse(operations.involvesAny((Object[]) null));
    assertFalse(operations.involvesAny(Arrays.asList((Object) null).toArray()));
  }
}
