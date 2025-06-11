package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.apis.gtfs.model.DRTGeoLocation;
import org.opentripplanner.apis.gtfs.model.DRTPassengers;
import org.opentripplanner.apis.gtfs.model.DRTScheduledGeoLocation;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;

public class DRTEstimateImpl implements GraphQLDataFetchers.GraphQLDrtEstimate {

  @Override
  public DataFetcher<String> id() {
    return env -> getSource(env).id();
  }

  @Override
  public DataFetcher<String> userId() {
    return env -> getSource(env).user_id();
  }

  @Override
  public DataFetcher<String> type() {
    return env -> getSource(env).type();
  }

  @Override
  public DataFetcher<String> status() {
    return env -> getSource(env).status();
  }

  @Override
  public DataFetcher<String> code() {
    return env -> getSource(env).code();
  }

  @Override
  public DataFetcher<DRTGeoLocation> desiredPickupLocation() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlGeoLocation location = getSource(
        env
      ).desired_pickup_location();
      return new DRTGeoLocation(location.latitude(), location.longitude());
    };
  }

  @Override
  public DataFetcher<DRTGeoLocation> desiredDropoffLocation() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlGeoLocation location = getSource(
        env
      ).desired_dropoff_location();
      return new DRTGeoLocation(location.latitude(), location.longitude());
    };
  }

  @Override
  public DataFetcher<DRTScheduledGeoLocation> scheduledPickupPlace() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation scheduledPlace = getSource(
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
  public DataFetcher<DRTScheduledGeoLocation> scheduledDropoffPlace() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation scheduledPlace = getSource(
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
    return env -> getSource(env).desired_pickup_time();
  }

  @Override
  public DataFetcher<Long> desiredDropoffTime() {
    return env -> getSource(env).desired_dropoff_time();
  }

  @Override
  public DataFetcher<Long> userExpectedPickupTime() {
    return env -> getSource(env).user_expected_pickup_time();
  }

  @Override
  public DataFetcher<Long> userExpectedDropoffTime() {
    return env -> getSource(env).user_expected_dropoff_time();
  }

  @Override
  public DataFetcher<Long> petitionTime() {
    return env -> getSource(env).petition_time();
  }

  @Override
  public DataFetcher<DRTPassengers> passengers() {
    return env -> {
      ShotlArrivalEstimateResponse.ShotlPassengers passengers = getSource(env).passengers();
      return new DRTPassengers(passengers.regular(), passengers.wheelchair());
    };
  }

  @Override
  public DataFetcher<String> vehicleId() {
    return env -> getSource(env).vehicle_id();
  }

  private ShotlArrivalEstimateResponse getSource(DataFetchingEnvironment environment) {
    return environment.getSource();
  }
}
