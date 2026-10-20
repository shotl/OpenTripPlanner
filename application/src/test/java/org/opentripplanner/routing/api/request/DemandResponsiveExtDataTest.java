package org.opentripplanner.routing.api.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DemandResponsiveExtDataTest {

  @ParameterizedTest
  @ValueSource(
    strings = { "6f1c0e9a-3b2d-4c55-9e1f-2a7b8c9d0e1f", "a", "quote:1.2_3-4", "ABCxyz019" }
  )
  void keepsAValidRequestId(String requestId) {
    assertEquals(requestId, DemandResponsiveExtData.validRequestId(requestId));
    assertEquals(
      requestId,
      DemandResponsiveExtData.builder().requestId(requestId).build().requestId()
    );
  }

  @ParameterizedTest
  @ValueSource(strings = { "", " ", "a b", "a\nb", "a\r\nX-Evil: 1", "é", "a/b", "a,b" })
  void dropsAnInvalidRequestId(String requestId) {
    assertNull(DemandResponsiveExtData.validRequestId(requestId));
    assertNull(DemandResponsiveExtData.builder().requestId(requestId).build().requestId());
  }

  @Test
  void dropsARequestIdOver128Characters() {
    assertEquals("a".repeat(128), DemandResponsiveExtData.validRequestId("a".repeat(128)));
    assertNull(DemandResponsiveExtData.validRequestId("a".repeat(129)));
    assertNull(DemandResponsiveExtData.validRequestId(null));
  }

  @Test
  void theRequestIdIsPartOfTheValue() {
    var a = DemandResponsiveExtData.builder().areaId("x").requestId("q-1").build();
    var b = DemandResponsiveExtData.builder().areaId("x").requestId("q-2").build();

    assertNotEquals(a, b);
    assertEquals(a, DemandResponsiveExtData.builder().areaId("x").requestId("q-1").build());
    assertNull(DemandResponsiveExtData.builder().areaId("x").build().requestId());
  }
}
