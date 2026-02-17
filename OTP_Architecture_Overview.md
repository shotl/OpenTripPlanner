# OpenTripPlanner Architecture Overview

This document explains how OpenTripPlanner (OTP) processes a trip planning request end-to-end, from the GraphQL API layer through the routing engine to the final response.

---

## Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [GraphQL API Layer](#2-graphql-api-layer)
3. [Request Processing Flow](#3-request-processing-flow)
4. [Routing Algorithms](#4-routing-algorithms)
5. [Core Data Model](#5-core-data-model)
6. [Street and Transit Networks](#6-street-and-transit-networks)
7. [CAR Mode vs Transit](#7-car-mode-vs-transit)
8. [Response Generation](#8-response-generation)
9. [Key Classes Reference](#9-key-classes-reference)

---

## 1. High-Level Architecture

OpenTripPlanner uses a layered architecture with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────────┐
│                     GraphQL API Layer                           │
│         (GTFS API & Transmodel API)                            │
├─────────────────────────────────────────────────────────────────┤
│                     Routing Service                             │
│              (DefaultRoutingService)                            │
├─────────────────────────────────────────────────────────────────┤
│                     Routing Worker                              │
│    ┌──────────────┬──────────────┬──────────────┐              │
│    │   Direct     │    Direct    │   Transit    │              │
│    │   Street     │    Flex      │   Router     │              │
│    │   Router     │    Router    │              │              │
│    └──────────────┴──────────────┴──────────────┘              │
├─────────────────────────────────────────────────────────────────┤
│                     Algorithm Layer                             │
│         ┌─────────────────┬─────────────────┐                  │
│         │   A* Algorithm  │ RAPTOR Algorithm│                  │
│         │   (Streets)     │   (Transit)     │                  │
│         └─────────────────┴─────────────────┘                  │
├─────────────────────────────────────────────────────────────────┤
│                     Data Layer                                  │
│    ┌──────────────────────┬──────────────────────┐             │
│    │   Street Graph       │   Transit Model      │             │
│    │   (Vertices/Edges)   │   (Patterns/Trips)   │             │
│    └──────────────────────┴──────────────────────┘             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. GraphQL API Layer

OTP provides **two parallel GraphQL APIs** with different design philosophies:

### 2.1 GTFS GraphQL API (Schema-First)

**Entry Point:** `POST /gtfs/v1/`

| Component | File | Responsibility |
|-----------|------|----------------|
| **GtfsGraphQLAPI** | `apis/gtfs/GtfsGraphQLAPI.java` | REST endpoint handler; parses HTTP requests |
| **GtfsGraphQLIndex** | `apis/gtfs/GtfsGraphQLIndex.java` | Executes GraphQL queries with timeout and complexity limits |
| **schema.graphqls** | `apis/gtfs/schema.graphqls` | GraphQL schema definition |
| **QueryTypeImpl** | `apis/gtfs/datafetchers/QueryTypeImpl.java` | Root query resolver |
| **GraphQLRequestContext** | `apis/gtfs/GraphQLRequestContext.java` | Provides services (routing, transit, fares) to resolvers |

**Example Query:**
```graphql
{
  plan(
    from: { lat: 52.3092, lon: 13.0291 }
    to: { lat: 52.5147, lon: 13.3927 }
    date: "2023-02-15"
    time: "11:37"
  ) {
    itineraries {
      start
      end
      legs {
        mode
        from { name }
        to { name }
      }
    }
  }
}
```

### 2.2 Transmodel GraphQL API (Code-First)

**Entry Point:** `POST /transmodel/v3/`

| Component | File | Responsibility |
|-----------|------|----------------|
| **TransmodelAPI** | `apis/transmodel/TransmodelAPI.java` | REST endpoint handler |
| **TransmodelGraph** | `apis/transmodel/TransmodelGraph.java` | Executes GraphQL with error limiting |
| **TransmodelGraphQLSchema** | `apis/transmodel/TransmodelGraphQLSchema.java` | Programmatically constructs schema |
| **TransmodelGraphQLPlanner** | `apis/transmodel/TransmodelGraphQLPlanner.java` | Executes trip planning |
| **TripRequestMapper** | `apis/transmodel/mapping/TripRequestMapper.java` | Converts GraphQL args to RouteRequest |

### 2.3 Request Flow Through API Layer

```
HTTP POST Request (JSON with query, variables)
    │
    ▼
GtfsGraphQLAPI.getGraphQL() / TransmodelAPI.getGraphQL()
    │
    ├── Parse JSON payload
    ├── Extract query, variables, operationName
    │
    ▼
GtfsGraphQLIndex.getGraphQLResponse() / TransmodelGraph.executeGraphQL()
    │
    ├── Create ExecutionInput with context
    ├── Apply instrumentation (complexity limits, metrics)
    ├── Build GraphQL executor
    │
    ▼
Execute Query → DataFetchers resolve fields
    │
    ▼
JSON Response
```

---

## 3. Request Processing Flow

### 3.1 Complete End-to-End Flow

```
GraphQL Query
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  API Layer: TripRequestMapper                               │
│  Converts GraphQL arguments → RouteRequest                  │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  DefaultRoutingService.route(RouteRequest)                  │
│  Entry point for all routing                                │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  RoutingWorker.route()                                      │
│  Orchestrates three parallel routing modes                  │
│                                                             │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐           │
│  │   Direct    │ │   Direct    │ │  Transit    │           │
│  │   Street    │ │    Flex     │ │   Router    │           │
│  │   Router    │ │   Router    │ │             │           │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘           │
│         │               │               │                   │
│         └───────────────┼───────────────┘                   │
│                         │                                   │
│                         ▼                                   │
│              Combine Results                                │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  ItineraryListFilterChain                                   │
│  Filter, sort, and paginate results                         │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  RoutingResponse                                            │
│  Contains List<Itinerary> and metadata                      │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
GraphQL Response (JSON)
```

### 3.2 Transit Router Detail

The `TransitRouter` is the most complex component, handling scheduled public transit:

```
TransitRouter.route()
    │
    ├── 1. Validate service period (check if transit operates)
    │
    ├── 2. AccessEgressRouter
    │   │   Find nearby transit stops from origin/destination
    │   │   Uses A* on street network
    │   └── Returns: List<RoutingAccessEgress>
    │
    ├── 3. RaptorRequestMapper
    │   │   Convert RouteRequest → RaptorRequest
    │   └── Include access/egress connections
    │
    ├── 4. RaptorService.route()
    │   │   Execute RAPTOR algorithm
    │   └── Returns: RaptorResponse with paths
    │
    ├── 5. TransferOptimizationService (optional)
    │   │   Optimize guaranteed/conditional transfers
    │   └── Improve transfer points
    │
    └── 6. RaptorPathToItineraryMapper
        │   Convert Raptor paths → OTP Itineraries
        └── Returns: List<Itinerary>
```

---

## 4. Routing Algorithms

### 4.1 RAPTOR Algorithm (Transit Routing)

**Location:** `raptor/src/main/java/org/opentripplanner/raptor/`

RAPTOR (Round-based Public Transit Optimized Router) is the primary transit routing algorithm.

**Key Characteristics:**
- Round-based: Each round represents one additional transit vehicle
- Range search: Searches over a departure time window
- Multi-criteria: Finds Pareto-optimal solutions (not dominated on any criteria)

**Reference:** Delling, Pajor, Werneck (2012) "Round-Based Public Transit Routing"

**Core Classes:**

| Class | Responsibility |
|-------|----------------|
| **RangeRaptor** | Main algorithm implementation; iterates over departure minutes |
| **RaptorService** | Service coordinator; handles request/response |
| **McRangeRaptorWorker** | Multi-criteria worker for Pareto-optimal paths |
| **StopArrivalParetoSet** | Maintains non-dominated arrivals at each stop |

**Algorithm Flow:**

```
For each departure minute in search window (backwards):
    │
    ├── Round 0: Mark origin stops as reached
    │
    └── For each round k = 1, 2, 3, ...:
        │
        ├── Route phase: Board transit, ride to stops
        │   For each route pattern serving marked stops:
        │       For each trip in pattern:
        │           Propagate arrivals along route
        │
        ├── Transfer phase: Walk to nearby stops
        │   For each newly reached stop:
        │       Mark stops reachable by transfer
        │
        └── If destination reached and no improvement possible:
            Stop search
```

**Multi-Criteria Optimization:**

RAPTOR maintains Pareto-optimal solutions based on:
- **Arrival time** (minimize)
- **Generalized cost** (combines time, wait, transfers, comfort)
- **Number of transfers** (minimize)

### 4.2 A* Algorithm (Street Routing)

**Location:** `application/src/main/java/org/opentripplanner/astar/`

A* is used for street network routing (walking, cycling, driving).

**Key Classes:**

| Class | Responsibility |
|-------|----------------|
| **AStar** | Generic A* implementation |
| **EuclideanRemainingWeightHeuristic** | Admissible heuristic for remaining cost |
| **DominanceFunction** | Determines state dominance for pruning |
| **BinHeap** | Priority queue for frontier management |

**Algorithm:**

```
Initialize:
    Open set (priority queue) with origin state
    Closed set (visited states)

While open set not empty:
    │
    ├── Pop state with lowest f(n) = g(n) + h(n)
    │   g(n) = cost from origin
    │   h(n) = heuristic estimate to destination
    │
    ├── If destination reached:
    │   Return path
    │
    ├── Add to closed set
    │
    └── For each outgoing edge:
        │
        ├── Calculate new cost
        │
        └── If improvement:
            Add/update in open set
```

**Usage in OTP:**
- **Access routing:** Finding transit stops reachable from origin
- **Egress routing:** Finding destination from transit stops
- **Direct street routing:** Point-to-point without transit
- **Transfer walking:** Connections between stops

---

## 5. Core Data Model

### 5.1 Trip Planning Results

**Location:** `application/src/main/java/org/opentripplanner/model/plan/`

```
TripPlan
    │
    └── List<Itinerary>
            │
            └── List<Leg>
                    │
                    ├── ScheduledTransitLeg (transit vehicle)
                    ├── StreetLeg (walk/bike/car)
                    └── FlexibleTransitLeg (demand-responsive)
```

| Entity | Responsibility |
|--------|----------------|
| **Itinerary** | Complete journey from origin to destination |
| **Leg** | Single segment (one mode of transport) |
| **Place** | Location with name, coordinates, stop reference |
| **StopArrival** | Intermediate stop during transit leg |

**Itinerary Properties:**
- Duration (total, transit, walking)
- Number of transfers
- Generalized cost (optimization metric)
- Elevation gain/loss
- Fare information
- Accessibility score

### 5.2 Transit Network Model

**Location:** `application/src/main/java/org/opentripplanner/transit/model/`

```
Agency
    │
    └── Route
            │
            └── TripPattern
                    │
                    ├── StopPattern (sequence of stops)
                    │
                    └── Timetable
                            │
                            └── List<TripTimes>
                                    │
                                    └── Trip
```

| Entity | Responsibility |
|--------|----------------|
| **Agency** | Transit operator/authority |
| **Route** | A transit line (e.g., "Bus 42") |
| **TripPattern** | Groups trips with same stop sequence |
| **Trip** | Single vehicle journey |
| **TripTimes** | Arrival/departure times at each stop |
| **RegularStop** | Physical stop location |
| **Station** | Groups multiple stops (e.g., train station) |

### 5.3 Request Objects

| Entity | Responsibility |
|--------|----------------|
| **RouteRequest** | Main trip planning query with all parameters |
| **GenericLocation** | Origin/destination (coordinates or address) |
| **RoutingPreferences** | User preferences (walk speed, transfer penalty, etc.) |
| **JourneyRequest** | Mode-specific constraints |

---

## 6. Street and Transit Networks

### 6.1 Dual Network Architecture

OTP maintains two separate but connected networks:

```
┌────────────────────────────────────────┐
│          STREET NETWORK (Graph)        │
│                                        │
│  ┌─────┐     ┌─────┐     ┌─────┐      │
│  │ V1  │─────│ V2  │─────│ V3  │      │
│  └─────┘     └──┬──┘     └─────┘      │
│               │                        │
│          ┌────┴────┐                   │
│          │TransitStop│ ◄── Connection  │
│          │  Vertex  │     Point        │
│          └────┬────┘                   │
└───────────────┼────────────────────────┘
                │
                ▼
┌────────────────────────────────────────┐
│       TRANSIT NETWORK (Separate)       │
│                                        │
│  ┌─────────────────────────────────┐   │
│  │         TripPattern             │   │
│  │                                 │   │
│  │  Stop1 ──► Stop2 ──► Stop3     │   │
│  │    │                            │   │
│  │    └── TripTimes (schedule)     │   │
│  └─────────────────────────────────┘   │
│                                        │
│  ┌─────────────────────────────────┐   │
│  │      RaptorTransitData          │   │
│  │  (Optimized for RAPTOR)         │   │
│  └─────────────────────────────────┘   │
└────────────────────────────────────────┘
```

### 6.2 Street Network (Graph)

**Location:** `application/src/main/java/org/opentripplanner/routing/graph/`

| Component | Responsibility |
|-----------|----------------|
| **Graph** | Main container for street network |
| **Vertex** | Node (intersection, stop, parking, etc.) |
| **Edge** | Connection between vertices |
| **StreetIndex** | Spatial indexing for fast lookups |

**Vertex Types:**
- `StreetVertex` - Street intersection
- `TransitStopVertex` - Links to transit stop
- `VehicleRentalPlaceVertex` - Bike/scooter rental
- `VehicleParkingEntranceVertex` - Parking facility

**Edge Types:**
- `StreetEdge` - Street segment with permissions
- `AreaEdge` - Traversal through pedestrian area
- `ElevatorEdge` - Elevator connections
- `PathTransfer` - Pre-computed transfer paths

### 6.3 Transit Network

The transit network is **separate from the street graph** for efficiency.

**Raptor-Optimized Structures:**

| Component | Responsibility |
|-----------|----------------|
| **RaptorTransitData** | Indexed transit data for RAPTOR |
| **TripPatternForDate** | Patterns filtered for specific date |
| **RaptorTransferIndex** | Pre-computed transfers between stops |
| **ConstrainedTransfersForPatterns** | Guaranteed/timed transfers |

### 6.4 Connection Between Networks

The `TransitStopVertex` bridges the two networks:

```
Street Network:                Transit Network:

   Vertex ─── Edge ───        TripPattern
                     \            │
                      TransitStopVertex ─── RegularStop
                     /            │
   Vertex ─── Edge ───        StopTimes
```

**Access/Egress Routing:**
1. User specifies origin coordinates
2. A* searches street network from origin
3. Finds all `TransitStopVertex` within time limit
4. These become "access" connections for RAPTOR
5. Same process in reverse for "egress" from destination

---

## 7. CAR Mode vs Transit

This section explains how CAR routing differs from transit routing in terms of algorithms, cost calculation, and filtering.

### 7.1 Algorithm Differences

| Aspect | CAR | Transit |
|--------|-----|---------|
| **Algorithm** | A* on street graph | RAPTOR on transit network |
| **Data Structure** | Graph (vertices/edges) | TripPatterns, TripTimes |
| **Speed Source** | `edge.getCarSpeed()` from OSM | Schedule-based |
| **Real-time** | Traffic (if configured) | GTFS-RT delays |
| **Router** | DirectStreetRouter | TransitRouter |

CAR routing is handled entirely by **DirectStreetRouter** using A* on the street graph, while transit uses **TransitRouter** with the RAPTOR algorithm.

### 7.2 Street Modes for CAR

**Location:** `routing/api/request/StreetMode.java`

| Mode | Description | Use Case |
|------|-------------|----------|
| `CAR` | Drive entire trip | Point-to-point driving |
| `CAR_TO_PARK` | Drive → Park → Walk to transit | Park-and-Ride (access only) |
| `CAR_PICKUP` | Walk → Pickup → Drive → Drop-off → Walk | Kiss-and-Ride, Taxi |
| `CAR_RENTAL` | Walk → Rent car → Drive | Car sharing |
| `CAR_HAILING` | Uber/Lyft style | Ride-hailing services |
| `FLEXIBLE_ACCESS` | DRT services | Demand-responsive transport |

### 7.3 Cost Calculation Differences

**Location:** `street/model/edge/StreetEdge.java`

| Cost Factor | Walk | Bicycle | CAR |
|-------------|------|---------|-----|
| **Speed** | User preference | User preference | Edge speed (from OSM) |
| **Reluctance** | `walk().reluctance()` | `bike().reluctance()` | `car().reluctance()` (default 2.0) |
| **Safety factors** | walkSafetyFactor | bicycleSafetyFactor | None |
| **Slope adjustment** | Yes | Yes (significant) | No |
| **Walk distance tracking** | Yes | Yes | **No** |

**Important:** CAR mode does NOT contribute to walk distance:

```java
// In StreetEdge.doTraverse()
if (!traverseMode.isInCar()) {
    s1.incrementWalkDistance(getDistanceWithElevation());
}
```

This is critical for filtering, as walk distance limits don't apply to car trips.

### 7.4 CAR Preferences

**Location:** `routing/api/request/preference/CarPreferences.java`

| Parameter | Default | Description |
|-----------|---------|-------------|
| `reluctance` | 2.0 | Overall cost multiplier for driving |
| `parkingTime` | configurable | Time to park the car |
| `parkingCost` | configurable | Cost penalty for parking |
| `pickupTime` | 1 minute | Time for pickup/drop-off |
| `pickupCost` | 2 minutes | Cost penalty for pickup/drop-off |
| `accelerationSpeed` | 2.9 m/s² | Car acceleration rate |
| `decelerationSpeed` | 2.9 m/s² | Car deceleration rate |

### 7.5 Park-and-Ride Flow

**Key Classes:**
- `VehicleParkingEdge` - Handles parking/unparking transitions
- `VehicleParkingEntranceVertex` - Parking facility entrance
- `CarPickupState` - Tracks journey phase for kiss-and-ride

**State Transitions:**

```
Forward Search (depart at time T):
    CAR (driving) ──► VehicleParkingEdge ──► WALK (to transit)
                          │
                          └── Adds parking time/cost
                          └── Changes TraverseMode to WALK

Reverse Search (arrive by time T):
    WALK (from destination) ──► VehicleParkingEdge ──► CAR (unpark)
                                      │
                                      └── Subtracts parking time
                                      └── Changes TraverseMode to CAR
```

### 7.6 Filtering: CAR vs Transit

The `ItineraryListFilterChain` treats car and transit very differently.

**Location:** `routing/algorithm/filterchain/`

#### Filter Execution Order

```
1. Group-By-Similar-Legs
2. Same Routes/Stops Deduplication
3. Transit Generalized Cost Filter (transit vs transit)
4. Non-Transit Generalized Cost Filter (street-only threshold)
5. RemoveTransitIfStreetOnlyIsBetter (CAR vs Transit)  ◄── Key filter
6. RemoveTransitIfWalkingIsBetter
7. RemoveParkAndRideWithMostlyWalkingFilter
8. Paging and Sorting
```

#### Key Filter: RemoveTransitIfStreetOnlyIsBetter

**Location:** `routing/algorithm/filterchain/filters/transit/RemoveTransitIfStreetOnlyIsBetter.java`

This filter compares the best street-only itinerary (CAR, BIKE, or WALK) against transit:

```java
// Pseudocode
bestStreetOnly = findBestStreetOnlyItinerary(itineraries);
limit = costFunction.calculate(bestStreetOnly.getGeneralizedCost());

for (itinerary : transitItineraries) {
    if (itinerary.getGeneralizedCost() >= limit) {
        remove(itinerary);  // Transit is not competitive
    }
}
```

**Important:** Uses `getGeneralizedCost()` WITHOUT penalties for fair comparison.

#### Transit vs Transit Filter

**Location:** `routing/algorithm/filterchain/filters/transit/TransitGeneralizedCostFilter.java`

Transit itineraries are compared pairwise:

```java
// Formula
limit = costFunction(reference.cost) + intervalRelaxFactor * timeDifference;
```

Uses `getGeneralizedCostIncludingPenalty()` WITH access/egress penalties.

#### Park-and-Ride Specific Filter

**Location:** `routing/algorithm/filterchain/filters/street/RemoveParkAndRideWithMostlyWalkingFilter.java`

Ensures the car portion is meaningful:

```java
// Uses DURATION ratio
ratio = carDuration / totalDuration;
if (ratio <= threshold) {  // e.g., 0.5
    remove(itinerary);  // Too much walking, not worth driving
}
```

### 7.7 Penalty System for Fair Comparison

Transit itineraries include **access/egress penalties** to make comparison fair with street-only:

**Location:** `routing/algorithm/raptoradapter/transit/cost/AccessEgressPenaltyDecorator.java`

| Mode | Why Penalty Needed |
|------|-------------------|
| Street-only (CAR) | No penalty (entire trip is one mode) |
| Transit + walk access | Penalty for walking portion |
| Transit + car access (P+R) | Penalty for car + parking portion |
| Transit + car pickup (K+R) | Penalty for pickup wait |

This prevents unfair advantage to driving-only trips.

### 7.8 Response Structure Comparison

| Itinerary Type | Legs Structure | Example |
|----------------|----------------|---------|
| **CAR only** | `[StreetLeg(CAR)]` | Drive A→B |
| **Transit only** | `[StreetLeg(WALK), TransitLeg, StreetLeg(WALK)]` | Walk→Bus→Walk |
| **Park-and-Ride** | `[StreetLeg(CAR), StreetLeg(WALK), TransitLeg, StreetLeg(WALK)]` | Drive→Park→Walk→Train→Walk |
| **Kiss-and-Ride** | `[StreetLeg(WALK), StreetLeg(CAR), TransitLeg, StreetLeg(WALK)]` | Walk→Taxi→Train→Walk |

### 7.9 Sorting Priority

**Location:** `routing/algorithm/filterchain/comparators/SortOrderComparator.java`

Street-only itineraries (including CAR) are sorted **before** transit in default sort orders:

```java
// STREET_AND_ARRIVAL_TIME (default for depart-after):
// 1. Street-only comes FIRST
// 2. Then sort by arrival time
// 3. Then by generalized cost
// 4. Then by number of transfers
```

### 7.10 Flow Diagram: CAR vs Transit

```
┌─────────────────────────────────────────────────────────────────┐
│                      RouteRequest                               │
│         (modes: [CAR, TRANSIT], from, to, time)                │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      RoutingWorker                              │
│                                                                 │
│  ┌─────────────────────┐           ┌─────────────────────┐     │
│  │  DirectStreetRouter │           │    TransitRouter    │     │
│  │                     │           │                     │     │
│  │  • A* Algorithm     │           │  • A* for access    │     │
│  │  • CAR speed from   │           │  • RAPTOR for       │     │
│  │    OSM edges        │           │    transit          │     │
│  │  • No walk distance │           │  • A* for egress    │     │
│  │    tracking         │           │                     │     │
│  └──────────┬──────────┘           └──────────┬──────────┘     │
│             │                                  │                 │
│             └────────────┬─────────────────────┘                │
│                          │                                      │
│                          ▼                                      │
│               Combined Itineraries                              │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│              ItineraryListFilterChain                           │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ RemoveTransitIfStreetOnlyIsBetter                       │   │
│  │                                                         │   │
│  │ IF car_cost < costFunction(transit_cost):               │   │
│  │     KEEP transit                                        │   │
│  │ ELSE:                                                   │   │
│  │     REMOVE transit (driving is better)                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Sort: Street-only FIRST, then by arrival time           │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 7.11 Key Classes for CAR Mode

| Class | Location | Responsibility |
|-------|----------|----------------|
| **StreetMode** | `routing/api/request/` | Defines CAR, CAR_TO_PARK, etc. |
| **TraverseMode** | `street/search/` | Internal mode (CAR, WALK, BICYCLE) |
| **CarPreferences** | `routing/api/request/preference/` | Car-specific settings |
| **StreetEdge** | `street/model/edge/` | Edge with car speed, permissions |
| **VehicleParkingEdge** | `street/model/edge/` | Park/unpark transitions |
| **CarPickupState** | `street/search/state/` | Kiss-and-ride state tracking |
| **DirectStreetRouter** | `routing/algorithm/raptoradapter/router/street/` | Street-only routing |
| **RemoveTransitIfStreetOnlyIsBetter** | `routing/algorithm/filterchain/filters/transit/` | CAR vs transit filter |

---

## 8. Response Generation

### 7.1 Path to Itinerary Mapping

After RAPTOR finds optimal paths, they must be converted to user-friendly itineraries:

```
RaptorPath (algorithm output)
    │
    ▼
RaptorPathToItineraryMapper
    │
    ├── For each transit leg:
    │   │
    │   ├── Look up TripPattern, Trip, TripTimes
    │   ├── Get stop names, coordinates
    │   ├── Calculate intermediate stops
    │   └── Create ScheduledTransitLeg
    │
    ├── For each access/egress/transfer:
    │   │
    │   ├── Get street path geometry
    │   ├── Generate turn-by-turn directions
    │   └── Create StreetLeg
    │
    └── Assemble Itinerary
            │
            ├── Calculate totals (duration, transfers)
            ├── Compute generalized cost
            └── Add fare information
```

### 7.2 Filtering and Pagination

`ItineraryListFilterChain` applies various filters:

| Filter | Purpose |
|--------|---------|
| **RemoveTransitIfStreetOnlyIsBetter** | Remove transit if walking is faster |
| **SameFirstOrLastTripFilter** | Deduplicate similar itineraries |
| **GroupByFilter** | Group by mode or agency |
| **SortByNumTransfers** | Order results |
| **PagingFilter** | Handle pagination cursors |

### 7.3 GraphQL Response Serialization

```
List<Itinerary>
    │
    ▼
GraphQL DataFetchers (ItineraryImpl, LegImpl, etc.)
    │
    ├── Resolve requested fields only
    ├── Apply field-level transformations
    │
    ▼
GraphQL Execution Result
    │
    ▼
JSON Serialization
    │
    ▼
HTTP Response
```

---

## 9. Key Classes Reference

### API Layer

| Class | Location | Responsibility |
|-------|----------|----------------|
| GtfsGraphQLAPI | `apis/gtfs/` | GTFS API HTTP endpoint |
| TransmodelAPI | `apis/transmodel/` | Transmodel API HTTP endpoint |
| TripRequestMapper | `apis/transmodel/mapping/` | GraphQL → RouteRequest |
| GraphQLRequestContext | `apis/gtfs/` | Service injection for resolvers |

### Routing Service

| Class | Location | Responsibility |
|-------|----------|----------------|
| DefaultRoutingService | `routing/service/` | Main routing entry point |
| RoutingWorker | `routing/algorithm/` | Orchestrates all routers |
| TransitRouter | `routing/algorithm/raptoradapter/router/` | Transit routing coordinator |
| DirectStreetRouter | `routing/algorithm/raptoradapter/router/street/` | Non-transit routing |

### Algorithms

| Class | Location | Responsibility |
|-------|----------|----------------|
| RaptorService | `raptor/` | RAPTOR service coordinator |
| RangeRaptor | `raptor/rangeraptor/` | Main RAPTOR implementation |
| AStar | `astar/` | A* shortest path algorithm |
| AccessEgressRouter | `routing/algorithm/raptoradapter/router/street/` | Find stops from location |

### Data Model

| Class | Location | Responsibility |
|-------|----------|----------------|
| Itinerary | `model/plan/` | Complete journey result |
| Leg | `model/plan/` | Journey segment |
| Route | `transit/model/network/` | Transit line |
| TripPattern | `transit/model/network/` | Trips with same stops |
| Trip | `transit/model/timetable/` | Single vehicle journey |
| RegularStop | `transit/model/site/` | Physical stop |
| Graph | `routing/graph/` | Street network |

### Transit Data (RAPTOR-optimized)

| Class | Location | Responsibility |
|-------|----------|----------------|
| RaptorTransitData | `routing/algorithm/raptoradapter/transit/` | Indexed transit data |
| TripPatternForDate | `routing/algorithm/raptoradapter/transit/` | Date-filtered patterns |
| RaptorTransferIndex | `routing/algorithm/raptoradapter/transit/` | Transfer connections |

---

## Summary

OpenTripPlanner's architecture efficiently handles multimodal trip planning through:

1. **Dual GraphQL APIs** providing flexible query interfaces
2. **Service layer** abstracting routing complexity
3. **Parallel routing** (street, flex, transit) for comprehensive results
4. **RAPTOR algorithm** for efficient transit routing with multi-criteria optimization
5. **A* algorithm** for street network navigation
6. **Separated networks** (street graph + transit model) for optimized data structures
7. **Robust filtering** and pagination for user-friendly results

The modular design allows for easy extension (e.g., adding new modes like DRT) while maintaining performance through specialized data structures and algorithms.
