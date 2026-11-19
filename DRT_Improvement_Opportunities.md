# DRT mode: design improvement opportunities

A backlog of possible refactors of the DRT mode, whose design is in `DRT_Design.md` (the items
keep the numbering of its section 19): none of this is a working rule, and
none of it is to be applied unless asked. Line numbers were correct when each item was written;
find the code by symbol.

### 19.1 Service Interface Coupled to Shotl (High Impact)

**Problem**: `DemandResponsiveTransportationService.arrivalTimes()` returns `ShotlArrivalEstimateResponse` directly. Every class that consumes the interface (DecorateWithDRT, AccessShifter, RoutingWorker, DRTLeg, AccessAdapter) depends on Shotl-specific types. Adding a second DRT provider (e.g., Via, Padam) would require either returning Shotl types from a non-Shotl provider or changing the interface (breaking all consumers).

**Suggestion**: Introduce a provider-agnostic `DrtEstimate` record that captures the essential fields (expected pickup/dropoff times, walking durations, ride duration, scheduled locations, vehicle ID). The Shotl service maps its response to this generic type. GraphQL response serialization maps from the generic type. The Shotl-specific response stays internal to `ShotlService`.

```
Current:  ShotlService -> ShotlArrivalEstimateResponse -> DecorateWithDRT -> DRTLeg -> GraphQL
Proposed: ShotlService -> ShotlApiResponse -> map to DrtEstimate -> DecorateWithDRT -> DRTLeg -> GraphQL
```

### 19.2 Hardcoded `services.get(0)` (High Impact)

**Problem**: Both `DemandResponsiveTransportationAccessShifter.shiftTime()` (line 174) and `RoutingWorker.shiftDirectDrtItineraries()` (line 296) always use the first service in the list: `var service = services.get(0)`. The config supports a list of services, but only the first one is ever used in these critical paths.

Meanwhile, `DecorateWithDRT.filter()` iterates all services via `parallelStream().flatMap()`, which would **duplicate itineraries** if multiple services were configured (each service decorates each itinerary independently, producing N copies).

**Suggestion**: Either:
- Support a single service (simplify the list to a single optional) and make it explicit, or
- Implement proper multi-provider logic: select the appropriate provider per leg based on geographic area or service configuration, rather than always picking index 0.

### 19.3 Duplicated Logic in Three Places (Medium Impact)

**Problem**: The DRT shifting logic (call Shotl API, create DRTLeg, fix temporal overlaps, update cost delta) is implemented independently in three places:

1. `RoutingWorker.shiftDirectDrtItinerary()` — for direct DRT
2. `DecorateWithDRT.decorateLegWithRideEstimate()` — for egress DRT (and undecorated access)
3. `DemandResponsiveTransportationAccessShifter.shiftTime()` — for access DRT

Each extracts `demandResponsiveExtData` fields manually (paxAppId, areaId, userId, etc.), constructs the API call with 12 parameters, handles null responses, and computes generalized cost. The temporal overlap fixing method is literally copy-pasted between `RoutingWorker` and `DecorateWithDRT`.

**Suggestion**: Extract a shared `DrtLegFactory` or similar utility that encapsulates "given a StreetLeg and a Shotl response, produce a DRTLeg with correct times and cost". Similarly, extract the 12-parameter API call into a method that takes `DemandResponsiveExtData` + coordinates + time, reducing the boilerplate at each call site. The `fixTemporalOverlaps` method should live in one place and be called from both.

### 19.4 Mode Detection Too Broad (Medium Impact)

**Problem**: `DecorateWithDRT.decorateLegWithRideEstimate()` checks `sl.getMode().isInCar()`, which matches **any** car mode (CAR, CAR_TO_PARK, CAR_PICKUP, CAR_HAILING, DRT). If a user requested mixed modes (e.g., CAR_TO_PARK access + DRT egress), the decorator would incorrectly try to call Shotl for the park-and-ride car leg.

In practice this is unlikely because the current UI always sends DRT for all car positions, but it is a latent bug. The same issue exists in `RoutingWorker.shiftDirectDrtItinerary()` (line 328).

**Suggestion**: Either check the request's street mode to confirm it's DRT before decorating, or add a marker/flag to legs that were generated from a DRT street search, so only those legs get decorated.

### 19.5 Request Object Mutation (Medium Impact)

**Problem**: In `RoutingWorker.routeDirectStreet()` (line 262), the code temporarily mutates the shared `request` object to run a walk search:

```java
request.journey().direct().setMode(StreetMode.WALK);
try {
    itineraries.addAll(DirectStreetRouter.route(serverContext, request));
} finally {
    request.journey().direct().setMode(savedMode);
}
```

This is a race condition risk if the request object is accessed concurrently (e.g., by the parallel transit routing that may be running simultaneously). Even with the try/finally, an exception in another thread reading the mode during the WALK window could see the wrong value.

**Suggestion**: Create a copy of the request with the walk mode instead of mutating in place, or construct a separate walk-only request.

### 19.6 No Circuit Breaker or Timeout Strategy (Medium Impact)

**Problem**: The Shotl HTTP client has a 60-second timeout. If Shotl is down or very slow, every DRT API call blocks for up to 60 seconds. During access shifting, this happens per transit stop (potentially 10-30 stops in parallel). During decoration, it happens per itinerary per car leg. A single Shotl outage could make the entire OTP instance unresponsive.

**Suggestion**: Implement a circuit breaker pattern: after N consecutive failures within a time window, stop calling Shotl and immediately return null (no DRT available) for a cooldown period. This prevents cascading timeouts. Consider reducing the timeout to 10-15 seconds for routing requests, since users expect fast responses.

### 19.7 All-or-Nothing Error Handling (Low-Medium Impact)

**Problem**: When the Shotl API fails for a leg (returns null, throws exception, or returns `success: false`), the entire itinerary is flagged for deletion. The user loses the transit itinerary entirely, not just the DRT leg.

```
Example: BUS 42 (20 min) + DRT egress (Shotl timeout) -> entire itinerary deleted
The user never sees that BUS 42 was an option, even with a walk egress.
```

**Suggestion**: Instead of deleting the itinerary, fall back to the original car leg (or replace it with a walk leg if possible). The user sees the transit itinerary without DRT enrichment. The `drtEstimate` field would be null, signaling to the UI that DRT is unavailable for that leg. This is especially important for egress legs where the transit portion is still valuable information.

### 19.8 No Temporal Feasibility Check for Access Legs (Low-Medium Impact)

**Problem**: `DecorateWithDRT.isTemporallyInfeasible()` only validates egress legs (line 243: `if (isEgress)`). There is no equivalent check for access legs. An access DRT leg that drops off the passenger **after** the transit leg departs would create an impossible itinerary.

In practice, this is mitigated by the access shifting phase, which shifts Raptor's search window. But if the decoration phase decorates an access leg that wasn't shifted (e.g., a cache miss, or the access shifting returned a stale response), the infeasibility could slip through.

**Suggestion**: Add a symmetric check for access legs: the DRT expected dropoff + walk-from-dropoff must be before the next transit leg's departure time.

### 19.9 Misleading Class Name: `CachingDemandResponsiveTransportationService` (Low Impact)

**Problem**: This class does not cache anything. It's a base class that adds logging and error handling around the API call. The actual caching is in `PerRequestDemandResponsiveTransportationService`. The name was likely inherited from the ride-hailing module (`CachingRideHailingService`) which does have a cache.

**Suggestion**: Rename to `LoggingDemandResponsiveTransportationService` or `BaseDemandResponsiveTransportationService` to reflect its actual responsibility.

### 19.10 Walking Seconds Default Silently to Zero (Low Impact)

**Problem**: When Shotl doesn't return `pickup_walking_seconds` or `dropoff_walking_seconds`, the code defaults to 0 with a warning log. This means the DRT leg's start/end times won't include walking, and the generalized cost won't account for walking. The itinerary timeline becomes slightly inaccurate (the leg appears to start/end at the vehicle, not at the user's location).

This happens in two places: `DRTLeg.pickupWalkingSeconds()` and `DemandResponsiveTransportationAccessShifter.shiftTime()` (lines 240-245).

**Suggestion**: This is acceptable as a fallback, but consider whether a better default could be computed from the distance between the desired location and the scheduled pickup/dropoff location (if both are available in the response). Even a rough `distance / walkSpeed` estimate would be better than zero.

### 19.11 Arrive-By Mode Has Limited DRT Support (Low Impact)

**Problem**: Access shifting is entirely skipped for arrive-by searches (`shouldShift()` returns false when `request.arriveBy() == true`). This means in arrive-by mode:
- Access legs use stale A* car times (not shifted by pickup delay)
- Raptor may find transit connections that depart before the DRT vehicle could realistically drop off the passenger
- Decoration still runs for egress, but access legs may have incorrect timing

**Suggestion**: For arrive-by mode, the egress side is the one that should be shifted (analogous to how access is shifted in depart-at mode). Currently egress shifting is deferred entirely to the decoration phase, but at that point Raptor has already made routing decisions based on stale egress times. Implementing pre-Raptor egress shifting for arrive-by would make DRT work correctly in both directions.

### 19.12 Priority Summary

| # | Improvement | Impact | Effort | Risk if Not Addressed |
|---|-------------|--------|--------|-----------------------|
| 19.1 | Provider abstraction | High | Medium | Cannot add second DRT provider without major refactor |
| 19.2 | Hardcoded `services.get(0)` | High | Low | Multi-service config is silently broken |
| 19.3 | Duplicated shifting logic | Medium | Medium | Bug fixes must be applied in 3 places |
| 19.4 | Mode detection too broad | Medium | Low | Latent bug with mixed car modes |
| 19.5 | Request mutation | Medium | Low | Potential race condition |
| 19.6 | No circuit breaker | Medium | Medium | Shotl outage cascades to OTP |
| 19.7 | All-or-nothing errors | Low-Med | Low | Users lose transit info on DRT failure |
| 19.8 | No access feasibility check | Low-Med | Low | Edge case: impossible access timeline |
| 19.9 | Misleading class name | Low | Trivial | Developer confusion |
| 19.10 | Walking defaults to zero | Low | Low | Slightly inaccurate timelines |
| 19.11 | Arrive-by limited support | Low | Medium | Arrive-by DRT less accurate |
