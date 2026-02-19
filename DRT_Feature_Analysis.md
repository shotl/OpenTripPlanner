# DRT (Demand Responsive Transportation) Feature Analysis

This document analyzes the current DRT implementation in this OpenTripPlanner fork, compares it with the Uber ride-hailing feature, identifies missing components, and provides recommendations for improvement.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Current Implementation Status](#2-current-implementation-status)
3. [Architecture Comparison: DRT vs Ride-Hailing](#3-architecture-comparison-drt-vs-ride-hailing)
4. [How DRT Works](#4-how-drt-works)
5. [Issues and Missing Components](#5-issues-and-missing-components)
6. [Recommended Improvements](#6-recommended-improvements)
7. [Key Classes Reference](#7-key-classes-reference)

---

## 1. Overview

### What is DRT?

Demand Responsive Transportation (DRT) is a flexible transit service where vehicles operate on-demand rather than fixed routes. Unlike traditional transit, DRT:

- Picks up passengers at requested locations
- Adjusts routes dynamically based on demand
- Requires advance booking or real-time requests
- Often serves as first/last mile connectivity to fixed transit

### DRT vs Ride-Hailing (Uber)

| Aspect | DRT (Shotl) | Ride-Hailing (Uber) |
|--------|-------------|---------------------|
| **Service Type** | Shared, semi-scheduled | On-demand, private |
| **Booking** | Often requires advance notice | Immediate |
| **Pricing** | Usually fixed/subsidized | Dynamic/surge pricing |
| **Vehicle** | Minibus, shared vehicle | Private car |
| **Integration** | Deep transit integration | Supplementary service |

### StreetMode Definition

**Location:** `routing/api/request/StreetMode.java`

```java
DEMAND_RESPONSIVE_TRANSPORTATION(Feature.ACCESS, Feature.EGRESS, Feature.DRIVING, Feature.PICKUP)
```

Features:
- **ACCESS** - Can be used to get TO transit stops
- **EGRESS** - Can be used to leave FROM transit stops
- **DRIVING** - Involves vehicle travel
- **PICKUP** - Requires pickup/dropoff coordination

---

## 2. Current Implementation Status

### Component Checklist

| Component | Status | File Location |
|-----------|--------|---------------|
| StreetMode | ✅ Complete | `routing/api/request/StreetMode.java` |
| Service Interface | ✅ Complete | `ext/demandresponsivetransportation/DemandResponsiveTransportationService.java` |
| Shotl Service | ✅ Complete | `ext/demandresponsivetransportation/service/shotl/ShotlService.java` |
| Access Shifter | ✅ Complete | `ext/demandresponsivetransportation/DemandResponsiveTransportationAccessShifter.java` |
| Access Adapter | ✅ Complete | `ext/demandresponsivetransportation/DemandResponsiveTransportationAccessAdapter.java` |
| Decorator Filter | ✅ Complete | `ext/demandresponsivetransportation/DecorateWithDRT.java` |
| DRT Leg Model | ✅ Complete | `ext/demandresponsivetransportation/model/DRTLeg.java` |
| Request Data Model | ✅ Complete | `routing/api/request/DemandResponsiveExtData.java` |
| GraphQL Schema | ✅ Complete | `apis/gtfs/schema.graphqls` (DRTEstimate, DRTInput) |
| GraphQL Fetcher | ✅ Complete | `apis/gtfs/datafetchers/DRTEstimateImpl.java` |
| Filter Chain Integration | ✅ Complete | `routing/algorithm/mapping/RouteRequestToFilterChainMapper.java` |
| Transit Router Integration | ✅ Complete | `routing/algorithm/raptoradapter/router/TransitRouter.java` |
| Caching Layer | ❌ Missing | - |
| Provider Abstraction | ❌ Missing | - |
| Egress Time Shifting | ❌ Missing | - |

### File Structure

```
application/src/ext/java/org/opentripplanner/ext/demandresponsivetransportation/
├── DemandResponsiveTransportationService.java          # Service interface
├── DemandResponsiveTransportationServiceParameters.java # Config parameters
├── DemandResponsiveTransportationAccessShifter.java    # Time shifting logic
├── DemandResponsiveTransportationAccessAdapter.java    # Access/egress adapter
├── DecorateWithDRT.java                                # Filter decorator
├── model/
│   └── DRTLeg.java                                     # DRT-specific leg type
├── service/shotl/
│   ├── ShotlService.java                               # Shotl API client
│   ├── ShotlArrivalEstimateResponse.java              # API response model
│   └── ShotlTimeEstimateRequest.java                  # API request model
└── configure/
    └── DemandResponsiveTransportationServicesModule.java # DI module
```

---

## 3. Architecture Comparison: DRT vs Ride-Hailing

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         RIDE-HAILING (UBER)                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  RideHailingService (interface)                                        │
│       │                                                                 │
│       ├── provider(): RideHailingProvider      ◄── Provider tracking   │
│       ├── arrivalTimes(): List<ArrivalTime>    ◄── Generic types       │
│       └── rideEstimates(): List<RideEstimate>  ◄── Separate pricing    │
│                │                                                        │
│                ▼                                                        │
│  CachingRideHailingService (wrapper)           ◄── 2-minute cache      │
│                │                                                        │
│                ▼                                                        │
│  UberService (implementation)                                          │
│       │                                                                 │
│       └── Uses OAuth2 token management         ◄── Secure auth         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                         DRT (SHOTL) - Current                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  DemandResponsiveTransportationService (interface)                     │
│       │                                                                 │
│       └── arrivalTimes(): ShotlArrivalEstimateResponse                 │
│                │                     ▲                                  │
│                │                     └── Tightly coupled to Shotl!     │
│                │                                                        │
│                │  (no caching layer)              ◄── MISSING          │
│                │                                                        │
│                ▼                                                        │
│  ShotlService (implementation)                                         │
│       │                                                                 │
│       └── Uses header-based auth (Shotl-Passenger-App-Id)              │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Component-by-Component Comparison

#### Service Interface

**Ride-Hailing (3 methods, generic types):**
```java
public interface RideHailingService {
    RideHailingProvider provider();
    List<ArrivalTime> arrivalTimes(WgsCoordinate coord, boolean wheelchair);
    List<RideEstimate> rideEstimates(WgsCoordinate start, WgsCoordinate end, boolean wheelchair);
}
```

**DRT (1 method, Shotl-specific type):**
```java
public interface DemandResponsiveTransportationService {
    ShotlArrivalEstimateResponse arrivalTimes(
        String paxAppId, String areaId, String userId, String rideType,
        WgsCoordinate from, WgsCoordinate to,
        int regularPassengers, int wheelchairPassengers,
        Instant desiredPickupTime
    );
}
```

#### Decorator Filter

| Aspect | DecorateWithRideHailing | DecorateWithDRT |
|--------|-------------------------|-----------------|
| Constructor | `(services, wheelchairAccessible)` | `(services, request)` |
| Service call | Simple: `(start, end, wheelchair)` | Complex: 9 parameters from request |
| Response | `List<RideEstimate>` | `ShotlArrivalEstimateResponse` |
| Error handling | `ExecutionException` | `ExecutionException`, `IOException` |

#### Access Adapter

Both implementations are **virtually identical**:

```java
// Both extend DefaultAccessEgress and override:
@Override
public int earliestDepartureTime(int requestedDepartureTime) {
    return super.earliestDepartureTime(requestedDepartureTime) + (int) arrival.toSeconds();
}

@Override
public int latestArrivalTime(int requestedArrivalTime) {
    return super.latestArrivalTime(requestedArrivalTime) + (int) arrival.toSeconds();
}
```

#### Leg Model

**RideHailingLeg:**
```java
public class RideHailingLeg extends StreetLeg {
    private final RideEstimate estimate;
    private final RideHailingProvider provider;  // ← Provider tracking
}
```

**DRTLeg:**
```java
public class DRTLeg extends StreetLeg {
    private final ShotlArrivalEstimateResponse estimate;
    // No provider field
}
```

---

## 4. How DRT Works

### End-to-End Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         GraphQL Request                                 │
│                                                                         │
│  plan(                                                                  │
│    from: {...},                                                        │
│    to: {...},                                                          │
│    modes: { accessMode: DEMAND_RESPONSIVE_TRANSPORTATION },            │
│    drt: {                                                              │
│      areaId: "area-123",                                               │
│      userId: "user-456",                                               │
│      rideType: "ON_DEMAND",                                            │
│      passengers: { regular: 1, wheelchair: 0 }                         │
│    }                                                                    │
│  )                                                                      │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    LegacyRouteRequestMapper                             │
│                                                                         │
│  • Parses DRT input from GraphQL request                               │
│  • Creates DemandResponsiveExtData with:                               │
│    - paxAppId, areaId, userId, rideType                                │
│    - Passengers (regular + wheelchair counts)                          │
│  • Attaches to RouteRequest                                            │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    TransitRouter.route()                                │
│                                                                         │
│  1. Check if mode is DEMAND_RESPONSIVE_TRANSPORTATION                  │
│  2. Call DemandResponsiveTransportationAccessShifter.shiftAccesses()   │
│     │                                                                   │
│     ├── Query Shotl API for earliest pickup time                       │
│     ├── Calculate delay: pickupTime - requestedDeparture               │
│     └── Wrap access with DemandResponsiveTransportationAccessAdapter   │
│                                                                         │
│  3. Run RAPTOR with adjusted access times                              │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    ItineraryListFilterChain                             │
│                                                                         │
│  DecorateWithDRT filter:                                               │
│  │                                                                      │
│  ├── For each itinerary (parallel):                                    │
│  │   ├── For each leg:                                                 │
│  │   │   └── If leg is StreetLeg with car mode:                        │
│  │   │       ├── Call ShotlService.arrivalTimes()                      │
│  │   │       ├── Create DRTLeg with estimate                           │
│  │   │       └── Replace original leg                                  │
│  │   │                                                                  │
│  │   └── If no estimate available:                                     │
│  │       └── Flag itinerary for deletion                               │
│  │                                                                      │
│  └── Return decorated itineraries                                      │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    GraphQL Response                                     │
│                                                                         │
│  {                                                                      │
│    "plan": {                                                           │
│      "itineraries": [{                                                 │
│        "legs": [{                                                      │
│          "mode": "CAR",                                                │
│          "drtEstimate": {                                              │
│            "id": "ride-789",                                           │
│            "userExpectedPickupTime": 1706965200,                       │
│            "userExpectedDropoffTime": 1706966100,                      │
│            "scheduledPickupPlace": { "name": "Main St", ... },         │
│            "vehicleId": "vehicle-001"                                  │
│          }                                                              │
│        }, {                                                             │
│          "mode": "RAIL",                                               │
│          "route": { "shortName": "Line 1" }                            │
│        }]                                                               │
│      }]                                                                 │
│    }                                                                    │
│  }                                                                      │
└─────────────────────────────────────────────────────────────────────────┘
```

### Time Shifting Logic

The key innovation in DRT (and ride-hailing) is **time shifting** - adjusting the departure time to account for vehicle arrival.

**Location:** `DemandResponsiveTransportationAccessShifter.java`

```
User wants to leave at 10:00 AM
        │
        ▼
Query Shotl: "When can you pick up at origin?"
        │
        ▼
Shotl responds: "Pickup at 10:15 AM"
        │
        ▼
Calculate delay: 10:15 - 10:00 = 15 minutes
        │
        ▼
Shift RAPTOR search: Start searching from 10:15 AM
        │
        ▼
Find transit connections departing AFTER 10:15 AM
```

**Code:**
```java
Instant userExpectedPickupTime = Instant.ofEpochSecond(
    drtEstimationResponse.user_expected_pickup_time()
);

Duration pickupDelay = Duration.between(now, userExpectedPickupTime);
Duration untilReqTime = Duration.between(now, req.dateTime());
Duration totalDelay = pickupDelay.minus(untilReqTime);

if (totalDelay.isNegative()) {
    totalDelay = Duration.ZERO;
}
```

### Shotl API Integration

**Request:**
```java
POST {baseUrl}/v3/drt/time-estimations
Headers:
  - Content-Type: application/json
  - Shotl-Passenger-App-Id: {paxAppId}

Body:
{
  "area_id": "area-123",
  "user_id": "user-456",
  "ride_type": "ON_DEMAND",
  "pickup_location": { "latitude": 41.3851, "longitude": 2.1734 },
  "dropoff_location": { "latitude": 41.3902, "longitude": 2.1650 },
  "passengers": { "regular": 1, "wheelchair": 0 },
  "desired_pickup_time": 1706965200
}
```

**Response:**
```java
{
  "id": "ride-789",
  "user_id": "user-456",
  "type": "ON_DEMAND",
  "status": "ESTIMATED",
  "desired_pickup_location": { "latitude": 41.3851, "longitude": 2.1734 },
  "desired_dropoff_location": { "latitude": 41.3902, "longitude": 2.1650 },
  "scheduled_pickup_place": {
    "location": { "latitude": 41.3855, "longitude": 2.1730 },
    "name": "Main Street Stop"
  },
  "user_expected_pickup_time": 1706965500,
  "user_expected_dropoff_time": 1706966100,
  "passengers": { "regular": 1, "wheelchair": 0 },
  "vehicle_id": "vehicle-001"
}
```

---

## 5. Issues and Missing Components

### 5.1 Missing Caching Layer (High Priority)

**Problem:** Every request calls the Shotl API directly without caching.

**Impact:**
- High load on Shotl servers
- Slower response times (network latency on every call)
- Potential rate limiting
- Increased costs if API is metered

**Ride-hailing has:** `CachingRideHailingService` with 2-minute cache and coordinate rounding.

**Current DRT flow:**
```
Request → ShotlService → Shotl API → Response
Request → ShotlService → Shotl API → Response  (duplicate call!)
```

**Should be:**
```
Request → CachingDRTService → Cache HIT → Response (fast!)
Request → CachingDRTService → Cache MISS → Shotl API → Cache → Response
```

---

### 5.2 Service Interface Coupled to Shotl (Medium Priority)

**Problem:** The interface returns `ShotlArrivalEstimateResponse` directly, making it impossible to add other DRT providers.

**Current:**
```java
public interface DemandResponsiveTransportationService {
    ShotlArrivalEstimateResponse arrivalTimes(...);  // Shotl-specific!
}
```

**If you want to add another provider (e.g., Via, Padam):**
- Would need to return `ShotlArrivalEstimateResponse` (wrong!)
- Or create separate interface (code duplication)
- Or change interface (breaking change)

---

### 5.3 Egress Time Shifting Not Implemented (Medium Priority)

**Problem:** Only ACCESS legs are time-shifted. EGRESS legs (DRT from train station to home) don't account for vehicle arrival time.

**Current code:**
```java
if (isAccess && ae.getLastState().containsModeCar()) {
    // Only shifts access!
}
```

**Scenario not handled:**
```
User wants to ARRIVE home by 6:00 PM
    │
    ├── Takes train arriving at station at 5:45 PM
    │
    └── Needs DRT from station to home
        │
        └── But DRT might not arrive until 5:55 PM!
            │
            └── User actually arrives at 6:10 PM (LATE!)
```

---

### 5.4 Mode Detection Too Broad (Low Priority)

**Problem:** The decorator checks `sl.getMode().isInCar()` which matches ANY car mode.

**Current:**
```java
if (leg instanceof StreetLeg sl && sl.getMode().isInCar()) {
    // Decorates ALL car legs, not just DRT!
}
```

**Could incorrectly decorate:**
- Regular CAR legs (user's own car)
- CAR_TO_PARK legs (park-and-ride)
- CAR_PICKUP legs (kiss-and-ride)

---

### 5.5 TODO in Code (Low Priority)

**Location:** `DemandResponsiveTransportationAccessShifter.java:114`

```java
req.dateTime().isBefore(now.plus(MAX_DURATION_FROM_NOW)) && // TODO review this for DRT
```

The 30-minute threshold might not be appropriate for DRT services that often require advance booking (e.g., book 1 hour ahead).

---

### 5.6 No Provider Tracking (Low Priority)

**Problem:** `DRTLeg` doesn't track which provider served the request.

**Ride-hailing has:**
```java
public class RideHailingLeg extends StreetLeg {
    private final RideHailingProvider provider;  // UBER, LYFT, etc.
}
```

**DRT has:**
```java
public class DRTLeg extends StreetLeg {
    // No provider field - assumes Shotl
}
```

---

### 5.7 No Error Recovery (Low Priority)

**Problem:** If Shotl API fails, the itinerary is deleted entirely.

**Current:**
```java
} catch (ExecutionException e) {
    LOG.error("Could not get DRT estimate for Shotl", e);
    flagForDeletion(i);  // Itinerary completely lost!
    return leg;
}
```

**Better approach:** Keep the itinerary but mark DRT as unavailable, allowing user to see the transit option without DRT.

---

## 6. Recommended Improvements

### Priority Matrix

| # | Improvement | Priority | Effort | Impact |
|---|-------------|----------|--------|--------|
| 1 | Add caching layer | **High** | Medium | Performance, API load |
| 2 | Abstract service interface | **High** | Medium | Extensibility |
| 3 | Implement egress shifting | **Medium** | Low | Feature completeness |
| 4 | Fix mode detection | **Medium** | Low | Correctness |
| 5 | Add provider enum/field | Low | Low | Tracking |
| 6 | Configurable time threshold | Low | Low | Flexibility |
| 7 | Error recovery | Low | Low | User experience |

---

### 6.1 Caching Layer Implementation

**Create:** `CachingDemandResponsiveTransportationService.java`

```java
public class CachingDemandResponsiveTransportationService
    implements DemandResponsiveTransportationService {

    private static final Duration CACHE_DURATION = Duration.ofMinutes(2);
    private static final int COORDINATE_PRECISION = 4; // ~10m accuracy

    private final Cache<String, ShotlArrivalEstimateResponse> cache;
    private final DemandResponsiveTransportationService delegate;

    public CachingDemandResponsiveTransportationService(
        DemandResponsiveTransportationService delegate
    ) {
        this.delegate = delegate;
        this.cache = CacheBuilder.newBuilder()
            .expireAfterWrite(CACHE_DURATION)
            .build();
    }

    @Override
    public ShotlArrivalEstimateResponse arrivalTimes(...) {
        String key = buildCacheKey(areaId, from, to, rideType, passengers);

        try {
            return cache.get(key, () -> delegate.arrivalTimes(...));
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private String buildCacheKey(String areaId, WgsCoordinate from,
                                  WgsCoordinate to, String rideType, ...) {
        return String.format("%s:%.4f,%.4f->%.4f,%.4f:%s:%d:%d",
            areaId,
            from.latitude(), from.longitude(),
            to.latitude(), to.longitude(),
            rideType,
            regularPassengers, wheelchairPassengers
        );
    }
}
```

---

### 6.2 Provider Abstraction

**Create generic types:**

```java
// Generic provider enum
public enum DRTProvider {
    SHOTL,
    VIA,
    PADAM,
    // Future providers...
}

// Generic estimate type
public record DRTEstimate(
    DRTProvider provider,
    String rideId,
    Duration pickupDelay,
    Instant expectedPickupTime,
    Instant expectedDropoffTime,
    WgsCoordinate scheduledPickupLocation,
    WgsCoordinate scheduledDropoffLocation,
    String scheduledPickupName,
    String vehicleId,
    // Optional pricing
    @Nullable Money price
) {}

// Updated interface
public interface DemandResponsiveTransportationService {
    DRTProvider provider();
    DRTEstimate getEstimate(DRTEstimateRequest request);
}

// Shotl adapter
public class ShotlService implements DemandResponsiveTransportationService {
    @Override
    public DRTProvider provider() {
        return DRTProvider.SHOTL;
    }

    @Override
    public DRTEstimate getEstimate(DRTEstimateRequest request) {
        ShotlArrivalEstimateResponse response = callShotlApi(request);
        return mapToGenericEstimate(response);
    }
}
```

---

### 6.3 Egress Shifting

**Update:** `DemandResponsiveTransportationAccessShifter.java`

```java
public static List<RoutingAccessEgress> shiftAccesses(
    boolean isAccess,
    List<RoutingAccessEgress> results,
    List<DemandResponsiveTransportationService> services,
    RouteRequest request,
    Instant now
) {
    return results.stream().map(ae -> {
        if (ae.getLastState().containsModeCar()) {
            // Shift ACCESS for depart-at searches
            // Shift EGRESS for arrive-by searches
            boolean shouldShift = (isAccess && !request.arriveBy())
                               || (!isAccess && request.arriveBy());

            if (shouldShift) {
                var duration = fetchArrivalDelay(services, request, now, isAccess);
                if (duration.isSuccess()) {
                    return new DemandResponsiveTransportationAccessAdapter(
                        ae, duration.successValue()
                    );
                }
            }
        }
        return ae;
    }).filter(Objects::nonNull).collect(Collectors.toList());
}
```

---

### 6.4 Mode Detection Fix

**Update:** `DecorateWithDRT.java`

```java
private Leg decorateLegWithRideEstimate(
    Itinerary i,
    Leg leg,
    DemandResponsiveTransportationService service
) {
    // Check if this is specifically a DRT leg, not just any car leg
    if (leg instanceof StreetLeg sl && isDRTLeg(sl)) {
        // ... decorate
    }
    return leg;
}

private boolean isDRTLeg(StreetLeg leg) {
    // Option 1: Check leg mode matches DRT
    // Option 2: Check if leg was created from DRT access/egress
    // Option 3: Add a marker to legs created via DRT mode
    return leg.getMode().isInCar()
        && request.journey().modes().accessMode == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION;
}
```

---

## 7. Key Classes Reference

### Core DRT Classes

| Class | Location | Responsibility |
|-------|----------|----------------|
| **DemandResponsiveTransportationService** | `ext/demandresponsivetransportation/` | Service interface for DRT providers |
| **ShotlService** | `ext/demandresponsivetransportation/service/shotl/` | Shotl API client implementation |
| **DemandResponsiveTransportationAccessShifter** | `ext/demandresponsivetransportation/` | Adjusts departure time for vehicle arrival |
| **DemandResponsiveTransportationAccessAdapter** | `ext/demandresponsivetransportation/` | Wraps access/egress with arrival delay |
| **DecorateWithDRT** | `ext/demandresponsivetransportation/` | Filter that enriches legs with DRT estimates |
| **DRTLeg** | `ext/demandresponsivetransportation/model/` | StreetLeg with DRT estimate data |

### Request/Response Models

| Class | Location | Responsibility |
|-------|----------|----------------|
| **DemandResponsiveExtData** | `routing/api/request/` | DRT parameters in route request |
| **Passengers** | `routing/api/request/` | Passenger count (regular + wheelchair) |
| **ShotlTimeEstimateRequest** | `ext/.../service/shotl/` | Request body for Shotl API |
| **ShotlArrivalEstimateResponse** | `ext/.../service/shotl/` | Response from Shotl API |

### Integration Points

| Class | Location | DRT Integration |
|-------|----------|-----------------|
| **TransitRouter** | `routing/algorithm/raptoradapter/router/` | Calls `DemandResponsiveTransportationAccessShifter` |
| **RouteRequestToFilterChainMapper** | `routing/algorithm/mapping/` | Adds `DecorateWithDRT` to filter chain |
| **LegImpl** | `apis/gtfs/datafetchers/` | Exposes `drtEstimate` field in GraphQL |
| **DRTEstimateImpl** | `apis/gtfs/datafetchers/` | GraphQL resolver for DRT estimate fields |

### GraphQL Schema

| Type | Location | Purpose |
|------|----------|---------|
| **DRTInput** | `apis/gtfs/schema.graphqls` | Input for DRT request parameters |
| **DRTPassengersInput** | `apis/gtfs/schema.graphqls` | Input for passenger counts |
| **DRTEstimate** | `apis/gtfs/schema.graphqls` | Output type with DRT estimate data |

---

## Summary

The DRT implementation is **functionally complete** and follows the same architectural pattern as the Uber ride-hailing feature. The main areas for improvement are:

1. **Add caching** to reduce API load and improve performance
2. **Abstract the interface** to support multiple DRT providers in the future
3. **Implement egress shifting** for complete arrive-by search support

The current implementation successfully:
- Integrates with the Shotl API
- Shifts access times based on vehicle arrival
- Decorates itineraries with DRT information
- Exposes data through the GraphQL API
- Combines DRT with transit for multimodal journeys

With the recommended improvements, the DRT feature will be production-ready and extensible for future provider integrations.
