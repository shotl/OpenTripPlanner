# OTP GraphQL API - Trip Planning Parameter Guide

This document describes all configurable input parameters for the `planConnection` trip planning query in the OTP (OpenTripPlanner) GTFS GraphQL API, with special focus on DRT (Demand Responsive Transportation) integration.

---

## Table of Contents

1. [API Endpoint](#1-api-endpoint)
2. [`planConnection` Query Parameters](#2-planconnection-query-parameters)
3. [DRT Input Parameters](#3-drt-input-parameters)
4. [Mode Configuration](#4-mode-configuration)
5. [Preference Parameters](#5-preference-parameters)
6. [Itinerary Filters](#6-itinerary-filters)
7. [Parameter Incompatibilities](#7-parameter-incompatibilities)
8. [DRT Response Fields](#8-drt-response-fields)
9. [Example Queries](#9-example-queries)

---

## 1. API Endpoint

| API | Endpoint | Trip Query |
|-----|----------|------------|
| **GTFS** | `/otp/routers/default/index/graphql` | `planConnection` |

The API accepts a `drt: DRTInput` parameter and supports `DRT` as a street mode.

---

## 2. `planConnection` Query Parameters

This query follows the GraphQL Cursor Connections Specification.

### Top-Level Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `origin` | `PlanLabeledLocationInput!` | **Yes** | - | Where the search starts. Coordinates or stop location. |
| `destination` | `PlanLabeledLocationInput!` | **Yes** | - | Where the search ends. Coordinates or stop location. |
| `dateTime` | `PlanDateTimeInput` | No | Now (earliest departure) | When to depart or arrive. See [DateTime Input](#datetime-input). |
| `searchWindow` | `Duration` | No | Auto-calculated | Duration of the search window (e.g. `"PT2H"` for 2 hours). |
| `first` | `Int` | No | Server default | Max itineraries for forward pagination. |
| `last` | `Int` | No | - | Max itineraries for backward pagination. |
| `after` | `String` | No | - | Cursor for forward pagination. |
| `before` | `String` | No | - | Cursor for backward pagination. |
| `modes` | `PlanModesInput` | No | WALK + all transit | Street and transit mode configuration. |
| `preferences` | `PlanPreferencesInput` | No | Server defaults | Routing preferences (walk/bike/car/transit). |
| `itineraryFilter` | `PlanItineraryFilterInput` | No | Server defaults | Advanced itinerary filtering settings. |
| `locale` | `Locale` | No | - | Language for returned text (ISO code, e.g. `"en"`). |
| `drt` | `DRTInput` | No | - | DRT service configuration. **Required when using DRT mode.** |
| `via` | `[PlanViaLocationInput!]` | No | - | Intermediate locations to visit in order. |

### DateTime Input

`PlanDateTimeInput` is a `@oneOf` type - you must provide **exactly one** of:

| Field | Type | Description |
|-------|------|-------------|
| `earliestDeparture` | `OffsetDateTime` | Itineraries should not depart before this time. |
| `latestArrival` | `OffsetDateTime` | Itineraries should not arrive after this time. |

**Format:** ISO 8601 with timezone offset, e.g. `"2025-03-15T09:00:00+02:00"`

**Incompatibility:** You cannot set both `earliestDeparture` and `latestArrival` simultaneously.

### Location Input

`PlanLabeledLocationInput` structure:

```graphql
{
  label: "Home"           # Optional display label
  location: {
    coordinate: {         # Option A: Use coordinates
      latitude: 41.3851
      longitude: 2.1734
    }
    # OR
    stopLocation: {       # Option B: Use stop/station ID
      stopLocationId: "FeedId:StopId"
    }
  }
}
```

**Incompatibility:** `coordinate` and `stopLocation` are `@oneOf` - use exactly one.

---

## 3. DRT Input Parameters

```graphql
input DRTInput {
  areaId: String
  passengers: DRTPassengersInput
  paxAppId: String
  rideType: String
  userId: String
}

input DRTPassengersInput {
  regular: Int
  wheelchair: Int
}
```

### Field Details

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `paxAppId` | `String` | **Yes** (for Shotl) | The passenger application identifier. Sent as the `Shotl-Passenger-App-Id` HTTP header to the DRT backend. This authenticates the calling application. |
| `userId` | `String` | **Yes** (for Shotl) | User identifier within the DRT system. Used to track ride requests per user. |
| `areaId` | `String` | **Yes** (for Shotl) | The DRT service area identifier. Determines which geographic zone the DRT service operates in. The DRT provider must have this area configured. |
| `rideType` | `String` | **Yes** (for Shotl) | The type of ride being requested. This is a string defined by the DRT provider (e.g., a specific service type or category). |
| `passengers` | `DRTPassengersInput` | No | Passenger counts. Defaults depend on implementation. |
| `passengers.regular` | `Int` | No | Number of regular (non-wheelchair) passengers. |
| `passengers.wheelchair` | `Int` | No | Number of wheelchair passengers. Affects vehicle assignment. |

### How DRT Parameters Are Used

1. **During routing (access shifting):** The `areaId`, `userId`, `rideType`, `paxAppId`, and passenger info are sent to the DRT backend API to get time estimates for pickup/dropoff. These estimates adjust the access/egress times in the routing algorithm.

2. **During itinerary decoration:** After routes are found, the DRT service is called again to attach detailed ride estimates (scheduled pickup/dropoff locations, expected times, vehicle assignment) to the DRT legs in the response.

3. **Caching:** DRT requests are cached with coordinates rounded to ~10m and pickup times rounded to 5-minute intervals to reduce API calls.

---

## 4. Mode Configuration

### Modes Input (`PlanModesInput`)

```graphql
modes: {
  directOnly: false     # Only search without transit
  transitOnly: false    # Only search with transit
  direct: [WALK]        # Direct (no-transit) modes
  transit: {
    access: [WALK]      # How to reach transit
    egress: [WALK]      # How to leave transit
    transfer: [WALK]    # How to transfer between transit
    transit: [           # Which transit modes to use
      { mode: BUS }
      { mode: RAIL, cost: { reluctance: 0.9 } }
    ]
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `directOnly` | `Boolean` | `false` | Only search for non-transit itineraries. |
| `transitOnly` | `Boolean` | `false` | Only search for itineraries that include transit. |
| `direct` | `[PlanDirectMode!]` | - | Street modes for non-transit itineraries. |
| `transit` | `PlanTransitModesInput` | - | Configuration for transit-inclusive itineraries. |
| `transit.access` | `[PlanAccessMode!]` | - | How to get from origin to transit network. |
| `transit.egress` | `[PlanEgressMode!]` | - | How to get from transit network to destination. |
| `transit.transfer` | `[PlanTransferMode!]` | - | How to move between transit vehicles. |
| `transit.transit` | `[PlanTransitModePreferenceInput!]` | All | Which transit modes to use, with optional cost. |

### `PlanAccessMode` (Access to transit from origin)

| Value | Description |
|-------|-------------|
| `WALK` | Walk to stop. |
| `BICYCLE` | Cycle to stop (keep bike on transit). Requires `BICYCLE` for transfer and egress too. |
| `BICYCLE_PARKING` | Cycle to bike parking, then walk to stop. |
| `BICYCLE_RENTAL` | Walk to rental, cycle to stop area, walk to stop. |
| `CAR` | Drive to stop (keep car). Requires `CAR` for transfer and egress too. |
| `CAR_PARKING` | Drive to car park, then walk to stop. |
| `CAR_DROP_OFF` | Get dropped off near a stop. |
| `CAR_RENTAL` | Walk to rental car, drive to stop area. |
| `DRT` | Use DRT service to reach transit. **Requires `drt` input.** |
| `FLEX` | Use GTFS-Flex flexible transit. |
| `SCOOTER_RENTAL` | Walk to rental scooter, ride to stop area. |

### `PlanEgressMode` (From transit to destination)

| Value | Description |
|-------|-------------|
| `WALK` | Walk from stop. |
| `BICYCLE` | Cycle from stop. Requires `BICYCLE` for access and transfer too. |
| `BICYCLE_RENTAL` | Rent bicycle near stop, cycle to destination. |
| `CAR` | Drive from stop. Requires `CAR` for access and transfer too. |
| `CAR_PICKUP` | Get picked up near a stop (kiss & ride). |
| `CAR_RENTAL` | Rent car near stop, drive to destination. |
| `DRT` | Use DRT service from transit to destination. **Requires `drt` input.** |
| `FLEX` | Use GTFS-Flex flexible transit. |
| `SCOOTER_RENTAL` | Rent scooter near stop, ride to destination. |

### `PlanDirectMode` (No transit - origin to destination)

| Value | Description |
|-------|-------------|
| `WALK` | Walk the entire way. |
| `BICYCLE` | Cycle the entire way. |
| `BICYCLE_PARKING` | Cycle + park. |
| `BICYCLE_RENTAL` | Rent bicycle for the trip. |
| `CAR` | Drive the entire way. |
| `CAR_PARKING` | Drive + park. |
| `CAR_RENTAL` | Rent a car. |
| `DRT` | Use DRT for the entire trip. **Requires `drt` input.** |
| `FLEX` | Use flexible transit. |
| `SCOOTER_RENTAL` | Rent a scooter. |

### `PlanTransferMode` (Between transit vehicles)

| Value | Description |
|-------|-------------|
| `WALK` | Walk between stops. |
| `BICYCLE` | Cycle between stops. Requires `BICYCLE` for access and egress too. |
| `CAR` | Drive between stops. Requires `CAR` for access and egress too. |

### `TransitMode` (Transit vehicle types)

| Value | Description |
|-------|-------------|
| `AIRPLANE` | Air travel |
| `BUS` | Bus |
| `CABLE_CAR` | Cable car |
| `CARPOOL` | Shared private car trips |
| `COACH` | Long-distance coach |
| `FERRY` | Ferry/water transport |
| `FUNICULAR` | Funicular railway |
| `GONDOLA` | Gondola lift |
| `MONORAIL` | Monorail |
| `RAIL` | Long or short distance trains |
| `SUBWAY` | Metro/subway |
| `TAXI` | Taxi (public transport operated) |
| `TRAM` | Tram/streetcar |
| `TROLLEYBUS` | Electric trolleybus |

Each transit mode can have a `cost` with a `reluctance` multiplier to make it more or less preferable relative to other modes.

---

## 5. Preference Parameters

### Structure (`PlanPreferencesInput`)

```graphql
preferences: {
  accessibility: { wheelchair: { enabled: true } }
  street: {
    walk: { ... }
    bicycle: { ... }
    car: { ... }
    scooter: { ... }
  }
  transit: {
    board: { ... }
    alight: { ... }
    transfer: { ... }
    timetable: { ... }
  }
}
```

### Street Preferences (`preferences.street`)

**Walk** (`preferences.street.walk`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `speed` | `Speed` | 1.33 m/s | Max walk speed on flat ground. |
| `reluctance` | `Reluctance` | 2.0 | How bad walking is vs. transit. Higher = less walking. |
| `boardCost` | `Cost` | 600s | Cost of boarding transit while walking. |
| `safetyFactor` | `Ratio` | 1.0 | How much walk safety matters (0-1). 0 = ignore safety. |

**Bicycle** (`preferences.street.bicycle`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `speed` | `Speed` | 5.0 m/s | Max cycling speed on flat ground. |
| `reluctance` | `Reluctance` | 2.0 | How bad cycling is vs. transit. |
| `boardCost` | `Cost` | 600s | Cost of boarding transit with bicycle. |
| `optimization` | `CyclingOptimizationInput` | - | Route optimization criteria (see below). |
| `parking` | `BicycleParkingPreferencesInput` | - | Parking facility filters/preferences. |
| `rental` | `BicycleRentalPreferencesInput` | - | Rental network filters. |
| `walk` | `BicycleWalkPreferencesInput` | - | Preferences for walking the bicycle. |

**Cycling Optimization** (`CyclingOptimizationInput`, `@oneOf` - pick exactly one):

Option A - Predefined type (`type`):

| Value | Description |
|-------|-------------|
| `SHORTEST_DURATION` | Fastest route. |
| `SAFE_STREETS` | Prefer safe cycling streets. |
| `FLAT_STREETS` | Prefer flat terrain. |
| `SAFEST_STREETS` | Maximum safety ignoring elevation. |

Option B - Triangle factors (`triangle`, all three must sum to 1.0):

| Factor | Description |
|--------|-------------|
| `time` | Importance of duration. |
| `safety` | Importance of safety. |
| `flatness` | Importance of flat terrain. |

**Bicycle Rental** (`preferences.street.bicycle.rental`):

| Field | Type | Description |
|-------|------|-------------|
| `allowedNetworks` | `[String!]` | Networks that can be used. If empty, all allowed. |
| `bannedNetworks` | `[String!]` | Networks that cannot be used. |
| `destinationBicyclePolicy.allowKeeping` | `Boolean` | Allow arriving at destination without returning bicycle. |
| `destinationBicyclePolicy.keepingCost` | `Cost` | Extra cost for keeping the bicycle at destination. |

**Bicycle Walking** (`preferences.street.bicycle.walk`):

| Field | Type | Description |
|-------|------|-------------|
| `cost.mountDismountCost` | `Cost` | Cost of getting on/off bicycle to walk it. |
| `cost.reluctance` | `Reluctance` | How bad walking the bicycle is. |
| `mountDismountTime` | `Duration` | Time to hop on/off bicycle. |
| `speed` | `Speed` | Walk speed when walking the bicycle. |

**Car** (`preferences.street.car`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `reluctance` | `Reluctance` | 3.0 | How bad driving is vs. transit. |
| `boardCost` | `Cost` | - | Cost of boarding transit with car. |
| `parking` | `CarParkingPreferencesInput` | - | Parking facility filters. |
| `rental` | `CarRentalPreferencesInput` | - | Rental network filters. |

**Car Rental** (`preferences.street.car.rental`):

| Field | Type | Description |
|-------|------|-------------|
| `allowedNetworks` | `[String!]` | Networks that can be used. |
| `bannedNetworks` | `[String!]` | Networks that cannot be used. |

**Parking Preferences** (both `bicycle.parking` and `car.parking`):

| Field | Type | Description |
|-------|------|-------------|
| `filters` | `[ParkingFilter!]` | Include/exclude parking facilities by tags. |
| `preferred` | `[ParkingFilter!]` | Preferred parking facilities. Non-matching get extra cost. |
| `unpreferredCost` | `Cost` | Extra cost for non-preferred facilities. |

Each `ParkingFilter` has `select` (include) and `not` (exclude) lists of `ParkingFilterOperation` with `tags: [String]`.

**Scooter** (`preferences.street.scooter`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `speed` | `Speed` | - | Max scooter speed. |
| `reluctance` | `Reluctance` | - | How bad scootering is vs. transit. |
| `optimization` | `ScooterOptimizationInput` | - | Route optimization (same structure as cycling). |
| `rental` | `ScooterRentalPreferencesInput` | - | Rental network filters (`allowedNetworks`, `bannedNetworks`, `destinationScooterPolicy`). |

### Transit Preferences (`preferences.transit`)

**Board** (`preferences.transit.board`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `slack` | `Duration` | 0s | Min extra time before boarding. |
| `waitReluctance` | `Reluctance` | 1.0 | How bad waiting at stop is vs. transit. |

**Alight** (`preferences.transit.alight`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `slack` | `Duration` | 0s | Min extra time after alighting. |

**Transfer** (`preferences.transit.transfer`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `cost` | `Cost` | - | Static cost per transfer. |
| `maximumTransfers` | `Int` | - | Hard cap on transfer count. |
| `maximumAdditionalTransfers` | `Int` | - | Max extra transfers vs. optimal. |
| `slack` | `Duration` | - | Global min transfer time. Setting `PT0S` risks missed connections. |

**Timetable** (`preferences.transit.timetable`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `excludeRealTimeUpdates` | `Boolean` | false | Ignore real-time data. |
| `includePlannedCancellations` | `Boolean` | false | Include pre-planned cancellations. |
| `includeRealTimeCancellations` | `Boolean` | false | Include real-time cancellations. |

### Accessibility (`preferences.accessibility`)

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `wheelchair.enabled` | `Boolean` | false | Require wheelchair-accessible routes. |

---

## 6. Itinerary Filters

### `PlanItineraryFilterInput`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `groupSimilarityKeepOne` | `Ratio` | `0.85` | Keep 1 itinerary per group of similar itineraries. Similarity threshold (min 0.5). |
| `groupSimilarityKeepThree` | `Ratio` | `0.68` | Keep up to 3 itineraries per group. Similarity threshold (min 0.5). |
| `groupedOtherThanSameLegsMaxCostMultiplier` | `Float` | `2.0` | Max cost multiplier for non-grouped legs vs. cheapest. Use <1.0 to disable grouping. |
| `itineraryFilterDebugProfile` | `ItineraryFilterDebugProfile` | `OFF` | Debug mode for filters. |

**`ItineraryFilterDebugProfile` values:**

| Value | Description |
|-------|-------------|
| `OFF` | Normal filtering (deleted itineraries not returned). |
| `LIST_ALL` | Return all itineraries including deleted ones. |
| `LIMIT_TO_SEARCH_WINDOW` | Return all within actual search window. |
| `LIMIT_TO_NUMBER_OF_ITINERARIES` | Return top N including deleted. No paging support. |

---

## 7. Parameter Incompatibilities

### Critical Incompatibilities

| Constraint | Details |
|------------|---------|
| **DRT mode requires `drt` input** | If you use `DRT` as an access, egress, or direct mode, you **must** provide the `drt` parameter with at least `paxAppId`, `userId`, `areaId`, and `rideType`. Without these, the DRT backend call will fail. |
| **`earliestDeparture` vs `latestArrival`** | These are mutually exclusive (`@oneOf`). Cannot set both. |
| **`coordinate` vs `stopLocation`** | Location input is `@oneOf`. Use exactly one for origin and destination. |
| **`directOnly` vs `transitOnly`** | Cannot both be `true`. At most one can be `true`. |
| **`BICYCLE` access/egress/transfer** | If `BICYCLE` is used for access, it **must** also be used for egress and transfer. You cannot mix `BICYCLE` with `WALK` across access/egress/transfer. |
| **`CAR` access/egress/transfer** | Same constraint as bicycle: if `CAR` is used for access, it must be used for egress and transfer too. |
| **Cycling `optimization` is `@oneOf`** | You must pick either `type` (predefined) or `triangle` (custom factors), not both. |
| **Triangle factors must sum to 1.0** | When using triangle optimization, `time + safety + flatness` must equal exactly `1.0`. |
| **`includeRealTimeCancellations` requires real-time** | Cannot be `true` if `excludeRealTimeUpdates` is `true`. |

### Mode Combination Rules

| Rule | Details |
|------|---------|
| **Max 2 modes per leg** | For access, egress, or direct, you can specify at most 2 modes. |
| **Single mode alone** | If only 1 mode is specified, it must be `WALK`, `BICYCLE`, `CAR`, a parking mode, or `DRT`. Other modes (rentals, flex) need `WALK` as companion. |
| **Two modes** | If 2 modes are specified, `WALK` must be one of them. `BICYCLE` and `CAR` cannot be combined with other modes in the same leg. |
| **Rental modes need WALK** | `BICYCLE_RENTAL`, `CAR_RENTAL`, `SCOOTER_RENTAL`, and `FLEX` should be paired with `WALK` when specified as a list. |
| **DRT is self-sufficient** | `DRT` can be specified alone (without `WALK`) as it inherently includes walking segments. |

### Mode Availability by Leg Phase

| Mode | Access | Egress | Direct | Transfer |
|------|--------|--------|--------|----------|
| `WALK` | Yes | Yes | Yes | Yes |
| `BICYCLE` | Yes* | Yes* | Yes | Yes* |
| `BICYCLE_PARKING` | Yes | No | Yes | No |
| `BICYCLE_RENTAL` | Yes | Yes | Yes | No |
| `CAR` | Yes* | Yes* | Yes | Yes* |
| `CAR_PARKING` | Yes | No | Yes | No |
| `CAR_DROP_OFF` | Yes | No | No | No |
| `CAR_PICKUP` | No | Yes | No | No |
| `CAR_RENTAL` | Yes | Yes | Yes | No |
| `DRT` | Yes | Yes | Yes | No |
| `FLEX` | Yes | Yes | Yes | No |
| `SCOOTER_RENTAL` | Yes | Yes | Yes | No |

*\* Must be used consistently across access, egress, AND transfer.*

### DRT-Specific Incompatibilities

| Constraint | Details |
|------------|---------|
| **DRT not available for transfers** | DRT cannot be used as a transfer mode between transit legs. |
| **DRT + `BICYCLE` access** | DRT and `BICYCLE` are incompatible in the same request since `BICYCLE` requires all legs to use bicycle. |
| **DRT + `CAR` access** | Same as above - `CAR` mode requires all legs use car. |
| **DRT as access + non-DRT egress** | This is valid. You can use DRT only for access to transit, with WALK or any other valid mode for egress. |
| **DRT as both access and egress** | Valid. DRT can be used for both first-mile and last-mile. |
| **DRT as direct only** | Valid. DRT can be the sole mode for a door-to-door trip without transit. |

---

## 8. DRT Response Fields

When DRT is used, legs may include a `drtEstimate` field with the following structure:

### `DRTEstimate`

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String!` | Unique identifier for the quoted ride. |
| `code` | `String!` | A code associated with the ride. |
| `status` | `String!` | Current status of the ride (e.g. "quoted"). |
| `type` | `String!` | Type of the ride. |
| `userId` | `String!` | User identifier. |
| `vehicleId` | `String` | Assigned vehicle ID (may be null if not yet assigned). |
| `desiredPickupLocation` | `ShotlGeoLocation!` | Requested pickup lat/lng. |
| `desiredDropoffLocation` | `ShotlGeoLocation!` | Requested dropoff lat/lng. |
| `desiredPickupTime` | `Long!` | Requested pickup time (epoch seconds). |
| `desiredDropoffTime` | `Long` | Requested dropoff time (epoch seconds, nullable). |
| `scheduledPickupPlace` | `ShotlScheduledGeoLocation!` | Actual scheduled pickup (location + name). |
| `scheduledDropoffPlace` | `ShotlScheduledGeoLocation!` | Actual scheduled dropoff (location + name). |
| `userExpectedPickupTime` | `Long!` | When the user should expect pickup (epoch seconds). |
| `userExpectedDropoffTime` | `Long!` | When the user should expect dropoff (epoch seconds). |
| `petitionTime` | `Long!` | When the ride was requested (epoch seconds). |
| `passengers` | `ShotlPassengers!` | Passenger counts (`regular: Int!`, `wheelchair: Int!`). |

### Sub-Types

**`ShotlGeoLocation`:**

| Field | Type |
|-------|------|
| `latitude` | `Float!` |
| `longitude` | `Float!` |

**`ShotlScheduledGeoLocation`:**

| Field | Type |
|-------|------|
| `location` | `ShotlGeoLocation!` |
| `name` | `String` |

---

## 9. Example Queries

### DRT as Access Mode

Use DRT to get from origin to transit, then walk from transit to destination.

```graphql
{
  planConnection(
    origin: {
      label: "Home"
      location: { coordinate: { latitude: 41.3851, longitude: 2.1734 } }
    }
    destination: {
      label: "Work"
      location: { coordinate: { latitude: 41.4036, longitude: 2.1744 } }
    }
    dateTime: { earliestDeparture: "2025-03-15T09:00:00+02:00" }
    modes: {
      transit: {
        access: [DRT]
        egress: [WALK]
        transit: [{ mode: BUS }, { mode: RAIL }]
      }
    }
    drt: {
      paxAppId: "my-app-id"
      userId: "user-123"
      areaId: "area-456"
      rideType: "standard"
      passengers: { regular: 1, wheelchair: 0 }
    }
    first: 5
  ) {
    edges {
      node {
        legs {
          mode
          start { estimated { time } }
          end { estimated { time } }
          from { name lat lon }
          to { name lat lon }
          drtEstimate {
            id
            status
            userExpectedPickupTime
            userExpectedDropoffTime
            scheduledPickupPlace { location { latitude longitude } name }
            scheduledDropoffPlace { location { latitude longitude } name }
          }
        }
      }
    }
  }
}
```

### DRT as Direct Mode (Door-to-Door)

Use DRT for the entire journey without transit.

```graphql
{
  planConnection(
    origin: {
      location: { coordinate: { latitude: 41.3851, longitude: 2.1734 } }
    }
    destination: {
      location: { coordinate: { latitude: 41.4036, longitude: 2.1744 } }
    }
    modes: {
      directOnly: true
      direct: [DRT]
    }
    drt: {
      paxAppId: "my-app-id"
      userId: "user-123"
      areaId: "area-456"
      rideType: "standard"
      passengers: { regular: 2, wheelchair: 0 }
    }
  ) {
    edges {
      node {
        legs {
          mode
          drtEstimate {
            id
            userExpectedPickupTime
            userExpectedDropoffTime
            scheduledPickupPlace { location { latitude longitude } name }
            scheduledDropoffPlace { location { latitude longitude } name }
          }
        }
      }
    }
  }
}
```

### DRT as Both Access and Egress

Use DRT for first-mile and last-mile, with transit in between.

```graphql
{
  planConnection(
    origin: {
      location: { coordinate: { latitude: 41.3851, longitude: 2.1734 } }
    }
    destination: {
      location: { coordinate: { latitude: 41.4200, longitude: 2.2000 } }
    }
    dateTime: { earliestDeparture: "2025-03-15T09:00:00+02:00" }
    modes: {
      transit: {
        access: [DRT]
        egress: [DRT]
        transit: [{ mode: BUS }, { mode: RAIL }]
      }
    }
    drt: {
      paxAppId: "my-app-id"
      userId: "user-123"
      areaId: "area-456"
      rideType: "standard"
      passengers: { regular: 1, wheelchair: 0 }
    }
    first: 5
  ) {
    edges {
      node {
        legs {
          mode
          start { estimated { time } }
          end { estimated { time } }
          drtEstimate {
            id
            status
            userExpectedPickupTime
            userExpectedDropoffTime
            scheduledPickupPlace { location { latitude longitude } name }
            scheduledDropoffPlace { location { latitude longitude } name }
            passengers { regular wheelchair }
            vehicleId
          }
        }
      }
    }
  }
}
```

### Walk + Transit (No DRT, for comparison)

Standard transit query without DRT.

```graphql
{
  planConnection(
    origin: {
      location: { coordinate: { latitude: 41.3851, longitude: 2.1734 } }
    }
    destination: {
      location: { coordinate: { latitude: 41.4036, longitude: 2.1744 } }
    }
    dateTime: { earliestDeparture: "2025-03-15T09:00:00+02:00" }
    modes: {
      transit: {
        access: [WALK]
        egress: [WALK]
      }
    }
    preferences: {
      street: {
        walk: { speed: 1.2, reluctance: 2.5 }
      }
      transit: {
        transfer: { maximumTransfers: 2, cost: 600 }
      }
    }
    first: 5
  ) {
    edges {
      node {
        legs {
          mode
          start { estimated { time } }
          end { estimated { time } }
          from { name }
          to { name }
        }
      }
    }
  }
}
```
