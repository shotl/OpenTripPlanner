package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.opentripplanner.apis.gtfs.GraphQLRequestContext;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.apis.gtfs.model.DRTGeoLocation;
import org.opentripplanner.apis.gtfs.model.DRTPassengers;
import org.opentripplanner.apis.gtfs.model.DRTScheduledGeoLocation;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;

public class DRTEstimateImpl implements GraphQLDataFetchers.GraphQLDrtEstimate {

  @Override
  public DataFetcher<String> id() {
    return env -> getEstimate(env).id();
  }

  @Override
  public DataFetcher<String> userId() {
    return env -> getEstimate(env).user_id();
  }

  @Override
  public DataFetcher<String> type() {
    return env -> getEstimate(env).type();
  }

  @Override
  public DataFetcher<String> status() {
    return env -> getEstimate(env).status();
  }

  @Override
  public DataFetcher<String> code() {
    return env -> getEstimate(env).code();
  }

  @Override
  public DataFetcher<Object> desiredPickupLocation() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlGeoLocation location = getEstimate(
        env
      ).desired_pickup_location();
      return new DRTGeoLocation(location.latitude(), location.longitude());
    };
  }

  @Override
  public DataFetcher<Object> desiredDropoffLocation() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlGeoLocation location = getEstimate(
        env
      ).desired_dropoff_location();
      return new DRTGeoLocation(location.latitude(), location.longitude());
    };
  }

  @Override
  public DataFetcher<Object> scheduledPickupPlace() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation scheduledPlace = getEstimate(
        env
      ).scheduled_pickup_place();
      if (scheduledPlace == null) {
        return null;
      }
      ShotlArrivalEstimateResponse.ShotlGeoLocation location = scheduledPlace.location();
      return new DRTScheduledGeoLocation(
        new DRTGeoLocation(location.latitude(), location.longitude()),
        scheduledPlace.name()
      );
    };
  }

  @Override
  public DataFetcher<Object> scheduledDropoffPlace() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation scheduledPlace = getEstimate(
        env
      ).scheduled_dropoff_place();
      if (scheduledPlace == null) {
        return null;
      }
      ShotlArrivalEstimateResponse.ShotlGeoLocation location = scheduledPlace.location();
      return new DRTScheduledGeoLocation(
        new DRTGeoLocation(location.latitude(), location.longitude()),
        scheduledPlace.name()
      );
    };
  }

  @Override
  public DataFetcher<Long> desiredPickupTime() {
    return env -> getEstimate(env).desired_pickup_time();
  }

  @Override
  public DataFetcher<Long> desiredDropoffTime() {
    return env -> getEstimate(env).desired_dropoff_time();
  }

  @Override
  public DataFetcher<Long> userExpectedPickupTime() {
    return env -> getEstimate(env).user_expected_pickup_time();
  }

  @Override
  public DataFetcher<Long> userExpectedDropoffTime() {
    return env -> getEstimate(env).user_expected_dropoff_time();
  }

  @Override
  public DataFetcher<OffsetDateTime> estimatedPickupTime() {
    return env -> {
      Long epochSeconds = getEstimate(env).user_expected_pickup_time();
      if (epochSeconds == null) {
        return null;
      }
      ZoneId zoneId = env.<GraphQLRequestContext>getContext().transitService().getTimeZone();
      return Instant.ofEpochSecond(epochSeconds).atZone(zoneId).toOffsetDateTime();
    };
  }

  @Override
  public DataFetcher<OffsetDateTime> estimatedDropoffTime() {
    return env -> {
      Long epochSeconds = getEstimate(env).user_expected_dropoff_time();
      if (epochSeconds == null) {
        return null;
      }
      ZoneId zoneId = env.<GraphQLRequestContext>getContext().transitService().getTimeZone();
      return Instant.ofEpochSecond(epochSeconds).atZone(zoneId).toOffsetDateTime();
    };
  }

  @Override
  public DataFetcher<Long> petitionTime() {
    return env -> getEstimate(env).petition_time();
  }

  @Override
  public DataFetcher<Object> passengers() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlPassengers passengers = getEstimate(env).passengers();
      return new DRTPassengers(passengers.regular(), passengers.wheelchair());
    };
  }

  @Override
  public DataFetcher<String> vehicleId() {
    return env -> getEstimate(env).vehicle_id();
  }

  @Override
  public DataFetcher<Integer> waitingSeconds() {
    return env -> (int) getSource(env).getWaitingSeconds();
  }

  @Override
  public DataFetcher<Integer> pickupWalkingSeconds() {
    return env -> {
      Long value = getEstimate(env).pickup_walking_seconds();
      return value != null ? value.intValue() : null;
    };
  }

  @Override
  public DataFetcher<Integer> dropoffWalkingSeconds() {
    return env -> {
      Long value = getEstimate(env).dropoff_walking_seconds();
      return value != null ? value.intValue() : null;
    };
  }

  private DRTLeg getSource(DataFetchingEnvironment environment) {
    return environment.getSource();
  }

  private ShotlArrivalEstimateResponse getEstimate(DataFetchingEnvironment environment) {
    return getSource(environment).rideEstimate();
  }
}
