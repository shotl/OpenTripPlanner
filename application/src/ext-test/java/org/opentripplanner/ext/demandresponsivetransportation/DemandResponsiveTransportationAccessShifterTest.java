package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentripplanner._support.time.ZoneIds;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.DefaultAccessEgress;
import org.opentripplanner.routing.api.request.DemandResponsiveExtData;
import org.opentripplanner.routing.api.request.Passengers;
import org.opentripplanner.routing.api.request.RequestModes;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.street.search.state.TestStateBuilder;

/**
 * Tests for DemandResponsiveTransportationAccessShifter.
 *
 * These tests verify that DRT access times are correctly shifted based on the
 * expected pickup time and that the access duration is adjusted to the actual
 * DRT travel duration returned by the DRT service.
 */
class DemandResponsiveTransportationAccessShifterTest {

  private static final Instant NOW = OffsetDateTime.parse("2023-03-23T17:00:00+01:00").toInstant();
  private static final GenericLocation FROM = new GenericLocation(0d, 0d);
  private static final GenericLocation TO = new GenericLocation(1d, 1d);
  private static final WgsCoordinate STOP_COORDINATE = new WgsCoordinate(0.5, 0.5);

  private final DemandResponsiveTransportationService service =
    new TestDemandResponsiveTransportationService();

  static Stream<Arguments> arrivalDelayTestCases() {
    return Stream.of(
      // Leave now - should shift by the full arrival delay (10 minutes)
      Arguments.of(NOW, TestDemandResponsiveTransportationService.DEFAULT_ARRIVAL_DELAY),
      // Future departure - service still returns +10min delay from desired time
      Arguments.of(
        NOW.plus(Duration.ofMinutes(30)),
        TestDemandResponsiveTransportationService.DEFAULT_ARRIVAL_DELAY
      ),
      // Very future departure - still gets shifted
      Arguments.of(
        NOW.plus(Duration.ofHours(2)),
        TestDemandResponsiveTransportationService.DEFAULT_ARRIVAL_DELAY
      )
    );
  }

  @ParameterizedTest
  @MethodSource("arrivalDelayTestCases")
  void testArrivalDelay(Instant searchTime, Duration expectedDelay) {
    var req = createRouteRequest(searchTime);

    var result = DemandResponsiveTransportationAccessShifter.arrivalDelay(
      req,
      List.of(service),
      NOW,
      STOP_COORDINATE
    );

    assertTrue(result.isSuccess(), "arrivalDelay should succeed");
    assertEquals(
      expectedDelay,
      result.successValue().pickupDelay(),
      "Pickup delay should match expected"
    );
    assertEquals(
      Duration.ofMinutes(30),
      result.successValue().drtTravelDuration(),
      "DRT travel duration should be 30 minutes"
    );
  }

  @Test
  void testShiftAccesses() {
    var drivingState = TestStateBuilder.ofDriving().streetEdge().streetEdge().build();
    var access = new DefaultAccessEgress(0, drivingState);

    RouteRequest req = createRouteRequest(NOW);

    var shifted = DemandResponsiveTransportationAccessShifter.shiftAccesses(
      true,
      List.of(access),
      List.of(service),
      req,
      NOW
    );

    assertEquals(1, shifted.size(), "Should return one shifted access");

    var shiftedAccess = shifted.get(0);
    assertTrue(
      shiftedAccess instanceof DemandResponsiveTransportationAccessAdapter,
      "Shifted access should be wrapped in DemandResponsiveTransportationAccessAdapter"
    );

    // Verify the start time is shifted by the expected delay
    var shiftedStart = shiftedAccess.earliestDepartureTime(
      NOW.atZone(ZoneIds.BERLIN).toLocalTime().toSecondOfDay()
    );
    var expectedStart = NOW.plus(TestDemandResponsiveTransportationService.DEFAULT_ARRIVAL_DELAY)
      .atZone(ZoneIds.BERLIN)
      .toLocalTime();

    assertEquals(
      expectedStart,
      LocalTime.ofSecondOfDay(shiftedStart),
      "Shifted start time should include DRT pickup delay"
    );

    // Verify the duration is the DRT travel duration (30 min), not the car-based duration
    assertEquals(
      1800,
      shiftedAccess.durationInSeconds(),
      "Access duration should be the DRT travel duration (30 min), not car driving time"
    );
  }

  @Test
  void testShiftAccessesOnlyForDrtMode() {
    var walkingState = TestStateBuilder.ofWalking().streetEdge().build();
    var access = new DefaultAccessEgress(0, walkingState);

    RouteRequest req = createRouteRequest(NOW);

    var shifted = DemandResponsiveTransportationAccessShifter.shiftAccesses(
      true,
      List.of(access),
      List.of(service),
      req,
      NOW
    );

    // Walking-only accesses should not be shifted (they don't contain car mode)
    assertEquals(1, shifted.size(), "Should return the access");
    // The original access is returned unchanged (not wrapped)
    assertEquals(access, shifted.get(0), "Walking access should not be wrapped");
  }

  @Test
  void testEgressNotShiftedBeforeRaptor() {
    var drivingState = TestStateBuilder.ofDriving().streetEdge().streetEdge().build();
    var egress = new DefaultAccessEgress(0, drivingState);

    RouteRequest req = createRouteRequest(NOW);

    var result = DemandResponsiveTransportationAccessShifter.shiftAccesses(
      false, // egress
      List.of(egress),
      List.of(service),
      req,
      NOW
    );

    assertEquals(1, result.size(), "Egress should be returned unmodified");
    assertSame(
      egress,
      result.get(0),
      "Egress shifting is deferred to leg decoration, so the original egress should be returned"
    );
  }

  @Test
  void testArriveByNotShifted() {
    var req = createRouteRequest(NOW);
    req.setArriveBy(true);

    var result = DemandResponsiveTransportationAccessShifter.arrivalDelay(
      req,
      List.of(service),
      NOW,
      STOP_COORDINATE
    );

    assertTrue(result.isSuccess(), "arrivalDelay should succeed");
    assertEquals(
      Duration.ZERO,
      result.successValue().pickupDelay(),
      "arriveBy requests should not be shifted (return zero delay)"
    );
  }

  private RouteRequest createRouteRequest(Instant searchTime) {
    var req = new RouteRequest();
    req.setDateTime(searchTime);
    req.setFrom(FROM);
    req.setTo(TO);
    req
      .journey()
      .setModes(
        RequestModes.of().withAccessMode(StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION).build()
      );

    // Set up DRT-specific data
    req.setDemandResponsiveExtData(
      new DemandResponsiveExtData(
        "test-app-id", // paxAppId
        "test-user-id", // userId
        "test-area-id", // areaId
        "SHARED", // rideType
        new Passengers(1, 0)
      )
    );

    return req;
  }
}
