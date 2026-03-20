package org.opentripplanner.apis.transmodel.model.plan;

import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;

public class DRTEstimateType {

  private static final GraphQLObjectType GEO_LOCATION_TYPE = GraphQLObjectType.newObject()
    .name("DRTGeoLocation")
    .description("Geographic location for a DRT ride.")
    .field(f ->
      f
        .name("latitude")
        .type(new GraphQLNonNull(Scalars.GraphQLFloat))
        .dataFetcher(env -> {
          var loc = (ShotlArrivalEstimateResponse.ShotlGeoLocation) env.getSource();
          return loc.latitude();
        })
    )
    .field(f ->
      f
        .name("longitude")
        .type(new GraphQLNonNull(Scalars.GraphQLFloat))
        .dataFetcher(env -> {
          var loc = (ShotlArrivalEstimateResponse.ShotlGeoLocation) env.getSource();
          return loc.longitude();
        })
    )
    .build();

  private static final GraphQLObjectType SCHEDULED_GEO_LOCATION_TYPE = GraphQLObjectType.newObject()
    .name("DRTScheduledGeoLocation")
    .description("Scheduled geographic location for a DRT ride, including a name.")
    .field(f ->
      f
        .name("location")
        .type(new GraphQLNonNull(GEO_LOCATION_TYPE))
        .dataFetcher(env -> {
          var loc = (ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation) env.getSource();
          return loc.location();
        })
    )
    .field(f ->
      f
        .name("name")
        .type(Scalars.GraphQLString)
        .dataFetcher(env -> {
          var loc = (ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation) env.getSource();
          return loc.name();
        })
    )
    .build();

  private static final GraphQLObjectType PASSENGERS_TYPE = GraphQLObjectType.newObject()
    .name("DRTPassengers")
    .description("Passenger details for a DRT ride.")
    .field(f ->
      f
        .name("regular")
        .type(new GraphQLNonNull(Scalars.GraphQLInt))
        .dataFetcher(env -> {
          var p = (ShotlArrivalEstimateResponse.ShotlPassengers) env.getSource();
          return p.regular();
        })
    )
    .field(f ->
      f
        .name("wheelchair")
        .type(new GraphQLNonNull(Scalars.GraphQLInt))
        .dataFetcher(env -> {
          var p = (ShotlArrivalEstimateResponse.ShotlPassengers) env.getSource();
          return p.wheelchair();
        })
    )
    .build();

  public static GraphQLObjectType create(GraphQLScalarType dateTimeScalar) {
    return GraphQLObjectType.newObject()
      .name("DRTEstimate")
      .description(
        "Response type for a DRT (Shotl) arrival estimate, containing details of a quoted ride."
      )
      .field(f ->
        f
          .name("id")
          .description("Unique identifier for the quoted ride.")
          .type(new GraphQLNonNull(Scalars.GraphQLString))
          .dataFetcher(env -> source(env).id())
      )
      .field(f ->
        f
          .name("code")
          .description("A code associated with the ride.")
          .type(new GraphQLNonNull(Scalars.GraphQLString))
          .dataFetcher(env -> source(env).code())
      )
      .field(f ->
        f
          .name("status")
          .description("Current status of the ride.")
          .type(new GraphQLNonNull(Scalars.GraphQLString))
          .dataFetcher(env -> source(env).status())
      )
      .field(f ->
        f
          .name("type")
          .description("Type of the ride.")
          .type(new GraphQLNonNull(Scalars.GraphQLString))
          .dataFetcher(env -> source(env).type())
      )
      .field(f ->
        f
          .name("userId")
          .description("Identifier for the user.")
          .type(new GraphQLNonNull(Scalars.GraphQLString))
          .dataFetcher(env -> source(env).user_id())
      )
      .field(f ->
        f
          .name("vehicleId")
          .description("Identifier for the vehicle assigned to the ride.")
          .type(Scalars.GraphQLString)
          .dataFetcher(env -> source(env).vehicle_id())
      )
      .field(f ->
        f
          .name("desiredPickupLocation")
          .description("Desired pickup location for the ride.")
          .type(new GraphQLNonNull(GEO_LOCATION_TYPE))
          .dataFetcher(env -> source(env).desired_pickup_location())
      )
      .field(f ->
        f
          .name("desiredDropoffLocation")
          .description("Desired dropoff location for the ride.")
          .type(new GraphQLNonNull(GEO_LOCATION_TYPE))
          .dataFetcher(env -> source(env).desired_dropoff_location())
      )
      .field(f ->
        f
          .name("scheduledPickupPlace")
          .description("Scheduled pickup place, including location and name.")
          .type(new GraphQLNonNull(SCHEDULED_GEO_LOCATION_TYPE))
          .dataFetcher(env -> source(env).scheduled_pickup_place())
      )
      .field(f ->
        f
          .name("scheduledDropoffPlace")
          .description("Scheduled dropoff place, including location and name.")
          .type(new GraphQLNonNull(SCHEDULED_GEO_LOCATION_TYPE))
          .dataFetcher(env -> source(env).scheduled_dropoff_place())
      )
      .field(f ->
        f
          .name("desiredPickupTime")
          .description("Desired pickup time as epoch seconds.")
          .type(new GraphQLNonNull(ExtendedScalars.GraphQLLong))
          .dataFetcher(env -> source(env).desired_pickup_time())
      )
      .field(f ->
        f
          .name("desiredDropoffTime")
          .description("Desired dropoff time as epoch seconds.")
          .type(ExtendedScalars.GraphQLLong)
          .dataFetcher(env -> source(env).desired_dropoff_time())
      )
      .field(f ->
        f
          .name("userExpectedPickupTime")
          .description("User expected pickup time as epoch seconds.")
          .type(new GraphQLNonNull(ExtendedScalars.GraphQLLong))
          .dataFetcher(env -> source(env).user_expected_pickup_time())
      )
      .field(f ->
        f
          .name("userExpectedDropoffTime")
          .description("User expected dropoff time as epoch seconds.")
          .type(new GraphQLNonNull(ExtendedScalars.GraphQLLong))
          .dataFetcher(env -> source(env).user_expected_dropoff_time())
      )
      .field(f ->
        f
          .name("estimatedPickupTime")
          .description("Estimated pickup time as an ISO-8601 datetime with timezone offset.")
          .type(dateTimeScalar)
          .dataFetcher(env -> {
            Long epochSeconds = source(env).user_expected_pickup_time();
            return epochSeconds != null ? epochSeconds * 1000 : null;
          })
      )
      .field(f ->
        f
          .name("estimatedDropoffTime")
          .description("Estimated dropoff time as an ISO-8601 datetime with timezone offset.")
          .type(dateTimeScalar)
          .dataFetcher(env -> {
            Long epochSeconds = source(env).user_expected_dropoff_time();
            return epochSeconds != null ? epochSeconds * 1000 : null;
          })
      )
      .field(f ->
        f
          .name("petitionTime")
          .description("Petition time for the ride as epoch seconds.")
          .type(new GraphQLNonNull(ExtendedScalars.GraphQLLong))
          .dataFetcher(env -> source(env).petition_time())
      )
      .field(f ->
        f
          .name("passengers")
          .description("Passenger details for the ride.")
          .type(new GraphQLNonNull(PASSENGERS_TYPE))
          .dataFetcher(env -> source(env).passengers())
      )
      .field(f ->
        f
          .name("waitingSeconds")
          .description("Seconds the user waits at the pickup point for the DRT vehicle to arrive.")
          .type(Scalars.GraphQLInt)
          .dataFetcher(env -> (int) drtLeg(env).getWaitingSeconds())
      )
      .build();
  }

  private static ShotlArrivalEstimateResponse source(graphql.schema.DataFetchingEnvironment env) {
    return drtLeg(env).rideEstimate();
  }

  private static DRTLeg drtLeg(graphql.schema.DataFetchingEnvironment env) {
    return env.getSource();
  }
}
