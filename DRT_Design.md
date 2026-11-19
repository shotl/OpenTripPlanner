# Design of the DRT (Demand Responsive Transportation) Mode

This OpenTripPlanner fork extends the standard routing engine with a **Demand Responsive Transportation (DRT)** mode, integrated with the **Shotl** ride-sharing service. DRT acts as a first/last-mile solution: users can request on-demand shared vehicles to reach transit stops (access), leave transit stops (egress), or travel door-to-door (direct).

---

## Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [GraphQL Request Format](#2-graphql-request-format)
3. [Phase 1: Street Routing (A*)](#3-phase-1-street-routing-a)
4. [Phase 2: Access Shifting (Pre-Raptor)](#4-phase-2-access-shifting-pre-raptor)
5. [Phase 3: Raptor Transit Routing](#5-phase-3-raptor-transit-routing)
6. [Phase 4: Itinerary Decoration (Post-Raptor)](#6-phase-4-itinerary-decoration-post-raptor)
7. [Phase 5: Direct DRT (Door-to-Door)](#7-phase-5-direct-drt-door-to-door)
8. [Shotl API Integration](#8-shotl-api-integration)
9. [Cost Calculation (Generalized Cost)](#9-cost-calculation-generalized-cost)
10. [Penalties (AccessEgressPenaltyDecorator)](#10-penalties-accessegresspenaltydecorator)
11. [Temporal Overlap Fixing](#11-temporal-overlap-fixing)
12. [Filtering](#12-filtering)
13. [DRT-Eligible Stops and Sibling Expansion](#13-drt-eligible-stops-and-sibling-expansion)
14. [Caching (Per-Request)](#14-caching-per-request)
15. [GraphQL Response](#15-graphql-response)
16. [Configuration](#16-configuration)
17. [Worked Examples](#17-worked-examples)
18. [Key Source Files](#18-key-source-files)
19. [Design improvement opportunities](#19-design-improvement-opportunities)
20. [Performance: Current Optimizations and Improvement Opportunities](#20-performance-current-optimizations-and-improvement-opportunities)
21. [DRT waiting time](#21-drt-waiting-time)
22. [Journey-Availability Pre-Filter](#22-journey-availability-pre-filter)

---

## 1. High-Level Architecture

A DRT request goes through these phases:

```
GraphQL Request (with drt: DRTInput, modes: DRT)
        |
        v
+---------------------------+
| Phase 1: Street Routing   |   A* finds car paths from origin to transit stops
| (A* on street graph)      |   and from transit stops to destination
+---------------------------+
        |
        v
+---------------------------+
| Phase 2: Access Shifting  |   Calls Shotl API for ACCESS legs
| (Pre-Raptor)              |   Shifts departure times by pickup delay
+---------------------------+
        |
        v
+---------------------------+
| Phase 3: Raptor Transit   |   Multi-criteria transit search
| Routing (McRAPTOR)        |   Uses shifted access times + DRT costs
+---------------------------+
        |
        v
+---------------------------+
| Phase 4: Decoration       |   Calls Shotl API for EGRESS legs
| (Post-Raptor filter)      |   Replaces car legs with DRTLeg
+---------------------------+
        |
        v
+---------------------------+
| Filter Chain              |   Cost-based filtering, Pareto, sorting
| (Standard OTP filters)    |   minTransitDuration, walk comparison
+---------------------------+
        |
        v
GraphQL Response (with drtEstimate on legs)
```

For **direct DRT** (Phase 5: door-to-door without transit), the flow is simpler:

```
Direct Street Router (A*) -> shiftDirectDrtItineraries() -> Filter Chain -> Response
```

---

## 2. GraphQL Request Format

### Query Structure

```graphql
query {
  planConnection(
    origin: { location: { coordinate: { latitude: 41.385, longitude: 2.173 } } }
    destination: { location: { coordinate: { latitude: 41.390, longitude: 2.165 } } }
    dateTime: { earliestDeparture: "2025-03-15T09:00:00+02:00" }
    modes: {
      direct: [DRT]             # Door-to-door DRT (optional)
      transit: {
        access: [DRT]           # DRT to reach transit
        egress: [DRT]           # DRT from transit to destination
        transit: [{ mode: BUS }, { mode: RAIL }]
      }
    }
    drt: {
      areaId: "area-123"
      userId: "user-456"
      paxAppId: "app-id"
      rideType: "ON_DEMAND"
      passengers: { regular: 1, wheelchair: 0 }
      passengerFareType: [
        { type: "REGULAR", count: 1 }
      ]
      egressReluctance: 1.0     # Cost multiplier for DRT egress (default 1.0)
    }
  ) {
    edges {
      node {
        legs {
          mode
          startTime
          endTime
          generalizedCost
          drtEstimate {
            id
            code
            estimatedPickupTime
            estimatedDropoffTime
            scheduledPickupPlace { location { latitude longitude } name }
            scheduledDropoffPlace { location { latitude longitude } name }
            vehicleId
            passengers { regular wheelchair }
          }
        }
      }
    }
  }
}
```

### DRT Input Fields

| Field | Type | Required | Default | Purpose |
|-------|------|----------|---------|---------|
| `areaId` | String | Not enforced | - | Shotl service area identifier |
| `userId` | String | Not enforced | - | User identifier for Shotl |
| `paxAppId` | String | Not enforced | - | Passenger app ID (sent as HTTP header) |
| `rideType` | String | Not enforced | - | Ride type (e.g., "ON_DEMAND") |
| `passengers` | Object | No | 1 regular, 0 wheelchair | Passenger counts |
| `passengerFareType` | List | No | null | Fare type breakdown for pricing |
| `egressReluctance` | Float | No | 1.0 | Multiplier to inflate CAR egress cost before Raptor |
| `accessBufferSeconds` | Int | No | 0 | Seconds added after the DRT dropoff at a transit stop before boarding (access legs) |
| `egressBufferSeconds` | Int | No | 0 | Seconds added after the transit arrival before the requested DRT pickup (egress legs) |

The schema declares every field optional and `RouteRequestMapper.setDrtInput()` does not validate
them: `areaId`, `userId`, `paxAppId` and `rideType` are passed to the Shotl API as given. The
Transmodel `DRTInput` has no `passengerFareType`, `accessBufferSeconds` or `egressBufferSeconds`.

### Mode Combinations

DRT can be used in three positions:

| Position | GraphQL Field | Effect |
|----------|--------------|--------|
| **Access** | `modes.transit.access: [DRT]` | DRT vehicle takes user from origin to a transit stop |
| **Egress** | `modes.transit.egress: [DRT]` | DRT vehicle takes user from a transit stop to destination |
| **Direct** | `modes.direct: [DRT]` | DRT vehicle takes user door-to-door (no transit) |

All three can be combined in a single request. When access or egress DRT is used, walk-only alternatives are also computed as fallback.

---

## 3. Phase 1: Street Routing (A*)

### What Happens

The A* street router finds car-drivable paths from the origin to reachable transit stops (access) and from transit stops to the destination (egress). DRT maps to `TraverseMode.CAR` internally:

```java
// StateData.java
case CAR, CAR_TO_PARK, CAR_PICKUP, CAR_HAILING,
     DEMAND_RESPONSIVE_TRANSPORTATION -> TraverseMode.CAR;
```

### Cost During Street Routing

Each street edge cost is computed as:

```
weight = (distance / carSpeed) * carReluctance
```

Where `carSpeed` comes from OSM speed limits on each edge (not a global value), and `carReluctance` defaults to **1.0**.

**Important**: These A*-based costs are **temporary estimates**. They will be replaced by real Shotl API data in later phases. The A* routing is only used to determine which transit stops are reachable by car and to get the path geometry.

### Access vs Egress: Different Reluctances During A* Street Search

The A* street search runs **twice** — once for access (origin to stops) and once for egress (stops to destination). For DRT, these two searches use **different car reluctance values**, and this asymmetry is deliberate:

| A* Search | Car Reluctance Used | Source | Purpose |
|-----------|-------------------|--------|---------|
| **Access** (origin → stops) | `carReluctance` = **1.0** (default) | `CarPreferences.reluctance` | Standard car cost; will be replaced by real Shotl data during access shifting |
| **Egress** (stops → destination) | `egressReluctance` = **1.0** (default, configurable per request) | `DemandResponsiveExtData.egressReluctance()` | Inflates egress car cost to compensate for the gap between A* estimate and real DRT time |

#### Why Egress Needs a Separate Reluctance

The core problem is a **timing asymmetry** between access and egress:

- **Access legs** are shifted **before Raptor** (Phase 2). The A* car cost is replaced with real Shotl data, so Raptor sees accurate costs. The A* car reluctance doesn't matter much — it's overwritten.
- **Egress legs** are decorated **after Raptor** (Phase 4). During Raptor, egress legs still carry the stale A* car cost. Raptor uses this stale cost to decide which itineraries survive Pareto filtering. Only after Raptor finishes does `DecorateWithDRT` replace the egress car cost with real Shotl data.

This creates a problem: the A* car cost for egress is almost always **too optimistic** compared to real DRT times, because:
1. A* uses OSM speed limits (e.g., 50 km/h), but DRT vehicles make detours for shared rides
2. A* doesn't account for DRT pickup delay (waiting for the vehicle)
3. A* doesn't account for walking to/from the vehicle pickup/dropoff points

If the stale egress cost is too low, Raptor may:
- Favor DRT-egress itineraries over pure-transit alternatives during Pareto filtering
- Prune transit-only itineraries that would actually be better once real DRT costs are applied
- Result in worse itineraries surviving to the decoration phase

#### How `egressReluctance` Solves This

The `egressReluctance` parameter (set per request via `drt.egressReluctance` in GraphQL) **replaces** the car reluctance specifically for the egress A* search:

```java
// TransitRouter.fetchAccessEgresses() — only for egress + DRT mode:
if (type.isEgress() && mode == DEMAND_RESPONSIVE_TRANSPORTATION) {
    double egressReluctance = accessRequest.demandResponsiveExtData().egressReluctance();
    accessRequest.withPreferences(p -> p.withCar(c -> c.withReluctance(egressReluctance)));
}
```

This inflates the egress car cost that Raptor sees, making it closer to what the real DRT cost will be after decoration.

#### Worked Example: Why This Matters

```
Scenario: egressReluctance = 1.0 (default, no inflation)

  A* egress from Station to Home: 300s car time
  A* egress cost = 300 * 1.0 = 300 cost-seconds

  Raptor sees this cheap egress and builds:
    Itinerary A: Bus 10 (15 min) + car egress 300 cost  -> total c1 = 1200
    Itinerary B: Bus 22 (25 min) + walk egress 200 cost -> total c1 = 1700
    -> Itinerary A dominates B on both time and cost -> B is pruned

  After decoration, real DRT egress cost = 850 cost-seconds (shared ride + wait)
    Itinerary A actual: Bus 10 + DRT egress 850 cost -> total c1 = 1750
    Itinerary B would have been: Bus 22 + walk 200 cost -> total c1 = 1700
    -> B was actually better, but it was already pruned by Raptor!

Scenario: egressReluctance = 2.5

  A* egress cost = 300 * 2.5 = 750 cost-seconds (closer to real DRT cost)

  Raptor now sees:
    Itinerary A: Bus 10 + car egress 750 cost  -> total c1 = 1650
    Itinerary B: Bus 22 + walk egress 200 cost -> total c1 = 1700
    -> Neither dominates the other on all criteria -> BOTH survive

  After decoration:
    Itinerary A: Bus 10 + DRT 850 cost -> total c1 = 1750
    Itinerary B: Bus 22 + walk 200 cost -> total c1 = 1700
    -> Both available to user, correctly ranked by real costs
```

#### How to Tune `egressReluctance`

| Value | Effect | When to Use |
|-------|--------|-------------|
| **1.0** (default) | No inflation; A* car cost used as-is | When DRT times are similar to car times (short distances, no shared rides) |
| **1.5 - 2.0** | Moderate inflation | Typical shared-ride DRT where vehicles make detours |
| **2.5 - 3.0** | Heavy inflation | DRT with long pickup waits or significant routing detours |
| **> 3.0** | Very aggressive inflation | May cause DRT egress to be pruned too aggressively by Raptor |

Setting it too high means Raptor assigns so much cost to DRT egress that most DRT+transit itineraries are pruned before decoration can show they're actually competitive. Setting it too low means Raptor keeps too many DRT itineraries that turn out to be inferior after decoration — wasting Shotl API calls and potentially pruning better walk-based alternatives.

The ideal value depends on the specific DRT deployment: how much DRT vehicles deviate from direct car routes, average pickup delays, and average walking distances to/from pickup points.

### DRT-Eligible Stops Filtering

Not all transit stops are available for DRT access/egress. Only stops listed in `drt_stops.txt` (a custom file inside each GTFS feed) are eligible. The filtering happens after A* finds reachable stops:

```
A* finds: [Stop_A, Stop_B, Stop_C, Stop_D, Stop_E]
drt_stops.txt contains: [Stop_A, Stop_C, Stop_E]
Result after filtering: [Stop_A, Stop_C, Stop_E]
```

Walk-accessible stops are added separately and are NOT filtered by DRT eligibility. This means the router always considers walk+transit as a fallback.

The eligible stops then go through the journey-availability pre-filter (§22).

### Dual Origin/Destination Linking (CAR + WALK)

The walk fallback search reuses the same temporary origin/destination vertex as the car search
(`TemporaryVerticesContainer` is shared). For DRT mode the primary linking is CAR-only, so
`StreetIndex.createVertexFromCoordinate()` performs a **second WALK linking** on the same
temporary vertex. Without it, an origin/destination that snaps to a car-only street edge (e.g. a
carriageway with `foot=no`) would silently lose ALL walk+transit alternatives — Raptor would only
receive the DRT accesses, producing degenerate itineraries (DRT to a far stop + long waits for
sparse service).

Diagnostics:
- `StreetIndex` logs `[DRT] Origin/Destination at (lat,lon) linked: carEdges=N, walkEdges=M`
  (WARN if either count is 0).
- `TransitRouter` logs the walk fallback result per phase:
  `[DRT] ACCESS phase: N entries fed to Raptor = X DRT + Y walk fallback`, and WARNs when the
  WALK fallback finds 0 stops.

---

## 4. Phase 2: Access Shifting (Pre-Raptor)

### Why Shifting Is Needed

The A* router assumes the car trip starts instantly. In reality, the DRT vehicle needs time to arrive. Access shifting calls the Shotl API and adjusts departure times so Raptor searches for transit connections that depart **after** the DRT vehicle actually drops off the passenger.

### The Shifting Process

```
1. User requests departure at 10:00
2. A* finds: car path from origin to Stop_X (estimated 5 min drive)
3. Access Shifter calls Shotl API:
   "Pick up at origin, drop off at Stop_X, desired pickup 10:00"
4. Shotl responds:
   "Expected pickup: 10:12, expected dropoff: 10:20,
    walk to pickup: 90s, walk from dropoff: 30s,
    ride duration: 480s"
5. Pickup delay = 10:12 - 10:00 = 12 minutes
6. New access is created with:
   - Duration: 90s + 480s + 30s = 600s (10 min)
   - Departure shifted by: 12 minutes
   - Raptor will search for transit departing AFTER 10:22
     (10:00 + 12min delay + 10min travel)
```

### What the Access Adapter Stores

`DemandResponsiveTransportationAccessAdapter` wraps the original access with:

- `arrival` (Duration): the pickup delay to shift departure
- `drtDurationSeconds`: actual ride time from Shotl
- `walkToPickupSeconds`: walking to the vehicle pickup point
- `walkFromDropoffSeconds`: walking from the vehicle dropoff to the transit stop
- `waitingSeconds`: `max(0, pickup delay - walkToPickupSeconds)`, the wait at the pickup point
- `accessBufferSeconds`: the request's `drt.accessBufferSeconds`
- `shotlResponse`: the full Shotl API response (reused later in decoration)
- Recomputed generalized cost using proper reluctances

### Arrive-By Mode

Access shifting is **skipped** for "arrive by" searches (`request.arriveBy() == true`). In this mode, the user specifies a desired arrival time, so the DRT pickup delay cannot be pre-computed for access legs. Egress decoration still runs in all modes.

---

## 5. Phase 3: Raptor Transit Routing

### How DRT Costs Enter Raptor

Raptor receives the access/egress paths as fixed-cost entries. For DRT access paths, the cost has already been recomputed by the Access Adapter using real Shotl data:

```
walkCost   = (walkToPickup + walkFromDropoff) * walkReluctance
waitCost   = (waitingSeconds + accessBufferSeconds) * 1.0
drtCost    = drtDuration * carReluctance
raptorCost = RaptorCostConverter.toRaptorCost(walkCost + waitCost + drtCost)   // in centi-seconds
```

This means Raptor sees accurate DRT costs when comparing multi-modal itineraries.

### Pareto Optimality (Three-Criteria Filtering)

Raptor uses **McRAPTOR** with three criteria:

| Criterion | Meaning | Preference |
|-----------|---------|------------|
| Arrival time | When you reach the destination | Earlier is better |
| Pareto round | Proxy for number of transfers | Fewer is better |
| Generalized cost (c1) | Weighted combination of time, penalties, waiting | Lower is better |

An itinerary survives only if no other itinerary beats it on **all three** criteria simultaneously. DRT access and egress carry no access/egress penalty (§10), so a DRT itinerary competes on these three criteria with its own duration and cost.

### Egress Reluctance Adjustment

When the egress mode is DRT, the car reluctance for egress routing can be inflated by `egressReluctance` (default 1.0). This compensates for the fact that real DRT travel times (obtained post-Raptor during decoration) are typically longer than the A* car estimate. Without this, DRT egress paths might unfairly outcompete transit-only alternatives during Raptor, only to become worse after decoration.

```
Example with egressReluctance = 1.5:
  A* car egress cost = 300s * 1.0 (carReluctance) = 300 cost-seconds
  Inflated for Raptor = 300 * 1.5 = 450 cost-seconds
  This makes Raptor less aggressive about choosing DRT egress
```

---

## 6. Phase 4: Itinerary Decoration (Post-Raptor)

### DecorateWithDRT Filter

After Raptor produces itineraries, the `DecorateWithDRT` filter runs as the **first filter** in the chain (before any cost-based filters). This is critical because it replaces stale A* car costs with real DRT data, and all subsequent filters must see accurate costs.

### What It Does

The filter works in three steps (`decorateWithDeduplication()`):

1. **Collect** one request per distinct (from, to, pickup time) over the car-mode `StreetLeg`s of all itineraries that are not flagged for deletion. Legs that are already a `DRTLeg` are skipped (access legs are DRTLegs from the mapping phase). The pickup time is:
   - For egress legs (a transit leg comes earlier in the itinerary): `leg.getStartTime()` plus `egressBufferSeconds`
   - For other legs: `request.dateTime()` (the requested departure)
2. **Fetch** the distinct estimates from the Shotl API concurrently on `DrtIoExecutor`.
3. **Apply** the estimates to each itinerary, without further I/O (`decorateLegFromEstimates()`):
   - **Flag for deletion** if no DRT estimate is available (Shotl returned null or error)
   - **Check temporal feasibility** (egress only), and flag for deletion if it fails
   - **Create DRTLeg** wrapping the original StreetLeg with Shotl data and recomputed cost
   - Fix temporal overlaps (§11) and update the itinerary cost

### Temporal Feasibility Check (Egress)

For egress legs, the itinerary is flagged for deletion when the DRT expected pickup time is more than 2 minutes (`EGRESS_TEMPORAL_TOLERANCE`) **before** the start of the car leg, which is when the passenger reaches the pickup point (after getting off transit + walking). The tolerance covers Shotl rounding pickup times to whole minutes and the rounding of the per-request cache key.

```
Example - FEASIBLE:
  Transit arrives at station: 17:30
  Walk to car pickup point: 2 min -> passenger at pickup: 17:32
  DRT expected pickup: 17:35
  17:35 + 2 min tolerance >= 17:32 -> OK

Example - INFEASIBLE (flagged for deletion):
  Transit arrives at station: 17:30
  Walk to car pickup point: 2 min -> passenger at pickup: 17:32
  DRT expected pickup: 17:28
  17:28 + 2 min tolerance = 17:30 < 17:32 -> INFEASIBLE (DRT vehicle arrives before passenger)
```

### Egress Cost Update

After decoration, the itinerary's total generalized cost is adjusted:

```
costDelta = newDRTCost - oldA*CarCost
itinerary.generalizedCost += costDelta
```

This ensures the itinerary's total cost accurately reflects the real DRT data for downstream filtering and sorting.

---

## 7. Phase 5: Direct DRT (Door-to-Door)

### How It Works

When the direct mode is DRT (`modes.direct: [DRT]`), the router also produces door-to-door itineraries (no transit involved):

1. **Direct street router** runs A* with CAR mode from origin to destination
2. **Before the filter chain**, `shiftDirectDrtItineraries()` processes each direct itinerary:
   - For each car leg, calls the Shotl API with `DrtRequestContext.DIRECT_SHIFTING`
   - Creates a DRTLeg with real times and computed generalized cost
   - Calculates cost delta (new DRT cost - old A* cost) and updates itinerary cost
   - Fixes temporal overlaps in the timeline
3. **Walk search** also runs to provide a walk-only baseline

### Why Direct Shift Happens Before Filtering

Direct DRT itineraries are shifted before the filter chain (not inside it) because cost-based filters like `RemoveTransitIfWalkingIsBetter` need to see accurate DRT costs. If the stale A* car cost were used, the DRT itinerary might appear cheaper than it actually is.

### Walk-Only Competition

Walk-only itineraries are **not** removed when DRT is the direct mode. This was an intentional change: walk should compete naturally with DRT. For short distances, walking may be faster and cheaper than waiting for a DRT vehicle.

```
Example:
  Origin to destination: 400m
  Walk: 5 min, cost = 600 (300s * 2.0 walkReluctance)
  DRT: 2 min ride + 90s walk + 8 min wait at the pickup point,
       cost = 780 (90s * 2.0 walk + 480s * 1.0 wait + 120s * 1.0 ride, formula in §9)
  -> Walk wins on cost, DRT wins on ride comfort
  -> Both offered to user
```

---

## 8. Shotl API Integration

### Endpoint

```
POST {baseUrl}/v3/drt/time-estimations
```

The base URL is configured in `router-config.json` (e.g., `http://rides-api.shotl.svc.cluster.local/`).

### Request

```json
{
  "area_id": "area-123",
  "user_id": "user-456",
  "ride_type": "ON_DEMAND",
  "desired_pickup_location": { "latitude": 41.385, "longitude": 2.173 },
  "desired_dropoff_location": { "latitude": 41.390, "longitude": 2.165 },
  "passengers": { "regular": 1, "wheelchair": 0 },
  "desired_pickup_time": 1706965200,
  "pickup_shift": true,
  "passenger_fare_type": [
    { "type": "REGULAR", "count": 1 }
  ]
}
```

Headers:
- `Content-Type: application/json`
- `Shotl-Passenger-App-Id: {paxAppId}`
- `Accept-Language: {acceptLanguage}` (the language tag of the request locale; `en-US` when there is none)

HTTP timeout: **60 seconds**.

### Response (Success)

```json
{
  "success": true,
  "data": {
    "id": "ride-789",
    "user_id": "user-456",
    "type": "ON_DEMAND",
    "status": "ESTIMATED",
    "code": "ABC123",
    "desired_pickup_location": { "latitude": 41.385, "longitude": 2.173 },
    "desired_dropoff_location": { "latitude": 41.390, "longitude": 2.165 },
    "scheduled_pickup_place": {
      "location": { "latitude": 41.3855, "longitude": 2.1730 },
      "name": "Main Street Stop"
    },
    "scheduled_dropoff_place": {
      "location": { "latitude": 41.3905, "longitude": 2.1648 },
      "name": "Park Avenue"
    },
    "desired_pickup_time": 1706965200,
    "desired_dropoff_time": null,
    "user_expected_pickup_time": 1706965500,
    "user_expected_dropoff_time": 1706966100,
    "petition_time": 1706964000,
    "passengers": { "regular": 1, "wheelchair": 0 },
    "vehicle_id": "vehicle-001",
    "shotl_duration_seconds": 480,
    "pickup_walking_seconds": 90,
    "dropoff_walking_seconds": 30
  }
}
```

### Key Response Fields and How They Are Used

| Field | Usage in OTP |
|-------|-------------|
| `user_expected_pickup_time` | Vehicle pickup time. Access legs: DRTLeg start time (minus pickup walking). Egress and direct legs: end of the wait inside the leg (§9) |
| `user_expected_dropoff_time` | DRTLeg end time (plus dropoff walking) |
| `pickup_walking_seconds` | Walk time from origin/station to vehicle pickup point |
| `dropoff_walking_seconds` | Walk time from vehicle dropoff to destination/station |
| `shotl_duration_seconds` | Used as fallback if expected times are inconsistent |
| `scheduled_pickup_place` | Exposed in GraphQL response for UI display |
| `scheduled_dropoff_place` | Exposed in GraphQL response for UI display |
| `vehicle_id` | Exposed in GraphQL response |
| `id`, `code`, `status` | Exposed in GraphQL response |

### Response (Rejection)

```json
{
  "success": false,
  "reason": {
    "code": "OUT_OF_SERVICE_HOURS",
    "message": "Service is not available at this time",
    "display_message": "The DRT service is closed",
    "details": {}
  }
}
```

When Shotl returns `success: false`, a `ShotlBusinessRejectionException` is thrown, caught by the service layer, and the itinerary is flagged for deletion (no DRT available for that leg).

### When the API Is Called (4 Contexts)

| Context | When | Purpose |
|---------|------|---------|
| `ACCESS_SHIFTING` | Pre-Raptor, for access legs | Shift departure time by pickup delay |
| `EGRESS_SHIFTING` | Pre-Raptor, for egress legs | (Currently unused; egress handled in decoration) |
| `LEG_DECORATING` | Post-Raptor, in DecorateWithDRT filter | Replace car legs with real DRT data |
| `DIRECT_SHIFTING` | After direct street routing | Replace direct car legs with real DRT data |

---

## 9. Cost Calculation (Generalized Cost)

### DRT Leg Cost Formula

```
cost = (walkToPickup + walkFromDropoff) * walkReluctance
     + waitingSeconds * waitReluctance
     + drtDuration * carReluctance
```

Where:
- `walkToPickup` = `pickup_walking_seconds` from Shotl (walk from origin to vehicle pickup)
- `walkFromDropoff` = `dropoff_walking_seconds` from Shotl (walk from vehicle dropoff to destination/stop)
- `waitingSeconds` = `max(0, user_expected_pickup_time - (legStartTime + pickup_walking_seconds))` — time the user waits at the pickup point for the vehicle
- `drtDuration` = `user_expected_dropoff_time - user_expected_pickup_time` (actual ride time)
- `walkReluctance` = **2.0** (default)
- `waitReluctance` = **1.0** (same as transit wait)
- `carReluctance` = **1.0** (default)

### DRT Leg Duration

The DRT leg duration includes all four phases:

```
duration = walkToPickup + waitingSeconds + drtDuration + walkFromDropoff
```

### Worked Example

Shotl returns: pickup_walking = 120s, dropoff_walking = 60s, ride = 600s, waiting = 240s

```
walkCost    = (120 + 60) * 2.0 = 360 cost-seconds
waitCost    = 240 * 1.0 = 240 cost-seconds
drtCost     = 600 * 1.0 = 600 cost-seconds
totalCost   = 360 + 240 + 600 = 1200 cost-seconds
totalDuration = 120 + 240 + 600 + 60 = 1020 seconds
```

Compare with original A* car estimate for same path: 350 cost-seconds (based on OSM speeds, no waiting).

Cost delta = 1200 - 350 = +850 cost-seconds added to itinerary's total cost.

### Why Different Reluctances for Walk vs Ride vs Wait

- Walking is slower and more physically demanding -> higher reluctance (2.0) makes the planner prefer alternatives where walking is minimized
- DRT ride is comfortable but takes real time -> lower reluctance (1.0) treats ride time at face value
- Waiting at the pickup point is idle time -> reluctance (1.0) same as transit waiting, penalizes long waits proportionally
- This means: a 60-second walk "costs" as much as a 120-second ride or a 120-second wait in generalized cost

### DRTLeg Time Boundaries

The DRTLeg includes walking time in its start/end times:

```
DRTLeg.endTime   = user_expected_dropoff_time + dropoff_walking_seconds

Access legs (three-argument constructor, no waiting inside the leg):
DRTLeg.startTime = user_expected_pickup_time - pickup_walking_seconds

Egress and direct legs (four-argument constructor, waiting inside the leg):
DRTLeg.startTime = legStartTime
                   egress: the start time of the car leg it replaces
                   direct: the requested departure time (request.dateTime())
```

This means the leg represents the full door-to-vehicle-to-door experience, not just the ride portion.

---

## 10. Penalties (AccessEgressPenaltyDecorator)

### Pre-Raptor Penalties

Before Raptor runs, `AccessEgressPenaltyDecorator` adds artificial penalties to non-walk access/egress to prevent car-based modes from unfairly dominating:

| Street Mode | Time Penalty | Cost Factor |
|-------------|-------------|-------------|
| WALK | none | 0.0 |
| BIKE | none | 0.0 |
| CAR_TO_PARK | 20m + 2.0t | 1.5 |
| CAR_PICKUP | 20m + 2.0t | 1.5 |
| **DRT** | **none** | **0.0** |
| FLEXIBLE | 10m + 1.3t | 1.3 |

Formula: `timePenalty = constant + coefficient * duration`, then `costPenalty = timePenalty * costFactor`

DRT is excluded from the car penalty: `AccessEgressPreferences.createDefaultCarPenalty()` skips `StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION` (and `CAR`), and the `router-config.json` files under `config/` set no access/egress penalty. A DRT access or egress therefore enters Raptor with its own duration and cost: the Shotl-based values for access (§4, §5), the A* car cost scaled by `egressReluctance` for egress (§3).

### Example: 5-minute CAR_PICKUP egress compared with DRT

```
CAR_PICKUP egress, 5 minutes:
timePenalty = 20min + 2.0 * 5min = 30 minutes (1800 seconds)
costPenalty = 1800 * 1.5 = 2700 cost-seconds added to c1

DRT egress, 5 minutes:
timePenalty = 0
costPenalty = 0

2-minute walk egress:
timePenalty = 0
costPenalty = 0
```

The CAR_PICKUP egress appears 30 minutes "longer" and 2700 cost-seconds more expensive to Raptor. The DRT egress gets no such handicap: it competes with walk-based alternatives on arrival time, round and generalized cost as they are.

### When DRT Survives vs Gets Pruned

| Scenario | Result |
|----------|--------|
| Frequent transit, stops near destination (100m walk) | DRT pruned - walk dominates on cost |
| Sparse transit, long walk to destination (1.4km) | DRT offered - arrives 30+ min earlier |
| Evening/night with limited service | DRT offered - no competitive walk+transit option |
| No transit available at all | DRT always offered |

---

## 11. Temporal Overlap Fixing

### Why It's Needed

After DRT decoration replaces car leg times with real Shotl times, the timeline may have inconsistencies:
- **Overlaps**: a DRT leg starts before the previous leg ends (impossible)
- **Gaps**: a leg starts after the previous leg ends (waiting time)

### Rules

```
For DRT legs:
  - Fix OVERLAPS: shift the DRT leg forward so it starts when the previous leg ends
  - PRESERVE GAPS: gaps represent real waiting time for the DRT vehicle
    (this waiting time is already penalized in the generalized cost)

For non-DRT, non-transit legs (walks):
  - Fix BOTH overlaps AND gaps
  - Walks should connect seamlessly - no stale gaps from pre-decoration times
```

### Example

```
Before fixing:
  Transit leg:  17:00 - 17:25
  Walk leg:     17:22 - 17:27  (OVERLAP: starts before transit ends)
  DRT leg:      17:35 - 17:50  (GAP: 8 min wait for DRT vehicle)

After fixing:
  Transit leg:  17:00 - 17:25  (unchanged)
  Walk leg:     17:25 - 17:30  (shifted forward to close overlap)
  DRT leg:      17:35 - 17:50  (GAP PRESERVED - 5 min waiting)
```

The 5-minute gap before the DRT leg is intentional: it's the real waiting time for the DRT vehicle to arrive, and it's accounted for in the itinerary's total duration and cost.

---

## 12. Filtering

### Filter Chain Order

DRT-relevant filters execute in this order:

```
1. DecorateWithDRT          <- FIRST: replaces car legs with DRT data
2. Group-by filters          <- Cluster similar itineraries
3. Transit cost filters      <- Remove dominated transit itineraries
4. Non-transit cost filters  <- Remove dominated non-transit itineraries
5. RemoveTransitIfStreetOnlyIsBetter  <- Compare transit vs walk/DRT
6. RemoveShortTransitItinerariesFilter <- Remove itineraries with too little transit
7. Paging / deduplication    <- Limit results
8. Final sort                <- Order by generalized cost
```

### RemoveShortTransitItinerariesFilter (minTransitDuration)

This custom filter removes itineraries where the **total** transit duration is below a configured threshold. Prevents itineraries with token transit usage (e.g., one bus stop) that exist only because DRT access/egress makes them technically feasible.

```
Config: minTransitDuration = 4m (in router-config.json)

Example:
  Itinerary A: DRT (10min) + Bus (3min) + DRT (8min) = 3min transit -> REMOVED
  Itinerary B: DRT (10min) + Bus (12min) + Walk (5min) = 12min transit -> KEPT
```

**Important fix (commit 8b2e8df1c7)**: The filter originally checked if ANY single transit leg was shorter than the minimum. This was wrong - it now sums ALL transit legs and checks the total:

```
// WRONG (old): anyMatch(leg -> leg.getDuration() < minTransitDuration)
// RIGHT (new): totalTransitDuration < minTransitDuration
```

### Walk-Only Competition for Direct DRT

Walk-only itineraries are NOT artificially removed when direct DRT is used. They compete naturally on cost and time. This was changed in commit f53cb0c592 - previously, walk-only results were removed (like they are for FLEXIBLE mode), but for DRT this was wrong because walking may genuinely be better for short distances.

---

## 13. DRT-Eligible Stops and Sibling Expansion

### drt_stops.txt

Each GTFS feed can include a `drt_stops.txt` file listing stops where DRT vehicles can pick up or drop off passengers:

```csv
stop_id
STOP_001
STOP_002
STOP_003
```

During graph build, `DrtStopsModule` reads this file and stores the eligible stops in the `TimetableRepository`. At routing time, only these stops are considered for DRT access/egress (walk-accessible stops are added separately as fallback).

### Sibling Stop Expansion

When a DRT-eligible stop belongs to a parent station (e.g., a platform within a train station), the DRT access/egress is automatically expanded to all sibling stops:

```
DRT-eligible stop: Platform_1 (parent: Central_Station)
Siblings: Platform_1, Platform_2, Platform_3

Instead of calling Shotl API 3 times, OTP:
1. Calls Shotl for DRT origin -> Platform_1
2. Creates copies for Platform_2 and Platform_3 with added walk transfer time
```

This reduces API calls while ensuring all platforms in a station are reachable.

### Build-Time Validation

During graph build, `DrtStopsModule.validateDrivableStreetLinks()` checks every DRT-eligible stop
and logs a detailed multi-line diagnostic for each problematic one. Three problem classes:

- **No street vertex**: the stop was never linked to the street network at all
- **Not car-reachable, MITIGATED**: no incoming car-traversable street link, but a sibling stop in
  the same parent station is DRT-eligible + car-reachable + has a walk transfer to this stop, so
  the runtime sibling-expansion feature will still serve it (this is why DRT can "work" for stops
  that are flagged)
- **Not car-reachable, NOT MITIGATED**: no sibling fallback exists — the stop will never be served
  by DRT access

Each warning includes:

- **GTFS stop data**: id, name, code, platform code, description, vehicle type, wheelchair
  accessibility, coordinates, parent station and its child stop count
- **Street linkage cause analysis** (mirrors `TransitStopVertex.isReachableByCarForAccess()`):
  which street vertex the stop is linked to (OSM node id from the loaded OSM data + coordinates),
  its distance from the stop, every incoming street edge with name/permission/length/car speed and
  its endpoint node ids/coordinates, and a classification of the root cause — no street→stop link,
  isolated street vertex, ONE-WAY street pointing away (cars can leave but not arrive), or
  pedestrian/bicycle-only area. Also reports whether car egress and walk linkage work. All data
  comes from the graph built from the configured (custom) OSM extract — no external OSM URLs or
  services are referenced.
- **DRT impact**: MITIGATED (lists the sibling stops + walk transfer distances that will serve
  this stop) or NOT MITIGATED. Siblings that are car-reachable but lack a walk transfer are listed
  separately as near-misses.

A summary INFO line reports totals: directly reachable / mitigated / not mitigated / missing
vertex. The sibling check is exact because `DrtStopsModule` runs after `DirectTransferGenerator`
in the graph build pipeline, so walk path transfers are already available.

---

## 14. Caching (Per-Request)

### PerRequestDemandResponsiveTransportationService

Each HTTP request gets its own cache (`ConcurrentHashMap`). This prevents duplicate Shotl API calls within a single routing request (e.g., same stop queried for access and then again for decoration).

### Cache Key

The cache key (`DrtEstimateRequest`) rounds coordinates and time to increase hit rate:

```
Coordinates: rounded to ~10m precision (4 decimal places)
Pickup time: rounded to 5-minute intervals
```

### Cache Lifetime

The cache lives only for the duration of one HTTP request (`@HttpRequestScoped`). There is no cross-request caching - each new routing request starts fresh.

---

## 15. GraphQL Response

### DRTEstimate Type

When a leg is a DRTLeg, the `drtEstimate` field is populated:

```json
{
  "legs": [
    {
      "mode": "CAR",
      "startTime": "2025-03-15T09:10:30+02:00",
      "endTime": "2025-03-15T09:22:00+02:00",
      "generalizedCost": 960,
      "drtEstimate": {
        "id": "ride-789",
        "code": "ABC123",
        "estimatedPickupTime": "2025-03-15T09:12:00+02:00",
        "estimatedDropoffTime": "2025-03-15T09:21:30+02:00",
        "scheduledPickupPlace": {
          "location": { "latitude": 41.3855, "longitude": 2.1730 },
          "name": "Main Street Stop"
        },
        "scheduledDropoffPlace": {
          "location": { "latitude": 41.3905, "longitude": 2.1648 },
          "name": "Park Avenue"
        },
        "vehicleId": "vehicle-001",
        "passengers": { "regular": 1, "wheelchair": 0 }
      }
    },
    {
      "mode": "BUS",
      "startTime": "2025-03-15T09:25:00+02:00",
      "endTime": "2025-03-15T09:45:00+02:00"
    }
  ]
}
```

Note: the leg `mode` remains `"CAR"` even for DRT legs. The presence of `drtEstimate` distinguishes it from regular car legs.

### Walking and Waiting Fields

Besides the fields in the example, the GTFS API (`schema.graphqls`) exposes:

| Field | Type | Meaning |
|-------|------|---------|
| `DRTEstimate.pickupWalkingSeconds` | Int | Seconds the user walks from origin/station to the vehicle pickup point |
| `DRTEstimate.dropoffWalkingSeconds` | Int | Seconds the user walks from the vehicle dropoff point to the destination/station |
| `DRTEstimate.waitingSeconds` | Int | Seconds the user waits at the pickup point for the vehicle (§21) |
| `Itinerary.drtWaitingTime` | Long | Sum of `waitingSeconds` over the DRT legs of the itinerary, in seconds; not part of `waitingTime` |

Of these, the Transmodel API has `DRTEstimate.waitingSeconds` only.

### Timestamps

- `estimatedPickupTime` and `estimatedDropoffTime` are ISO-8601 with timezone (using the transit model timezone)
- `desiredPickupTime` and `desiredDropoffTime` are Unix epoch seconds (Long)

---

## 16. Configuration

### router-config.json

```json
{
  "demandResponsiveTransportationServices": [
    {
      "type": "shotl-demand-responsive-transportation",
      "enabled": true,
      "estimationsURL": "http://rides-api.shotl.svc.cluster.local/",
      "areasURL": "http://areas-api.shotl.svc.cluster.local/",
      "providerName": "Shotl"
    }
  ],
  "routingDefaults": {
    "itineraryFilters": {
      "minTransitDuration": "4m"
    }
  }
}
```

`areasURL` is optional. Without it the journey-availability pre-filter (§22) is disabled.

### HTTP Client Configuration

The Dagger module (`DemandResponsiveTransportationServicesModule`) creates a shared HTTP client with:
- **Max 40 total connections** (increased from default)
- **20 connections per host** (increased from default 5)
- Rationale: DRT API calls happen in parallel during access shifting and leg decoration

### Reluctance Defaults

| Parameter | Default | Effect on DRT |
|-----------|---------|---------------|
| `walkReluctance` | 2.0 | Higher = walk portions of DRT more expensive |
| `carReluctance` | 1.0 | Higher = DRT ride portion more expensive |
| `waitReluctance` | 1.0 | Higher = waiting at transit stops penalized more |

These can be overridden per-request via GraphQL or server-wide in `router-config.json`.

---

## 17. Worked Examples

### Example 1: Access DRT + Transit + Walk Egress

**Scenario**: User at home (suburban area), wants to reach downtown office. Bus stop 2km away.

```
Request: depart at 08:00, accessMode: DRT, egressMode: WALK

Phase 1 - Street Routing:
  A* finds car path: Home -> Bus Stop X (2.1km, 4 min by car)
  Bus Stop X is in drt_stops.txt -> eligible

Phase 2 - Access Shifting:
  Shotl API call: pickup at Home, dropoff at Bus Stop X, desired 08:00
  Response:
    expected_pickup: 08:07 (7 min wait)
    expected_dropoff: 08:15
    pickup_walking: 60s (walk to nearby pickup point)
    dropoff_walking: 30s (walk from dropoff to bus stop platform)

  Access Adapter:
    duration = 60 + 480 + 30 = 570s
    cost = (60 + 30) * 2.0 + 480 * 1.0 = 660 cost-seconds
    departure shifted by 7 min (pickup delay)

Phase 3 - Raptor:
  Searches for bus departing AFTER 08:15:30 (08:00 + 7min delay + 8min travel + 30s walk)
  Finds: Bus 42 departing 08:20, arriving downtown 08:45
  Walk from downtown stop to office: 3 min

Phase 4 - Decoration:
  Access leg already has DRTLeg from mapping phase (no extra API call)
  No egress car leg to decorate (walk egress)

Result:
  08:00 -> 08:01 Walk to DRT pickup (60s)
  08:07 -> 08:15 DRT ride (wait 6 min, then 8 min ride)
  08:15 -> 08:15:30 Walk from DRT dropoff to bus stop (30s)
  08:20 -> 08:45 Bus 42
  08:45 -> 08:48 Walk to office
  Total: 48 min, with 7 min waiting for DRT
```

### Example 2: Transit + DRT Egress (Temporally Infeasible)

**Scenario**: User takes train, needs DRT from station to home. DRT is busy.

```
Phase 3 - Raptor produces:
  Train arrives at station: 17:30
  A* car egress: Station -> Home, estimated 6 min by car

Phase 4 - Decoration:
  Passenger arrives at station: 17:30
  Walk to car pickup point: 2 min -> car leg starts at 17:32
  Shotl API call: pickup at Station, dropoff at Home, desired pickup 17:32 (start of the car leg)
  Response:
    expected_pickup: 17:25 (!!!)
    expected_dropoff: 17:35

  Temporal check:
    Passenger at pickup: 17:32
    DRT expected pickup: 17:25
    17:25 + 2 min tolerance = 17:27 < 17:32 -> INFEASIBLE!

  Result: itinerary flagged for deletion
  Reason: Shotl scheduled the vehicle before the passenger could possibly be there
```

### Example 3: Direct DRT vs Walking (Short Distance)

**Scenario**: 500m trip, user requests direct DRT.

```
Direct Street Router:
  Car path: 500m, 1 min by car
  Walk path: 500m, 6 min walk

Direct DRT Shifting:
  Shotl API: pickup at origin, dropoff at destination
  Response:
    expected_pickup: +10 min (vehicle en route from elsewhere)
    ride duration: 90s
    pickup_walking: 45s
    dropoff_walking: 20s

  The leg starts at the requested departure; the vehicle picks up 600s later.
  waiting = 600 - 45 = 555s (at the pickup point, after the 45s walk)
  DRT cost = (45 + 20) * 2.0 + 555 * 1.0 + 90 * 1.0 = 775 cost-seconds (formula in §9)
  DRT total time = 45s walk + 555s wait + 90s ride + 20s walk = 710s (11.8 min)

Walk itinerary:
  Walk cost = 360s * 2.0 = 720 cost-seconds
  Walk total time = 6 min

Filter chain comparison:
  Walk: 6 min, 720 cost
  DRT: 11.8 min, 775 cost (no access/egress penalty applies, §10)

  Walk wins on time (6 min vs 11.8 min) and on cost (720 vs 775)
  Which of the two is returned is decided by the filter chain (§12)
```

### Example 4: Evening DRT Egress (When DRT Shines)

**Scenario**: 21:30, sparse bus service, 1.4km walk from last stop to home.

```
Without DRT:
  Bus 84 -> arrive station 22:00 -> walk 1.4km (19 min) -> home 22:19
  Total: 49 min, long uncomfortable walk at night

With DRT egress:
  Bus 22 -> arrive station 21:40 -> DRT pickup 21:43 -> home 21:51
  DRT details:
    pickup_walking: 45s (walk to nearby pickup point)
    ride: 360s (6 min)
    dropoff_walking: 30s

  DRT cost = (45 + 30) * walkReluctance + waitingSeconds * waitReluctance + 360 * carReluctance
             (formula in §9; waitingSeconds runs from the passenger reaching the pickup point
              to the vehicle pickup)

Pareto comparison:
  Bus 84 + Walk: arrives 22:19, cost includes the long walk at 2.0 reluctance
  Bus 22 + DRT:  arrives 21:51, cost includes the DRT egress; no access/egress penalty (§10)

  DRT arrives 28 min earlier -> not dominated on arrival time
  -> DRT survives Pareto filtering and is offered to user
```

### Example 5: Multiple Siblings at a Train Station

**Scenario**: DRT to a train station with 4 platforms.

```
drt_stops.txt contains: Platform_1 (parent: Central_Station)

Phase 2 - Access Shifting:
  1. Shotl API call: origin -> Platform_1
     Response: pickup delay 5min, ride 8min, walk_to_pickup 60s, walk_from_dropoff 30s

  2. Sibling expansion:
     Platform_2: same DRT estimate + 90s walk transfer (Platform_1 -> Platform_2)
     Platform_3: same DRT estimate + 120s walk transfer
     Platform_4: same DRT estimate + 60s walk transfer

  Result: 4 access entries, only 1 Shotl API call

  Platform_1: duration = 60 + 480 + 30 = 570s
  Platform_2: duration = 60 + 480 + 30 + 90 = 660s (extra walk to sibling)
  Platform_3: duration = 60 + 480 + 30 + 120 = 690s
  Platform_4: duration = 60 + 480 + 30 + 60 = 630s
```

---

## 18. Key Source Files

### DRT Extension Package

| File | Purpose |
|------|---------|
| `ext/demandresponsivetransportation/DecorateWithDRT.java` | Post-Raptor filter: decorates egress/direct car legs with Shotl data |
| `ext/demandresponsivetransportation/DemandResponsiveTransportationAccessShifter.java` | Pre-Raptor: shifts access departure times using Shotl API |
| `ext/demandresponsivetransportation/DemandResponsiveTransportationAccessAdapter.java` | Wraps access/egress with real DRT timing and cost data |
| `ext/demandresponsivetransportation/DemandResponsiveTransportationService.java` | Service interface for DRT providers |
| `ext/demandresponsivetransportation/CachingDemandResponsiveTransportationService.java` | Base class with error handling and logging |
| `ext/demandresponsivetransportation/PerRequestDemandResponsiveTransportationService.java` | Per-HTTP-request cache layer |
| `ext/demandresponsivetransportation/model/DRTLeg.java` | StreetLeg subclass with Shotl estimate and cost computation |
| `ext/demandresponsivetransportation/service/shotl/ShotlService.java` | Shotl HTTP API client |
| `ext/demandresponsivetransportation/service/shotl/ShotlArrivalEstimateResponse.java` | Shotl API response record |
| `ext/demandresponsivetransportation/service/shotl/ShotlTimeEstimateRequest.java` | Shotl API request record |
| `ext/demandresponsivetransportation/service/shotl/ShotlApiResponse.java` | Raw Shotl API response wrapper |
| `ext/demandresponsivetransportation/service/shotl/ShotlBusinessRejectionException.java` | Exception for Shotl business rejections |
| `ext/demandresponsivetransportation/DrtIoExecutor.java` | Shared I/O thread pool (20 threads) for concurrent Shotl API calls |
| `ext/demandresponsivetransportation/DrtEstimateRequest.java` | Cache key (rounded coords + time) |
| `ext/demandresponsivetransportation/DrtRequestContext.java` | Enum: ACCESS_SHIFTING, EGRESS_SHIFTING, LEG_DECORATING, DIRECT_SHIFTING |
| `ext/demandresponsivetransportation/DrtStopsModule.java` | Graph build: loads drt_stops.txt |
| `ext/demandresponsivetransportation/DrtStopsDataReader.java` | Reads drt_stops.txt from GTFS feeds |
| `ext/demandresponsivetransportation/DemandResponsiveTransportationServiceParameters.java` | Config record: enabled, estimationsURL, providerName, areasURL |
| `ext/demandresponsivetransportation/JourneyAvailabilityFilter.java` | Pre-Raptor filter: drops DRT stops whose journey the provider cannot serve (§22) |
| `ext/demandresponsivetransportation/JourneyAvailabilityService.java` | Service interface for the journey-availability check |
| `ext/demandresponsivetransportation/service/shotlareas/ShotlAreasService.java` | Shotl areas HTTP client (`journey-availability` endpoint) |
| `ext/demandresponsivetransportation/service/shotlareas/JourneyAvailabilityRequest.java` | Journey-availability request record |
| `ext/demandresponsivetransportation/service/shotlareas/JourneyAvailabilityResponse.java` | Journey-availability response record |
| `ext/demandresponsivetransportation/configure/DemandResponsiveTransportationServicesModule.java` | Dagger DI module with HTTP client setup |

### Modified Core OTP Files

| File | DRT-Related Changes |
|------|-------------------|
| `routing/algorithm/RoutingWorker.java` | Direct DRT shifting, temporal overlap fixing |
| `routing/algorithm/filterchain/ItineraryListFilterChainBuilder.java` | DRT decorator as first filter |
| `routing/algorithm/mapping/RouteRequestToFilterChainMapper.java` | Creates DecorateWithDRT filter |
| `routing/algorithm/mapping/RaptorPathToItineraryMapper.java` | Converts access DRT adapter to DRTLeg |
| `routing/algorithm/raptoradapter/router/TransitRouter.java` | DRT stop filtering, journey-availability pre-filter, sibling expansion, egress reluctance |
| `routing/api/request/StreetMode.java` | DEMAND_RESPONSIVE_TRANSPORTATION mode definition |
| `routing/api/request/DemandResponsiveExtData.java` | DRT request parameters |
| `routing/api/request/Passengers.java` | Regular + wheelchair passenger counts |
| `routing/api/request/PassengerFareType.java` | Fare type for pricing |
| `routing/algorithm/filterchain/filters/transit/RemoveShortTransitItinerariesFilter.java` | minTransitDuration filter |

### GraphQL API Files

| File | Purpose |
|------|---------|
| `apis/gtfs/schema.graphqls` | DRTInput, DRTEstimate, DRT modes |
| `apis/gtfs/datafetchers/DRTEstimateImpl.java` | Resolves DRTEstimate fields |
| `apis/gtfs/datafetchers/LegImpl.java` | Exposes drtEstimate on legs |
| `apis/gtfs/mapping/routerequest/RouteRequestMapper.java` | Parses DRT input from GraphQL |
| `apis/gtfs/model/DRTGeoLocation.java` | GraphQL geo model |
| `apis/gtfs/model/DRTScheduledGeoLocation.java` | GraphQL scheduled location model |
| `apis/gtfs/model/DRTPassengers.java` | GraphQL passengers model |
| `apis/transmodel/mapping/TripRequestMapper.java` | Transmodel API DRT input parsing |

### Configuration

| File | Purpose |
|------|---------|
| `standalone/config/routerconfig/DemandResponsiveTransportationServicesConfig.java` | Parses DRT services from router-config.json |
| `standalone/config/routerconfig/services/ShotlConfig.java` | Parses one Shotl service entry: enabled, estimationsURL, providerName, areasURL |
| `config/staging/router-config.json` | Staging environment config (Shotl URL, minTransitDuration) |

---

## 19. Design improvement opportunities

The design improvement opportunities are in
[`DRT_Improvement_Opportunities.md`](DRT_Improvement_Opportunities.md). That file is a backlog,
not a set of working rules: apply none of it unless asked.

---

## 20. Performance: Current Optimizations and Improvement Opportunities

### 20.1 The Core Performance Challenge

Every DRT routing request triggers **multiple HTTP calls to the external Shotl API** — one per transit stop (access shifting), one per car leg per itinerary (decoration), and one per direct car leg. A single user request can produce **20-50+ Shotl API calls**, each with up to 60 seconds timeout. This external dependency dominates the request latency.

### 20.2 Current Optimizations

#### Connection Pooling

The Dagger module creates a shared `OtpHttpClient` (singleton) with increased limits:

```
Max total connections:  40  (default was lower)
Max per-host:           20  (default was 5)
```

This prevents connection pool starvation when many parallel DRT API calls target the same Shotl host. Without this, the default 5-per-host limit would serialize most requests, since all calls go to the same Shotl endpoint.

#### Per-Request Cache with Key Rounding

`PerRequestDemandResponsiveTransportationService` maintains a `ConcurrentHashMap` per HTTP request. The cache key (`DrtEstimateRequest`) applies rounding to increase hit rate:

```
Coordinates: rounded to ~10m precision  (4 decimal places)
Pickup time: rounded to 5-minute intervals  (seconds / 300 * 300)
```

This means two access legs to nearby stops (within 10m) requested within the same 5-minute window share a single API call. Example cache hit scenario:

```
Access to Stop_A at (41.38512, 2.17340), pickup 08:02 -> key: (41.3851, 2.1734, 08:00)
Access to Stop_B at (41.38518, 2.17345), pickup 08:03 -> key: (41.3852, 2.1735, 08:00)
  -> cache MISS (coordinates differ at 4th decimal)

Access to Stop_C at (41.38514, 2.17342), pickup 08:04 -> key: (41.3851, 2.1734, 08:00)
  -> cache HIT (same rounded key as Stop_A)
```

The cache lives only for the duration of one HTTP request — no cross-request caching exists.

#### Sibling Stop Expansion (API Call Avoidance)

When a DRT-eligible stop belongs to a parent station, the DRT estimate is computed once and reused for all sibling stops with only a walk transfer adjustment:

```
Parent station: Central_Station (4 platforms)
DRT-eligible: Platform_1

API calls without optimization: 4 (one per platform)
API calls with sibling expansion: 1 (Platform_1 only)
Savings: 3 API calls (75% reduction per station)
```

This is especially impactful for large stations (e.g., train stations with 8-12 platforms).

#### Dedicated I/O Thread Pool (DrtIoExecutor)

DRT API calls are I/O-bound (threads block waiting for HTTP responses), so `parallelStream()` — which sizes its pool to CPU core count via `ForkJoinPool` — was the wrong tool. On a single-core machine, `parallelStream()` runs everything sequentially, even though the CPU is idle while waiting for responses.

`DrtIoExecutor` provides a shared fixed thread pool of **20 daemon threads** (matching `DRT_MAX_CONN_PER_ROUTE`) using `CompletableFuture.supplyAsync(..., io)`. This ensures concurrent Shotl API calls regardless of CPU core count.

| Phase | Parallelism Strategy | Details |
|-------|---------------------|---------|
| **Top-level routing** | `CompletableFuture.allOf()` | Direct, Flex, and Transit routing run concurrently |
| **Access/egress fetch** | `CompletableFuture.allOf()` | Access and egress computed in parallel |
| **Access shifting** | `CompletableFuture` + `DrtIoExecutor` | All access entries shifted concurrently on I/O pool |
| **Leg decoration** | `CompletableFuture` + `DrtIoExecutor` | Distinct estimate requests across all itineraries fetched concurrently on I/O pool; results applied to the itineraries without I/O |
| **Direct DRT shifting** | `CompletableFuture` + `DrtIoExecutor` | All direct itineraries shifted concurrently on I/O pool |

#### Skip Already-Decorated Legs

`DecorateWithDRT` checks `leg instanceof DRTLeg` and skips the leg, both when it collects the estimate requests (`decorateWithDeduplication()`) and when it applies them (`decorateLegFromEstimates()`). This avoids redundant API calls for access legs that were already converted to DRTLeg during the `RaptorPathToItineraryMapper` phase.

### 20.3 API Call Count Analysis

Understanding the total API calls per request is critical for capacity planning:

```
Typical request: accessMode=DRT, egressMode=DRT, directMode=DRT

Phase 2 - Access Shifting:
  10 DRT-eligible stops found by A* (after drt_stops.txt filtering)
  3 stops in stations with siblings -> 3 API calls (expanded to 8 total entries)
  7 standalone stops -> 7 API calls
  Total: 10 API calls (parallel)

Phase 5 - Direct DRT Shifting:
  1 direct car itinerary with 1 car leg
  Total: 1 API call (direct itineraries are shifted concurrently)

Phase 4 - Leg Decoration:
  Raptor produces 5 itineraries, each with 1 egress car leg
  Access legs already decorated -> skipped
  Total: 5 API calls (parallel)
  Some may hit per-request cache if egress coordinates are similar

Grand total: ~16 API calls per user request
Worst case (many stops, many itineraries): 30-50+ calls
```

### 20.4 Performance Improvement Opportunities

#### 20.4.1 60-Second Timeout Is Too High (High Impact, Low Effort)

**Current**: `API_TIMEOUT = Duration.ofSeconds(60)`. The comment says "kept short", but 60 seconds is extremely long for a user-facing routing request. If Shotl is slow, 10 parallel access shifting calls each blocking 60 seconds means the user waits a full minute before getting any response.

**Suggestion**: Reduce to 10-15 seconds. A DRT estimate that takes longer than 15 seconds to compute is unlikely to be useful to the user. Combine with the circuit breaker from item 19.6 of `DRT_Improvement_Opportunities.md` to fast-fail after repeated timeouts.

```
Impact: Worst-case latency drops from 60s to 10-15s per phase
Risk: Some valid but slow Shotl responses get dropped (fallback: no DRT offered)
```

#### 20.4.2 Direct DRT Shifting Is Sequential — IMPLEMENTED

**Implemented**: `RoutingWorker.shiftDirectDrtItineraries()` now uses `CompletableFuture.supplyAsync()` with the shared `DrtIoExecutor` thread pool to shift all direct itineraries concurrently, instead of the previous sequential `for` loop.

#### 20.4.3 No Cross-Request Caching (Medium Impact, Medium Effort)

**Current**: Each HTTP request starts with an empty cache. Two users requesting similar routes at similar times each generate their own full set of API calls.

**Suggestion**: Add an optional short-lived cross-request cache (e.g., 2-minute TTL, bounded by size) sitting in front of the per-request cache. The per-request cache ensures unique estimation IDs per request; the cross-request cache stores the *timing data* (pickup delay, ride duration, walking seconds) which are reusable across requests.

```
Request 1 (08:01): origin A -> stop X -> Shotl API (cache miss) -> 350ms
Request 2 (08:02): origin A -> stop X -> cross-request cache HIT -> 0ms

Savings: Eliminates redundant calls for popular origin-stop pairs
Risk: Responses may be slightly stale (2 min window)
Mitigation: Use cross-request cache only for timing/cost estimation,
            always call Shotl for the final estimation ID used in booking
```

One concern is that Shotl estimates contain an `id` field that may be used for booking. If the cached `id` is reused for a different user's booking, it could cause issues. The cross-request cache should either strip the `id` field or only cache the timing/duration data, not the full response.

#### 20.4.4 Nested parallelStream() Can Oversubscribe the ForkJoinPool — IMPLEMENTED

**Implemented**: All three `parallelStream()` levels in `DecorateWithDRT` and the `parallelStream()` in `DemandResponsiveTransportationAccessShifter` have been replaced with `CompletableFuture.supplyAsync()` using the shared `DrtIoExecutor` thread pool (20 I/O threads). The innermost `parallelStream()` on legs was replaced with a sequential `stream()` since itineraries typically have only 1-2 car legs. This eliminates the `ForkJoinPool` bottleneck that caused all Shotl API calls to run sequentially on single-core deployments.

#### 20.4.5 Shotl API Batch Endpoint (High Impact, High Effort)

**Current**: Each leg generates one independent HTTP POST to `v3/drt/time-estimations`. 16-50 calls per routing request means 16-50 HTTP round-trips with TCP overhead, serialization/deserialization, and server-side processing.

**Suggestion**: If the Shotl API supports (or could support) a batch endpoint, send all estimation requests in a single HTTP call:

```json
POST v3/drt/time-estimations/batch
{
  "estimates": [
    { "from": {...}, "to": {...}, "desired_pickup_time": 1706965200 },
    { "from": {...}, "to": {...}, "desired_pickup_time": 1706965500 },
    ...
  ]
}
```

This would reduce HTTP overhead dramatically: 1 round-trip instead of 16-50, and the Shotl backend could optimize internal routing for nearby requests.

**Effort**: Requires changes on the Shotl API side (new endpoint) and on the OTP side (collect all estimation requests, batch them, distribute responses back to callers).

#### 20.4.6 Eagerly Filter Unreachable Stops Before API Calls (Low-Medium Impact, Low Effort)

**Current**: After A* finds car-reachable stops and `drt_stops.txt` filtering is applied, all remaining stops get Shotl API calls during access shifting. Some of these stops may be very far away (long car routes found by A*) and unlikely to produce competitive itineraries.

**Suggestion**: Add a pre-filter based on straight-line distance or A* cost before calling Shotl. For example, skip stops where the A* car cost already exceeds a configurable threshold (e.g., 30 minutes by car). These stops are unlikely to produce competitive DRT itineraries, so the API call is wasted.

```
Before: 15 DRT-eligible stops -> 15 API calls
After filter (max 15 min car access): 8 stops -> 8 API calls
Savings: 7 unnecessary API calls avoided
```

#### 20.4.7 INFO-Level Logging on Hot Path (Low Impact, Trivial Effort)

**Current**: The per-call lines in `CachingDemandResponsiveTransportationService.arrivalTimes()` (`API CALL`, `API RESPONSE`) and in `ShotlService` are logged at `DEBUG`. The per-estimate and per-leg lines are logged at `INFO`: in `DemandResponsiveTransportationAccessShifter.shiftTime()`, in `DecorateWithDRT` and in `RoutingWorker.shiftDirectDrtItinerary()`. In production with many concurrent requests, this generates significant log volume.

```java
LOG.info("[DRT] ACCESS shift | from=({},{}) → ({},{}) | ...");        // per access estimate
LOG.info("[DRT] decoration estimate | from=({},{}) → ({},{}) | ..."); // per decoration estimate
LOG.info("[DRT] {} leg decorated | from=({},{}) → ({},{}) | ...");    // per decorated leg
LOG.info("[DRT] direct decoration | from=({},{}) → ({},{}) | ...");   // per direct leg
```

With 20+ API calls per request and 100+ concurrent users, this produces thousands of log lines per second.

**Suggestion**: Move the per-estimate and per-leg lines to `DEBUG` level. Keep `INFO` for summary-level logging (e.g., total calls made, total cache hits, total duration per request). Keep `WARN` for business rejections and errors.

#### 20.4.8 Per-Request Cache Has No Size Bound (Low Impact, Low Effort)

**Current**: The `ConcurrentHashMap` in `PerRequestDemandResponsiveTransportationService` grows unbounded within a request. For normal requests this is fine (16-50 entries), but a pathological request (very large search area, many stops) could create a very large map.

**Suggestion**: In practice this is unlikely to be a real problem since the cache is garbage-collected after each request. But if defensive coding is desired, add a max size check and stop caching after N entries (e.g., 200), falling through to direct API calls.

### 20.5 Performance Summary

| Optimization | Status | API Calls Saved | Latency Impact |
|-------------|--------|-----------------|----------------|
| Connection pooling (40/20) | Implemented | 0 (enables parallelism) | Prevents serialization bottleneck |
| Per-request cache (10m, 5min rounding) | Implemented | ~10-20% per request | Avoids near-duplicate calls |
| Sibling stop expansion | Implemented | 50-75% per multi-platform station | Significant for train stations |
| Dedicated I/O thread pool (DrtIoExecutor) | Implemented | 0 (enables true parallelism) | Concurrent Shotl calls regardless of CPU cores; fixes single-core sequential bottleneck |
| Parallel access shifting | Implemented | 0 (enables parallelism) | Shifts from N*latency to max(latency) |
| Parallel leg decoration | Implemented | 0 (enables parallelism) | Shifts from N*latency to max(latency) |
| Parallel direct DRT shifting | Implemented | 0 (enables parallelism) | Was sequential, now concurrent on I/O pool |
| Sequential legs within itinerary | Implemented | 0 (removes overhead) | Eliminates ForkJoinPool overhead for 1-2 car legs |
| Skip decorated legs | Implemented | ~30-50% of decoration calls | Avoids re-decorating access legs |
| **Reduce timeout to 10-15s** | **Not implemented** | 0 | **Worst case 60s -> 10-15s** |
| **Cross-request cache** | **Not implemented** | **50-80% for popular routes** | **Major reduction for repeat queries** |
| **Batch Shotl API endpoint** | **Not implemented** | **Collapses N calls to 1** | **Eliminates HTTP round-trip overhead** |
| **Pre-filter distant stops** | **Not implemented** | **20-50% of access calls** | **Fewer wasted API calls** |
| Move logging to DEBUG | Implemented for the API call and response lines; per-estimate and per-leg lines are `INFO` | 0 | Reduces I/O contention under load |

---

## 21. DRT waiting time

Waiting at the pickup point is part of a DRT leg's duration and cost (§9):
`DRTLeg.waitingSeconds = max(0, vehiclePickup - (legStart + walkToPickup))`, exposed as
`DRTEstimate.waitingSeconds` in both GraphQL APIs. Tests: `model/DRTLegWaitingTimeTest.java`.

- **Egress and direct legs** are built with an explicit `legStartTime` (the 4-arg `DRTLeg`
  constructor), so the wait is inside the leg.
- **Access legs** use the 3-arg constructor, which back-computes the start time with zero
  waiting. Gaps before an access `DRTLeg` can therefore still exist, which is why
  `fixTemporalOverlaps` (§11) keeps its gap-preserving logic.

---

## 22. Journey-Availability Pre-Filter

### What It Does

For DRT access and egress, `JourneyAvailabilityFilter` asks the Shotl areas service which journeys
the provider can serve, and drops the stops whose journey is not available. It makes one batch
HTTP call per access or egress search. A dropped stop gets no Shotl rides call in access shifting
and no DRT access or egress path in Raptor.

### When It Runs

In `TransitRouter.fetchAccessEgresses()`, for the DRT mode only, right after the `drt_stops.txt`
filter (§3) and before access shifting (§4). One journey is checked per stop:

- Access: start of the car path (near the origin) -> stop coordinate
- Egress: stop coordinate -> start of the car path (near the destination; the egress search runs
  from the destination)

The call carries `drt.areaId` and `request.dateTime()` as the desired time. The filter is skipped,
and every stop kept, when no `areasURL` is configured or when the request has no `drt` data or no
`areaId`.

### Fail-Open

Every stop is kept when the call fails or returns no journeys, or when the response does not have
one entry per journey sent. A stop without a path state is kept without being checked.

### Endpoint, Classes and Configuration

`POST {areasURL}/journey-availability`, with a 10-second timeout. The request
(`JourneyAvailabilityRequest`) has `area_id`, `desired_time` (epoch seconds) and `journeys`, each
with `pickup_latitude`, `pickup_longitude`, `dropoff_latitude` and `dropoff_longitude`. The
response (`JourneyAvailabilityResponse`) has `journeys` in the same order, each with an
`available` flag.

- `JourneyAvailabilityFilter` and the `JourneyAvailabilityService` interface, in the extension
  package
- `service/shotlareas/ShotlAreasService`, the HTTP client
- `DemandResponsiveTransportationServicesModule.journeyAvailabilityService()` creates the client
  from the `areasURL` of the first configured service (§16)
- Tests: `JourneyAvailabilityFilterTest`, `service/shotlareas/JourneyAvailabilitySerializationTest`
