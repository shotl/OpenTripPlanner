# DRT and Pareto Optimal Filtering in OpenTripPlanner

## What Is Pareto Optimality?

Pareto optimality (also called Pareto efficiency) is a concept from multi-objective optimization. A solution is **Pareto-optimal** if no other solution is better on **all** criteria simultaneously.

In other words, a solution is kept only if improving one criterion would require making another criterion worse. Solutions that are worse on every criterion compared to another solution are **dominated** and discarded.

### Visual Example

Imagine comparing two itineraries on just two criteria — arrival time and cost:

```
Cost ↑
     |
  B  |  ●              B is worse on BOTH axes → dominated → discarded
     |
  A  |        ●        A is Pareto-optimal (nothing beats it on both)
     |
  C  |              ●  C arrives later but cheaper → also Pareto-optimal
     |
     +------------------→ Arrival Time (later)
```

- **A** is kept: nothing is both earlier AND cheaper.
- **C** is kept: it's cheaper than A (even though it arrives later).
- **B** is discarded: A is both earlier AND cheaper.

## How RAPTOR Uses Pareto Optimality

OTP's RAPTOR algorithm uses **three criteria** for its multi-criteria (McRAPTOR) search:

| # | Criterion | Meaning | Preference |
|---|-----------|---------|------------|
| 1 | **Arrival time** | When you reach the destination | Earlier is better |
| 2 | **Pareto round** | Proxy for number of transfers | Fewer is better |
| 3 | **Generalized cost (c1)** | Weighted combination of time, distance, mode penalties, waiting | Lower is better |

An itinerary **survives** only if no other itinerary is equal or better on **all three** criteria. The comparison logic inside RAPTOR is:

```
left dominates right  ⟺  (left.arrivalTime < right.arrivalTime)
                       OR (left.paretoRound < right.paretoRound)
                       OR (left.c1 < right.c1)
```

If `left` does not dominate `right` on any single criterion, then `right` is kept. If `left` is better on all three, `right` is discarded.

## How This Affects DRT Egress

### The Setup

When the street mode is `DEMAND_RESPONSIVE_TRANSPORTATION`, OTP computes **two types of egress** from each transit stop to the destination:

1. **Walk egress** — walk from the transit stop to the destination.
2. **DRT (car) egress** — drive from a DRT-eligible stop to the destination.

Both egress sets are passed into RAPTOR, which then combines them with transit paths and applies Pareto filtering.

### Scenario: Good Transit Coverage (15:30)

At 15:30, frequent bus service operates. Buses drop passengers close to the destination:

| Itinerary | Last Transit Stop | Walk to Destination | Total Duration | Arrives |
|-----------|-------------------|---------------------|----------------|---------|
| BUS 61 | VIALE DELLE NAZIONI I B | **164s** (~200m) | 1588s | 16:05 |
| BUS 62 | VIA RIGHI / LARGO PERLAR | **97s** (~100m) | 1737s | 16:11 |

A hypothetical BUS + DRT egress path would look like:

| Itinerary | Last Transit Stop | DRT to Destination | Total Duration | Arrives |
|-----------|-------------------|--------------------|----------------|---------|
| BUS 22 + DRT | STAZIONE PORTA NUOVA | ~379s + penalties | ~1200s | ~15:50 |

**Pareto comparison:**

| Criterion | BUS 61 (walk) | BUS 22 + DRT |
|-----------|--------------|--------------|
| Arrival time | 16:05 | ~15:50 |
| Transfers | 0 | 0 |
| Generalized cost (c1) | **Low** (short walk) | **High** (DRT has car penalties + time penalty) |

The DRT option might arrive slightly earlier, but its **generalized cost is much higher** because:

1. **DRT car egress has a higher base cost** — driving costs more per second than walking in generalized cost.
2. **`AccessEgressPenaltyDecorator`** adds an additional time penalty and cost penalty to non-walk egress, specifically to prevent car modes from unfairly dominating.
3. **The walk egress is only 97-164 seconds** — extremely cheap.

The result is that the BUS 61 itinerary (walk egress) **dominates** the BUS + DRT itinerary on cost while being competitive on time. RAPTOR discards the DRT option.

Meanwhile, there are enough frequent bus options that at least one pure-transit itinerary matches or beats DRT on every criterion. **DRT gets Pareto-dominated and pruned.**

### Scenario: Poor Transit Coverage (21:30)

At 21:30, evening service is sparse. The transit-only options are significantly worse:

| Itinerary | Last Transit Stop | Walk to Destination | Total Duration | Arrives |
|-----------|-------------------|---------------------|----------------|---------|
| BUS 84 | VIA TEVERE CIRCOSCRIZIONE B | **1128s** (~1.4km walk!) | 2312s | 22:31 |
| BUS 82 | VIALE DEL LAVORO / LARGO PERLAR A | **266s** | 1969s | 22:41 |

Now, BUS 22 + DRT egress:

| Itinerary | Last Transit Stop | DRT to Destination | Total Duration | Arrives |
|-----------|-------------------|--------------------|----------------|---------|
| BUS 22 + DRT | STAZIONE PORTA NUOVA | 379s | 1203s | **21:53** |

**Pareto comparison:**

| Criterion | BUS 84 (walk) | BUS 82 (walk) | BUS 22 + DRT |
|-----------|--------------|--------------|--------------|
| Arrival time | 22:31 | 22:41 | **21:53** ✓ earliest |
| Transfers | 0 | 0 | 0 |
| Generalized cost | Medium (long walk) | Medium | Higher (DRT penalty) |

The DRT option arrives **38+ minutes earlier** than any transit-only alternative. Even with its higher generalized cost, no pure-transit itinerary can beat it on arrival time. DRT is **not dominated** → it **survives Pareto filtering** and is offered to the user.

## Why This Is Correct Behavior

DRT is a "last-mile" mode. It provides the most value when:

- Transit doesn't cover the destination area well.
- The remaining walk after transit is long.
- Service frequency is low (evenings, weekends).

When a bus stops 100 meters from the destination every 10 minutes, there is no rational reason to dispatch a DRT vehicle. The Pareto optimization ensures DRT surfaces only when it provides **genuine, non-dominated value**.

## The Role of `AccessEgressPenaltyDecorator`

Before RAPTOR runs, OTP applies penalties to non-walk access/egress via `AccessEgressPenaltyDecorator`. The penalties are defined per `StreetMode` and consist of two components:

### Penalty Formula

Each penalty has a **time penalty** and a **cost factor**:

```
timePenalty = constant + coefficient × duration
costPenalty = timePenalty × costFactor
```

Where:
- **`constant`**: a fixed minimum time added regardless of trip duration.
- **`coefficient`**: a multiplier on the actual access/egress duration (`t`).
- **`costFactor`**: converts the time penalty into a generalized cost penalty.

The notation `20m + 2.0 t` means: 20 minutes constant + 2.0 × the actual travel time.

### Current Default Penalties

These defaults are defined in `AccessEgressPreferences.createDefaultCarPenalty()`:

| Street Mode | Time Penalty | Cost Factor | Applies To |
|-------------|-------------|-------------|------------|
| **WALK** | *none (zero)* | 0.0 | Walk-only access/egress |
| **BIKE** | *none (zero)* | 0.0 | Bike-only access/egress |
| **CAR_TO_PARK** | `20m + 2.0 t` | 1.5 | Park & Ride |
| **CAR_PICKUP** | `20m + 2.0 t` | 1.5 | Kiss & Ride, taxi |
| **CAR_RENTAL** | `20m + 2.0 t` | 1.5 | Car sharing |
| **CAR_HAILING** | `20m + 2.0 t` | 1.5 | Uber, Lyft |
| **DEMAND_RESPONSIVE_TRANSPORTATION** | `20m + 2.0 t` | 1.5 | DRT (Shotl, etc.) |
| **FLEXIBLE** | `10m + 1.3 t` | 1.3 | Flex transit |

> **Note:** `WALK` and `BIKE` have **no penalty** (zero). All car-based modes share the same penalty. `FLEXIBLE` has a lighter penalty since it represents actual transit service rather than private car use.

### Concrete Example: DRT Egress Penalty

For a DRT egress leg of **5 minutes (300 seconds)** actual driving time:

```
timePenalty = 20min + 2.0 × 5min = 20 + 10 = 30 minutes (1800 seconds)
costPenalty = 1800 × 1.5 = 2700 seconds of generalized cost
```

This means RAPTOR sees the DRT egress as if it were **30 minutes longer** than it really is, and adds **2700 centi-seconds** (~45 minutes equivalent) to the generalized cost `c1`.

Compare this to a **2-minute walk** egress:

```
timePenalty = 0 (no penalty for WALK)
costPenalty = 0
```

The walk egress has zero penalty. For the DRT option to survive Pareto filtering, it must compensate for the 30-minute artificial time disadvantage by arriving much earlier than any walk-based alternative.

### Overriding Defaults

These penalties can be overridden per-request via the GraphQL API:

```graphql
{
  planConnection(
    accessEgressPenalty: [
      { streetMode: demand_responsive_transportation, timePenalty: "10m + 1.5 t", costFactor: 1.2 }
    ]
  ) { ... }
}
```

Or in the `router-config.json` to change the server-wide defaults.

## Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                    RAPTOR Pareto Filter                         │
│                                                                 │
│   Criteria: arrival_time × pareto_round × c1_cost              │
│                                                                 │
│   For each candidate itinerary:                                 │
│     IF another itinerary is ≤ on ALL criteria                   │
│       → DOMINATED → discard                                     │
│     ELSE                                                        │
│       → PARETO-OPTIMAL → keep                                   │
│                                                                 │
│   Effect on DRT:                                                │
│     • Good transit → short walks → low cost → DRT dominated     │
│     • Poor transit → long walks → DRT arrives much earlier      │
│       → DRT not dominated → offered to user                     │
└─────────────────────────────────────────────────────────────────┘
```

| Condition | DRT Egress | Reason |
|-----------|-----------|--------|
| Frequent transit, stops near destination | **Not offered** | Walk egress dominates DRT on cost |
| Sparse transit, long walks to destination | **Offered** | DRT dominates on arrival time |
| No transit at all | **Always offered** | No competition |
