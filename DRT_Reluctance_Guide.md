# Reluctance in OpenTripPlanner — How It Works and How It Affects DRT

## 1. What Is Reluctance?

Reluctance is a **dimensionless multiplier** that converts real travel time (seconds) into
_generalized cost_ (also expressed in "cost-seconds"). It lets OTP express the idea that one second
of walking _feels_ worse than one second of riding in a car, even though the clock time is the
same.

```
generalized_cost = travel_time_seconds × reluctance
```

A higher reluctance makes a mode "more expensive" in the routing graph, so the planner will prefer
alternatives with lower total generalized cost. A reluctance of **1.0** means one second of real
time equals one cost-second (no penalty). A reluctance of **2.0** means each real second counts
double.

---

## 2. Default Reluctance Values for All Modes

| Mode / Context | Default Reluctance | Source Class |
|---|---|---|
| **Walk** | **2.0** | `WalkPreferences` |
| **Bike (cycling)** | **2.0** | `BikePreferences` |
| **Car (driving)** | **1.0** | `CarPreferences` |
| **Scooter** | **2.0** | `ScooterPreferences` |
| **Stairs (walk)** | **2.0** | `WalkPreferences.stairsReluctance` |
| **Walking a bike** | **5.0** | `VehicleWalkingPreferences.reluctance` |
| **Carrying bike on stairs** | **10.0** | `VehicleWalkingPreferences.stairsReluctance` |
| **Wait (transfer)** | **1.0** | `TransferPreferences.waitReluctance` |
| **Turn** | **1.0** | `StreetPreferences.turnReluctance` |
| **Walk safety factor** | **1.0** | `WalkPreferences.safetyFactor` |
| **Walk stairs time factor** | **3.0** | `WalkPreferences.stairsTimeFactor` (speed divisor, not a reluctance) |

> **DRT uses `StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION` which maps to `TraverseMode.CAR`**,
> so the car reluctance of **1.0** applies to DRT access/egress street routing.

### Wheelchair-specific reluctances (defaults)

| Parameter | Default |
|---|---|
| `inaccessibleStreetReluctance` | 25.0 |
| `slopeExceededReluctance` | 1.0 |
| `stairsReluctance` | 100.0 |

---

## 3. Where and When Reluctance Is Applied

Reluctance is applied during **Phase 1: Street Routing** (the A* graph search that finds
access/egress paths). It does **not** directly apply inside the Raptor transit router; instead,
the generalized cost computed during street routing is converted and passed as a fixed cost to
Raptor.

### 3.1 The Full Pipeline

```
┌─────────────────────────────────────────────────────────────────────┐
│  PHASE 1 — Street Routing (A* on the street graph)                  │
│                                                                     │
│  For each street edge:                                              │
│    1. Calculate speed (per-mode configuration or edge car speed)     │
│    2. Calculate travel time:  time = distance / speed               │
│    3. Apply reluctance:       weight = time × reluctance            │
│    4. Add turn cost:          weight += turnDuration × turnReluctance│
│    5. Add cost extensions     (if any)                              │
│    6. Accumulate into State.weight                                  │
│                                                                     │
│  Output: State with total weight (generalized cost in seconds)      │
├─────────────────────────────────────────────────────────────────────┤
│  FOR DRT: Access Shifter + Adapter                                  │
│                                                                     │
│  Calls Shotl API → gets real walk + ride durations                  │
│  REPLACES both duration and generalized cost:                       │
│    duration = walkToPickup + drtRide + walkFromDropoff              │
│    cost = (walkToPickup + walkFromDropoff) × walkReluctance         │
│          + drtRide × carReluctance                                  │
│  raptorCost = round(cost × 100)                                    │
├─────────────────────────────────────────────────────────────────────┤
│  CONVERSION — Street → Raptor (non-DRT modes)                       │
│                                                                     │
│  raptorCost = round(state.weight × 100)   (centi-seconds)          │
│  DefaultAccessEgress stores this as the access/egress cost          │
├─────────────────────────────────────────────────────────────────────┤
│  PHASE 2 — Raptor Transit Routing                                   │
│                                                                     │
│  Uses the access/egress cost to compare multi-modal itineraries.    │
│  waitReluctance applies HERE to waiting time at transit stops.      │
│  Transit reluctanceForMode (per TransitMode) applies HERE.          │
├─────────────────────────────────────────────────────────────────────┤
│  PHASE 3 — Itinerary Filtering / DRT Decoration                    │
│                                                                     │
│  DecorateWithDRT replaces the car leg with real DRT times and       │
│  prices. The generalized cost used for ranking was already          │
│  recomputed by the DRT Access Adapter with proper reluctances.      │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Per-Edge Cost Calculation (Code Path)

The cost for each `StreetEdge` is computed in `StreetEdge.java` → `otherTraversalCosts()` (for
CAR and other non-bike non-walk modes):

```java
// StreetEdge.java — otherTraversalCosts()
var time = getDistanceMeters() / speed;             // real travel time
var weight = time * StreetEdgeReluctanceCalculator   // generalized cost
               .computeReluctance(preferences, traverseMode, walkingBike, isStairs());
return new TraversalCosts(time, weight);
```

The reluctance is selected by `StreetEdgeReluctanceCalculator.computeReluctance()`:

```java
return switch (traverseMode) {
    case WALK    -> walkingBike ? pref.bike().walking().reluctance() : pref.walk().reluctance();
    case BICYCLE -> pref.bike().reluctance();
    case CAR     -> pref.car().reluctance();      // ← THIS IS WHAT DRT USES
    case SCOOTER -> pref.scooter().reluctance();
};
```

After the per-edge weight is computed, **turn cost** is added at intersections:

```java
weight += preferences.street().turnReluctance() * turnDuration;
```

The total accumulated `State.weight` becomes the generalized cost for the access/egress path.

---

## 4. How DRT Is Specifically Affected

### 4.1 DRT Mode Maps to CAR

In `StateData.java`, `StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION` maps directly to
`TraverseMode.CAR`:

```java
case CAR, CAR_TO_PARK, CAR_PICKUP, CAR_HAILING,
     DEMAND_RESPONSIVE_TRANSPORTATION -> TraverseMode.CAR;
```

This means:
- **Car reluctance (default 1.0)** is used for the street-graph weight of every DRT
  access/egress edge.
- **Car speed** comes from each `StreetEdge.getCarSpeed()` (set from OSM speed limits during
  graph build), not from a global preference.
- Walk reluctance (default 2.0) applies to the **walking portions** before/after the car leg
  (e.g., walking from origin to pickup point, or from drop-off to transit stop).

### 4.2 DRT Cost Flows Through Three Phases

| Phase | What happens | Which reluctance |
|---|---|---|
| **Street routing (A*)** | OTP routes CAR mode on the street graph to find reachable transit stops. Each edge's cost = `time × carReluctance`. Walking portions use `walkReluctance`. | `car.reluctance` = 1.0, `walk.reluctance` = 2.0 |
| **Access shifter** | `DemandResponsiveTransportationAccessShifter` calls the DRT API (e.g., Shotl) and wraps the result in a `DemandResponsiveTransportationAccessAdapter`. **Both the duration and the generalized cost are recomputed** from the DRT API response, replacing the stale A*-based values. | `walk.reluctance` for walking portions, `car.reluctance` for the DRT ride |
| **Raptor** | Uses the **recomputed** access generalized cost for itinerary ranking. | N/A (fixed cost from access adapter) |
| **Leg decoration** | `DecorateWithDRT` replaces car leg times with actual DRT pickup/dropoff times and adds fare info. This happens **after** Raptor has already selected the best itineraries. | N/A (post-ranking) |

### 4.3 How the Access Adapter Recomputes Cost

The Shotl DRT API returns the actual walking durations alongside the ride estimate:

- `pickup_walking_seconds` — walk time from origin to the vehicle pickup point
- `dropoff_walking_seconds` — walk time from the vehicle drop-off point to the transit stop
- `shotl_duration_seconds` — the DRT ride duration itself

The `DemandResponsiveTransportationAccessAdapter` uses these to compute a fresh generalized
cost that replaces the stale A*-based weight:

```java
private static int computeGeneralizedCost(
    int walkToPickupSeconds,
    int drtDurationSeconds,
    int walkFromDropoffSeconds,
    double walkReluctance,
    double carReluctance
) {
    double walkCost = (walkToPickupSeconds + walkFromDropoffSeconds) * walkReluctance;
    double drtCost  = drtDurationSeconds * carReluctance;
    return RaptorCostConverter.toRaptorCost(walkCost + drtCost);
}
```

The total access **duration** is also recomputed:
```
duration = walkToPickup + drtDuration + walkFromDropoff
```

This means Raptor now sees **consistent** duration and cost values that are both derived from
the same source of truth (the DRT API), with proper reluctance applied per travel mode.

### 4.4 Why This Matters

**Car reluctance directly affects which access/egress paths Raptor considers and how they
rank against each other.** Since the default car reluctance is **1.0** while walk reluctance is
**2.0**, the planner inherently prefers longer DRT rides over walking — which is sensible because
the DRT vehicle covers distance much faster than walking, and the per-second cost penalty
is lower.

Because the generalized cost is now recomputed from the actual DRT travel time (not OSM car
speeds), the ranking accurately reflects the real-world trade-offs. A DRT vehicle that takes
detours (shared ride) or travels slower will produce a higher cost, correctly making that
access path less attractive compared to alternatives.

---

## 5. How Reluctance Values Interact (Worked Example)

Consider a DRT access path where the Shotl API returns:
- `pickup_walking_seconds` = 120 (2 min walk to pickup)
- `shotl_duration_seconds` = 600 (10 min DRT ride)
- `dropoff_walking_seconds` = 60 (1 min walk from drop-off to transit stop)

### Walking portions (from DRT API)
```
walkToPickup      = 120 seconds
walkFromDropoff   = 60 seconds
walkReluctance    = 2.0 (default)
walkCost          = (120 + 60) × 2.0 = 360.0 cost-seconds
```

### DRT ride portion (from DRT API)
```
drtDuration       = 600 seconds
carReluctance     = 1.0 (default)
drtCost           = 600 × 1.0 = 600.0 cost-seconds
```

### Total access cost (computed by DemandResponsiveTransportationAccessAdapter)
```
total_cost   = 360.0 + 600.0 = 960.0 cost-seconds
raptor_cost  = round(960.0 × 100) = 96000 centi-seconds
total_duration = 120 + 600 + 60 = 780 seconds (13 minutes)
```

If you **increased** car reluctance to 2.0, the DRT ride portion would double:
```
drtCost      = 600 × 2.0 = 1200.0 cost-seconds
total_cost   = 360.0 + 1200.0 = 1560.0 cost-seconds   (+63%)
```

This would make DRT access legs score significantly worse relative to walking-only paths or
transit-only alternatives.

> **Note:** The duration stays the same (780s) regardless of reluctance — only the generalized
> cost changes. Raptor uses cost for ranking but duration for scheduling.

---

## 6. Configuration

### 6.1 router-config.json (Routing Defaults)

Reluctance values can be overridden in `router-config.json` under `routingDefaults`:

```json
{
  "routingDefaults": {
    "walkReluctance": 2.0,
    "carReluctance": 1.0,
    "bikeReluctance": 2.0,
    "waitReluctance": 1.0,
    "stairsReluctance": 2.0,
    "turnReluctance": 1.0
  }
}
```

### 6.2 Per-Request (GraphQL API)

Reluctance values can be set per-request in the GraphQL query:

```graphql
{
  planConnection(
    origin: { ... }
    destination: { ... }
    walkReluctance: 3.0    # Make walking feel costlier → prefer DRT for longer distances
    # Note: carReluctance is NOT directly exposed in the Transmodel API
    # but can be set in the GTFS GraphQL API
  ) {
    ...
  }
}
```

### 6.3 Impact on DRT Behavior

| Change | Effect on DRT |
|---|---|
| **Increase `walkReluctance`** | Walking portions become more expensive → DRT is preferred for longer origin-to-pickup and drop-off-to-stop distances |
| **Increase `carReluctance`** | Car/DRT driving portions become more expensive → shorter DRT legs are preferred, and walking-only or transit-only alternatives may win |
| **Decrease `carReluctance`** (below 1.0) | DRT driving becomes very cheap → planner heavily favors long DRT legs |
| **Increase `waitReluctance`** | Waiting at transit stops is penalized more → may lead to preferring DRT-only (direct) itineraries |

---

## 7. Valid Range and Normalization

Reluctance values are validated by `Units.reluctance()`:
- **Minimum:** 0.0
- **Maximum:** `Double.MAX_VALUE` (effectively unlimited)
- **Rounding:** 2 decimal places for values < 2.0, 1 decimal for values < 10.0, 0 decimals for
  values ≥ 10.0

---

## 8. Summary Diagram

```
                        ┌──────────────────┐
                        │   User Request    │
                        │ walkReluctance=2.0│
                        │ carReluctance=1.0 │
                        └────────┬─────────┘
                                 │
                    ┌────────────▼────────────┐
                    │  Street Router (A*)      │
                    │                          │
                    │  Walk edges:             │
                    │    weight = time × 2.0   │
                    │                          │
                    │  Car edges (DRT):        │
                    │    weight = time × 1.0   │
                    │                          │
                    │  Output: reachable stops │
                    │  + path geometry         │
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼─────────────────────┐
                    │  DRT Access Shifter + Adapter     │
                    │                                   │
                    │  Calls Shotl API, which returns:  │
                    │    pickup_walking_seconds          │
                    │    shotl_duration_seconds          │
                    │    dropoff_walking_seconds         │
                    │                                   │
                    │  RECOMPUTES both:                 │
                    │    duration = walk + DRT + walk    │
                    │    cost = walk×walkR + DRT×carR   │
                    │                                   │
                    │  (A* weight is REPLACED)           │
                    └────────────┬─────────────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │  Raptor Transit Router   │
                    │                          │
                    │  Uses recomputed cost    │
                    │  to rank itineraries.    │
                    │  waitReluctance=1.0      │
                    │  applies to waiting.     │
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │  DecorateWithDRT Filter  │
                    │                          │
                    │  Replaces car leg with   │
                    │  DRT pickup/dropoff times│
                    │  Adds fare information   │
                    │  (post-ranking)          │
                    └──────────────────────────┘
```

---

## 9. Key Source Files

| File | Role |
|---|---|
| `StreetEdgeReluctanceCalculator.java` | Selects the correct reluctance for a given mode |
| `StreetEdge.java` → `otherTraversalCosts()` | Computes `time × reluctance` for CAR edges |
| `CarPreferences.java` | Stores car reluctance (default 1.0) |
| `WalkPreferences.java` | Stores walk reluctance (default 2.0) |
| `BikePreferences.java` | Stores bike reluctance (default 2.0) |
| `ScooterPreferences.java` | Stores scooter reluctance (default 2.0) |
| `TransferPreferences.java` | Stores wait reluctance (default 1.0) |
| `StreetPreferences.java` | Stores turn reluctance (default 1.0) |
| `StateData.java` | Maps `DEMAND_RESPONSIVE_TRANSPORTATION` → `TraverseMode.CAR` |
| `DefaultAccessEgress.java` | Converts `State.weight` to Raptor cost |
| `RaptorCostConverter.java` | Converts domain cost (seconds) to Raptor cost (centi-seconds) |
| `DemandResponsiveTransportationAccessShifter.java` | Calls DRT API and passes walk durations + reluctances to adapter |
| `DemandResponsiveTransportationAccessAdapter.java` | Recomputes duration and generalized cost from DRT API data with proper reluctances |
| `DecorateWithDRT.java` | Post-ranking leg decoration with real DRT times/fares |
