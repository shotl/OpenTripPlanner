# OpenTripPlanner planConnection GraphQL Query Example

This document provides a comprehensive example of the `planConnection` GraphQL query and its response structure for OpenTripPlanner.

## Overview

The `planConnection` query follows the [GraphQL Cursor Connections Specification](https://relay.dev/graphql/connections.htm) and is the recommended replacement for the deprecated `plan` query. It provides itinerary planning with cursor-based pagination support.

## Complete Query Example

```graphql
query PlanConnectionExample {
  planConnection(
    origin: {
      label: "Central Station"
      location: { 
        coordinate: { 
          latitude: 45.5552, 
          longitude: -122.6534 
        } 
      }
    }
    destination: {
      label: "Airport"
      location: { 
        coordinate: { 
          latitude: 45.4908, 
          longitude: -122.5519 
        } 
      }
    }
    
    dateTime: { 
      earliestDeparture: "2025-07-02T14:30:00-07:00" 
    }
    
    searchWindow: "PT2H"
    
    # Number of results to return (pagination)
    first: 3
    
    itineraryFilter: {
      groupSimilarityKeepOne: 0.85
      groupSimilarityKeepThree: 0.68
      groupedOtherThanSameLegsMaxCostMultiplier: 2.0
      itineraryFilterDebugProfile: OFF
    }
    
    # Transportation modes
    modes: {
      directOnly: false
      transitOnly: false
      direct: [WALK, BICYCLE]
      transit: {
        access: [WALK, BICYCLE_RENTAL]
        transfer: [WALK]
        egress: [WALK, BICYCLE_RENTAL]
        transit: [
          { mode: BUS, cost: { reluctance: 1.0 } }
          { mode: RAIL, cost: { reluctance: 0.8 } }
          { mode: TRAM, cost: { reluctance: 0.9 } }
        ]
      }
    }
    
    # DRT (Demand Responsive Transport) configuration
    drt: {
      areaId: "downtown_zone"
      userId: "user123"
      paxAppId: "transit_app"
      rideType: "SHARED"
      passengers: {
        regular: 1
        wheelchair: 0
      }
    }
    
    # Travel preferences
    preferences: {
      accessibility: { 
        wheelchair: { enabled: false } 
      }
      street: {
        walk: {
          speed: 1.33
          reluctance: 2.0
          boardCost: 1.0
          safetyFactor: 1.0
        }
      }
      transit: {
        alight: {
          slack: "PT0S"
        }
        board: {
          slack: "PT5M"
          waitReluctance: 1.0
        }
        transfer: {
          cost: 600
          slack: "PT2M"
          maximumTransfers: 2
          maximumAdditionalTransfers: 1
        }
        timetable: {
          excludeRealTimeUpdates: false
          includePlannedCancellations: false
          includeRealTimeCancellations: false
        }
      }
    }
  ) {    
    # Routing errors
    routingErrors {
      code
      description
      inputField
    }
    
    # Itinerary results
    edges {
      cursor
      node {
        start
        end
        generalizedCost
        numberOfTransfers
        duration
        walkTime
        waitingTime
        walkDistance    
        
        # System notices for debugging
        systemNotices {
          tag
          text
        }
        
        # Detailed leg information
        legs {
          # Basic leg info
          id
          mode
          duration
          distance
          transitLeg
          realTime
          realtimeState
          accessibilityScore
          generalizedCost
          
          # DRT estimate
          drtEstimate {
            id
            code
            status
            type
            desiredPickupTime
            desiredDropoffTime
            userExpectedPickupTime
            userExpectedDropoffTime
            scheduledPickupPlace {
              name
              location {
                lat
                lon
              }
            }
            scheduledDropoffPlace {
              name
              location {
                lat
                lon
              }
            }
          }
          
          # Start location
          from {
            name
            lat
            lon
            departure {
              scheduledTime
              estimated {
                time
                delay
              }
            }
          }
          
          # End location
          to {
            name
            lat
            lon
            arrival {
              scheduledTime
              estimated {
                time
                delay
              }
            }
          }
          
          # Route information (for transit legs)
          route {
            shortName
            longName
            mode
            color
            textColor
          }
        }
      }
    }
  }
}
```

## Expected Response Structure

```json
{
  "data": {
    "planConnection": {
      "routingErrors": [],
      "edges": [
        {
          "cursor": "eyJkZXBhcnR1cmVUaW1lIjoxNjg4MzA2NjAwLCJzZXF1ZW5jZSI6MH0=",
          "node": {
            "start": "2025-07-02T21:30:00Z",
            "end": "2025-07-02T22:45:00Z",
            "generalizedCost": 2847,
            "numberOfTransfers": 1,
            "duration": "PT1H15M",
            "walkTime": "PT12M",
            "waitingTime": "PT13M",
            "walkDistance": 890.5,
            "systemNotices": [
              {
                "tag": "ITINERARY_FILTER_DEBUG",
                "text": "This itinerary was considered during filtering but not removed"
              }
            ],
            "legs": [
              {
                "id": "leg-walk-1",
                "mode": "WALK",
                "duration": "PT7M",
                "distance": 520.3,
                "transitLeg": false,
                "realTime": false,
                "realtimeState": "SCHEDULED",
                "accessibilityScore": 1.0,
                "generalizedCost": 420,
                "drtEstimate": null,
                "from": {
                  "name": "Central Station",
                  "lat": 45.5552,
                  "lon": -122.6534,
                  "departure": {
                    "scheduledTime": "2025-07-02T21:30:00Z",
                    "estimated": null
                  }
                },
                "to": {
                  "name": "Central Station Platform A",
                  "lat": 45.5560,
                  "lon": -122.6540,
                  "arrival": {
                    "scheduledTime": "2025-07-02T21:37:00Z",
                    "estimated": null
                  }
                },
                "route": null
              },
              {
                "id": "leg-bus-1",
                "mode": "BUS",
                "duration": "PT25M",
                "distance": 8500.0,
                "transitLeg": true,
                "realTime": true,
                "realtimeState": "UPDATED",
                "accessibilityScore": 0.9,
                "generalizedCost": 1800,
                "drtEstimate": {
                  "id": "drt-quote-12345",
                  "code": "SHARED_RIDE_55",
                  "status": "CONFIRMED",
                  "type": "SHARED",
                  "desiredPickupTime": 1656806400,
                  "desiredDropoffTime": 1656807900,
                  "userExpectedPickupTime": 1656806400,
                  "userExpectedDropoffTime": 1656807900,
                  "scheduledPickupPlace": {
                    "name": "Central Station Platform A",
                    "location": {
                      "lat": 45.5560,
                      "lon": -122.6540
                    }
                  },
                  "scheduledDropoffPlace": {
                    "name": "Metro Junction",
                    "location": {
                      "lat": 45.5200,
                      "lon": -122.6100
                    }
                  }
                },
                "from": {
                  "name": "Central Station Platform A",
                  "lat": 45.5560,
                  "lon": -122.6540,
                  "departure": {
                    "scheduledTime": "2025-07-02T21:40:00Z",
                    "estimated": {
                      "time": "2025-07-02T21:42:00Z",
                      "delay": "PT2M"
                    }
                  }
                },
                "to": {
                  "name": "Metro Junction",
                  "lat": 45.5200,
                  "lon": -122.6100,
                  "arrival": {
                    "scheduledTime": "2025-07-02T22:05:00Z",
                    "estimated": {
                      "time": "2025-07-02T22:07:00Z",
                      "delay": "PT2M"
                    }
                  }
                },
                "route": {
                  "shortName": "55",
                  "longName": "Downtown Express",
                  "mode": "BUS",
                  "color": "FF6B35",
                  "textColor": "FFFFFF"
                }
              },
              {
                "id": "leg-walk-transfer",
                "mode": "WALK",
                "duration": "PT3M",
                "distance": 180.5,
                "transitLeg": false,
                "realTime": false,
                "realtimeState": "SCHEDULED",
                "accessibilityScore": 1.0,
                "generalizedCost": 145,
                "drtEstimate": null,
                "from": {
                  "name": "Metro Junction",
                  "lat": 45.5200,
                  "lon": -122.6100,
                  "departure": {
                    "scheduledTime": "2025-07-02T22:07:00Z",
                    "estimated": null
                  }
                },
                "to": {
                  "name": "Metro Junction Rail Platform",
                  "lat": 45.5195,
                  "lon": -122.6090,
                  "arrival": {
                    "scheduledTime": "2025-07-02T22:10:00Z",
                    "estimated": null
                  }
                },
                "route": null
              },
              {
                "id": "leg-rail-1",
                "mode": "RAIL",
                "duration": "PT25M",
                "distance": 12500.0,
                "transitLeg": true,
                "realTime": true,
                "realtimeState": "UPDATED",
                "accessibilityScore": 0.95,
                "generalizedCost": 1200,
                "drtEstimate": null,
                "from": {
                  "name": "Metro Junction Rail Platform",
                  "lat": 45.5195,
                  "lon": -122.6090,
                  "departure": {
                    "scheduledTime": "2025-07-02T22:15:00Z",
                    "estimated": {
                      "time": "2025-07-02T22:15:00Z",
                      "delay": "PT0S"
                    }
                  }
                },
                "to": {
                  "name": "Airport Terminal",
                  "lat": 45.4908,
                  "lon": -122.5519,
                  "arrival": {
                    "scheduledTime": "2025-07-02T22:40:00Z",
                    "estimated": {
                      "time": "2025-07-02T22:40:00Z",
                      "delay": "PT0S"
                    }
                  }
                },
                "route": {
                  "shortName": "BLUE",
                  "longName": "Blue Line",
                  "mode": "RAIL",
                  "color": "0073E6",
                  "textColor": "FFFFFF"
                }
              },
              {
                "id": "leg-walk-2",
                "mode": "WALK",
                "duration": "PT5M",
                "distance": 189.7,
                "transitLeg": false,
                "realTime": false,
                "realtimeState": "SCHEDULED",
                "accessibilityScore": 1.0,
                "generalizedCost": 150,
                "drtEstimate": null,
                "from": {
                  "name": "Airport Terminal",
                  "lat": 45.4908,
                  "lon": -122.5519,
                  "departure": {
                    "scheduledTime": "2025-07-02T22:40:00Z",
                    "estimated": null
                  }
                },
                "to": {
                  "name": "Airport",
                  "lat": 45.4908,
                  "lon": -122.5519,
                  "arrival": {
                    "scheduledTime": "2025-07-02T22:45:00Z",
                    "estimated": null
                  }
                },
                "route": null
              }
            ]
          }
        }
      ]
    }
  }
}
```

## Advanced Query Parameters

### Itinerary Filtering

The `itineraryFilter` parameter provides advanced control over how similar itineraries are grouped and filtered:

```graphql
query AdvancedFilteringExample {
  planConnection(
    origin: { location: { coordinate: { latitude: 45.5552, longitude: -122.6534 } } }
    destination: { location: { coordinate: { latitude: 45.4908, longitude: -122.5519 } } }
    dateTime: { earliestDeparture: "2025-07-02T14:30:00-07:00" }
    first: 5
    
    # Advanced filtering settings
    itineraryFilter: {
      # Keep only one itinerary from groups that are 85% similar
      groupSimilarityKeepOne: 0.85
      # Keep up to three itineraries from groups that are 68% similar  
      groupSimilarityKeepThree: 0.68
      # Allow non-grouped legs to be up to 2x more expensive
      groupedOtherThanSameLegsMaxCostMultiplier: 2.0
      # Enable debug output for filtering (OFF, LIMIT_TO_SEARCH_WINDOW, LIST_ALL)
      itineraryFilterDebugProfile: OFF
    }
  ) {
    edges {
      node {
        start
        end
        legs { mode }
      }
    }
  }
}
```

### Locale Support

Specify locale for translated content:

```graphql
query LocalizedExample {
  planConnection(
    origin: { location: { coordinate: { latitude: 45.5552, longitude: -122.6534 } } }
    destination: { location: { coordinate: { latitude: 45.4908, longitude: -122.5519 } } }
    dateTime: { earliestDeparture: "2025-07-02T14:30:00-07:00" }
    first: 3
    
    # Locale for translations (alternatively use Accept-Language header)
    locale: "fi-FI"
  ) {
    edges {
      node {
        legs {
          alerts {
            alertHeaderText  # Will be in Finnish if available
            alertDescriptionText
          }
        }
      }
    }
  }
}
```

### Using Stop Locations

Instead of coordinates, you can specify specific stops, stations, or stop groups:

```graphql
query StopLocationExample {
  planConnection(
    # Using a specific stop as origin
    origin: {
      label: "Central Station"
      location: {
        stopLocation: {
          stop: "HSL:1010102"
        }
      }
    }
    
    # Using coordinates for destination
    destination: {
      label: "Airport"
      location: {
        coordinate: {
          latitude: 45.4908
          longitude: -122.5519
        }
      }
    }
    
    dateTime: { earliestDeparture: "2025-07-02T14:30:00-07:00" }
    first: 3
  ) {
    edges {
      node {
        legs { mode from { name } to { name } }
      }
    }
  }
}
```

### Backward Pagination

Use `before` and `last` for backward pagination:

```graphql
query BackwardPaginationExample {
  planConnection(
    origin: { location: { coordinate: { latitude: 45.5552, longitude: -122.6534 } } }
    destination: { location: { coordinate: { latitude: 45.4908, longitude: -122.5519 } } }
    dateTime: { earliestDeparture: "2025-07-02T14:30:00-07:00" }
    
    # Backward pagination parameters
    last: 3
    before: "eyJkZXBhcnR1cmVUaW1lIjoxNjg4MzA2NjAwLCJzZXF1ZW5jZSI6MH0="
  ) {
    pageInfo {
      hasNextPage
      hasPreviousPage
      startCursor
      endCursor
    }
    edges {
      cursor
      node {
        start
        end
        legs { mode }
      }
    }
  }
}
```

## Key Features

1. **Cursor-based pagination**: Use `first`/`after` for forward pagination, `last`/`before` for backward pagination
2. **Flexible location input**: Supports coordinates, stop IDs, or stop location searches
3. **Comprehensive mode selection**: Define access, egress, transfer, and direct modes separately
4. **Rich preferences**: Control transit boarding/alighting, transfers, and real-time preferences
5. **DRT support**: Configure demand-responsive transport with passenger counts and service areas
6. **Real-time data**: Includes delays, alerts, and real-time vehicle positions
7. **Detailed fare information**: Multi-fare product support with pricing and rider categories
8. **Environmental data**: CO2 emissions for trip comparison
9. **Turn-by-turn directions**: Detailed step-by-step navigation for walking/cycling legs

## Transit Preferences Structure

The transit preferences are organized into categories:

- **alight**: Preferences for getting off transit vehicles
  - `slack`: Required minimum time for alighting (Duration)
  
- **board**: Preferences for boarding transit vehicles
  - `slack`: Required minimum waiting time at a stop (Duration)  
  - `waitReluctance`: Multiplier for how bad waiting at a stop is compared to being in transit (Reluctance)
  
- **transfer**: Preferences for transfers between vehicles
  - `cost`: Static cost added for each transfer (Cost)
  - `slack`: Global minimum transfer time between vehicles (Duration)
  - `maximumTransfers`: Maximum number of transfers allowed in an itinerary (Int)
  - `maximumAdditionalTransfers`: How many additional transfers compared to the least transfer itinerary (Int)
  
- **timetable**: Real-time and scheduling preferences
  - `excludeRealTimeUpdates`: When false, real-time updates are considered during routing (Boolean)
  - `includePlannedCancellations`: Include planned cancellations in routing (Boolean)
  - `includeRealTimeCancellations`: Include real-time cancellations in routing (Boolean)

This example demonstrates the full power of the `planConnection` query for comprehensive trip planning in OpenTripPlanner, including support for modern features like DRT and properly structured preferences.
