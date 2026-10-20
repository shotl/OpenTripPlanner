package org.opentripplanner.apis.gtfs.mapping.routerequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.apis.gtfs.mapping.routerequest.RouteRequestMapperTest.createArgsCopy;
import static org.opentripplanner.apis.gtfs.mapping.routerequest.RouteRequestMapperTest.executionContext;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentripplanner.routing.api.request.RouteRequest;

class RouteRequestMapperDrtTest {

  private static RouteRequest map(Map<String, Object> drt) {
    var args = createArgsCopy(RouteRequestMapperTest.ARGS);
    args.put("drt", drt);
    var env = executionContext(args, Locale.ENGLISH, RouteRequestMapperTest.CONTEXT);
    return RouteRequestMapper.toRouteRequest(env, RouteRequestMapperTest.CONTEXT);
  }

  private static Map<String, Object> drt(String requestId) {
    return drt(requestId, null);
  }

  private static Map<String, Object> drt(String requestId, String areaTimezone) {
    var drt = new HashMap<String, Object>();
    drt.put("areaId", "area-1");
    drt.put("userId", "user-1");
    drt.put("finalLegs", true);
    drt.put("passengers", Map.of("regular", 1, "wheelchair", 0));
    if (requestId != null) {
      drt.put("requestId", requestId);
    }
    if (areaTimezone != null) {
      drt.put("areaTimezone", areaTimezone);
    }
    return drt;
  }

  @Test
  void mapsTheRequestId() {
    var ext = map(drt("6f1c0e9a-3b2d-4c55-9e1f-2a7b8c9d0e1f")).demandResponsiveExtData();

    assertEquals("6f1c0e9a-3b2d-4c55-9e1f-2a7b8c9d0e1f", ext.requestId());
    assertEquals("area-1", ext.areaId());
    assertTrue(ext.finalLegs(), "the other DRT fields still map");
  }

  @Test
  void noRequestIdWithoutOne() {
    assertNull(map(drt(null)).demandResponsiveExtData().requestId());
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "has space", "line\r\nX-Evil: 1", "ñ", "a/b" })
  void ignoresAnInvalidRequestId(String requestId) {
    var ext = map(drt(requestId)).demandResponsiveExtData();

    assertNull(ext.requestId());
    assertEquals("area-1", ext.areaId(), "the request itself still maps");
  }

  @Test
  void ignoresARequestIdLongerThan128Characters() {
    assertEquals(128, map(drt("a".repeat(128))).demandResponsiveExtData().requestId().length());
    assertNull(map(drt("a".repeat(129))).demandResponsiveExtData().requestId());
  }

  @Test
  void mapsTheAreaTimezone() {
    var ext = map(drt("quote-1", "Europe/Madrid")).demandResponsiveExtData();

    assertEquals("Europe/Madrid", ext.areaTimezone());
    assertEquals("quote-1", ext.requestId());
    assertEquals("area-1", ext.areaId());
  }

  @Test
  void noAreaTimezoneWithoutOne() {
    assertNull(map(drt("quote-1")).demandResponsiveExtData().areaTimezone());
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "+01:00", "Z", "GMT+1", "Europe/Nowhere", "Europe/Madrid\r\nX: 1" })
  void ignoresAnInvalidAreaTimezone(String areaTimezone) {
    var ext = map(drt("quote-1", areaTimezone)).demandResponsiveExtData();

    assertNull(ext.areaTimezone());
    assertEquals("quote-1", ext.requestId(), "the request itself still maps");
  }

  @Test
  void noDrtInputWithoutTheDrtArgument() {
    var env = executionContext(
      createArgsCopy(RouteRequestMapperTest.ARGS),
      Locale.ENGLISH,
      RouteRequestMapperTest.CONTEXT
    );
    assertNull(RouteRequestMapper.drtRequestId(env));
    assertNull(RouteRequestMapper.drtAreaTimezone(env));
    assertNull(
      RouteRequestMapper.toRouteRequest(
        env,
        RouteRequestMapperTest.CONTEXT
      ).demandResponsiveExtData()
    );
  }
}
