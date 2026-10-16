package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.routing.api.request.PassengerFareType;

/** The per-request cache sends only the legs it has not estimated yet, once per rounded key. */
class PerRequestBatchTest {

  private static final Instant AT = Instant.ofEpochSecond(1_760_000_000L);

  /** Records every batch it is asked and answers each leg with an estimate named by its index. */
  private static class Recording implements DemandResponsiveTransportationService {

    final List<Integer> batchSizes = new ArrayList<>();
    int answered = 0;

    @Override
    public ShotlArrivalEstimateResponse arrivalTimes(
      String paxAppId,
      String areaId,
      String userId,
      String rideType,
      WgsCoordinate fromCoordinate,
      WgsCoordinate toCoordinate,
      int regularPassengers,
      int wheelchairPassengers,
      Instant desiredPickupTime,
      DrtRequestContext context,
      List<PassengerFareType> passengerFareType,
      boolean pickupShift,
      String acceptLanguage
    ) {
      throw new AssertionError("the cache must ask in batches");
    }

    @Override
    public List<ShotlArrivalEstimateResponse> arrivalTimesBatch(
      String paxAppId,
      String areaId,
      String userId,
      String rideType,
      int regularPassengers,
      int wheelchairPassengers,
      DrtRequestContext context,
      List<PassengerFareType> passengerFareType,
      boolean pickupShift,
      String acceptLanguage,
      List<DrtEstimateItem> items
    ) {
      batchSizes.add(items.size());
      var out = new ArrayList<ShotlArrivalEstimateResponse>();
      for (var item : items) {
        int n = answered++;
        out.add(
          n == 1
            ? null
            : new ShotlArrivalEstimateResponse(
              "e" + n,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null
            )
        );
      }
      return out;
    }
  }

  private static DrtEstimateItem to(double lat) {
    return new DrtEstimateItem(new WgsCoordinate(41.38, 2.17), new WgsCoordinate(lat, 2.18), AT);
  }

  private static List<ShotlArrivalEstimateResponse> ask(
    PerRequestDemandResponsiveTransportationService s,
    List<DrtEstimateItem> items
  ) {
    return s.arrivalTimesBatch(
      "app",
      "area",
      "user",
      "RIDE_NOW",
      1,
      0,
      DrtRequestContext.LEG_DECORATING,
      List.of(),
      true,
      "es",
      items
    );
  }

  @Test
  void sendsOnlyTheMissesOncePerKey() {
    var delegate = new Recording();
    var cache = new PerRequestDemandResponsiveTransportationService(delegate);

    // 41.40 and 41.400001 round to the same ~10 m key: one estimate.
    var first = ask(cache, List.of(to(41.40), to(41.400001), to(41.41)));
    var second = ask(cache, List.of(to(41.41), to(41.42)));

    assertEquals(List.of(2, 1), delegate.batchSizes);
    assertEquals("e0", first.get(0).id());
    assertSame(first.get(0), first.get(1));
    assertNull(first.get(2), "a leg without an estimate is remembered as such");
    assertNull(second.get(0));
    assertEquals("e2", second.get(1).id());
  }
}
