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

  @ParameterizedTest
  @ValueSource(
    strings = {
      "Europe/Madrid",
      "America/Argentina/Buenos_Aires",
      "America/Port-au-Prince",
      "Etc/GMT+1",
      "UTC",
    }
  )
  void keepsAValidAreaTimezone(String zone) {
    assertEquals(zone, DemandResponsiveExtData.validAreaTimezone(zone));
    assertEquals(zone, DemandResponsiveExtData.builder().areaTimezone(zone).build().areaTimezone());
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "",
      " ",
      "+01:00",
      "+01",
      "-0300",
      "Z",
      "GMT+1",
      "UTC+01:00",
      "UT+1",
      "europe/madrid",
      "Europe/Nowhere",
      "Europe/Madrid ",
      "Europe/Madrid\r\nX-Evil: 1",
      "Europe.Madrid",
      "Europe/Madrid,Europe/Rome",
    }
  )
  void dropsAnInvalidAreaTimezone(String zone) {
    assertNull(DemandResponsiveExtData.validAreaTimezone(zone));
    assertNull(DemandResponsiveExtData.builder().areaTimezone(zone).build().areaTimezone());
  }

  @Test
  void dropsAnAreaTimezoneOver64Characters() {
    assertNull(DemandResponsiveExtData.validAreaTimezone("Europe/" + "A".repeat(58)));
    assertNull(DemandResponsiveExtData.validAreaTimezone(null));
  }

  @Test
  void theAreaTimezoneHeaderIsTheAreaAndItsZone() {
    assertEquals(
      "64f0c1d2e3a4b5c6d7e8f901=Europe/Madrid",
      DemandResponsiveExtData.areaTimezoneHeader("64f0c1d2e3a4b5c6d7e8f901", "Europe/Madrid")
    );
    assertNull(DemandResponsiveExtData.areaTimezoneHeader(null, "Europe/Madrid"));
    assertNull(DemandResponsiveExtData.areaTimezoneHeader("", "Europe/Madrid"));
    assertNull(DemandResponsiveExtData.areaTimezoneHeader("a=b", "Europe/Madrid"));
    assertNull(DemandResponsiveExtData.areaTimezoneHeader("area\r\nX: 1", "Europe/Madrid"));
    assertNull(DemandResponsiveExtData.areaTimezoneHeader("area-1", null));
    assertNull(DemandResponsiveExtData.areaTimezoneHeader("area-1", "+01:00"));
  }

  @Test
  void theAreaTimezoneIsPartOfTheValue() {
    var a = DemandResponsiveExtData.builder().areaId("x").areaTimezone("Europe/Madrid").build();
    var b = DemandResponsiveExtData.builder().areaId("x").areaTimezone("Europe/Rome").build();

    assertNotEquals(a, b);
    assertEquals(
      a,
      DemandResponsiveExtData.builder().areaId("x").areaTimezone("Europe/Madrid").build()
    );
    assertNull(DemandResponsiveExtData.builder().areaId("x").build().areaTimezone());
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
