package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

import static jakarta.ws.rs.core.HttpHeaders.ACCEPT_LANGUAGE;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static java.util.Map.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.opentripplanner.ext.demandresponsivetransportation.CachingDemandResponsiveTransportationService;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationServiceParameters;
import org.opentripplanner.ext.demandresponsivetransportation.DrtRequestContext;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.framework.io.OtpHttpClient;
import org.opentripplanner.framework.io.OtpHttpClientFactory;
import org.opentripplanner.framework.json.ObjectMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a demand responsive transportation service for Shotl.
 */
public class ShotlService extends CachingDemandResponsiveTransportationService {

  private static final Logger LOG = LoggerFactory.getLogger(ShotlService.class);
  private static final String DEFAULT_TIME_ESTIMATE_PATH = "drt/time-estimations";
  private static final ObjectMapper MAPPER = ObjectMappers.ignoringExtraFields();

  private final String timeEstimateUri;

  private final OtpHttpClient otpHttpClient;

  public ShotlService(DemandResponsiveTransportationServiceParameters config) {
    this(config.estimationsURL(), DEFAULT_TIME_ESTIMATE_PATH);
  }

  ShotlService(String baseUrl, String timeEstimateUri) {
    this.timeEstimateUri = baseUrl + timeEstimateUri;

    this.otpHttpClient = new OtpHttpClientFactory().create(LOG);
  }

  @Override
  protected ShotlArrivalEstimateResponse queryArrivalTimes(
    String paxAppId,
    String areaId,
    String userId,
    String rideType,
    WgsCoordinate fromCoordinate,
    WgsCoordinate toCoordinate,
    int regularPassengers,
    int wheelchairPassengers,
    Instant desiredPickupTime,
    DrtRequestContext context
  ) throws IOException {
    var uri = UriBuilder.fromUri(timeEstimateUri).build();

    // Create the request body
    var pickupLocation = new ShotlTimeEstimateRequest.Location(
      fromCoordinate.latitude(),
      fromCoordinate.longitude()
    );

    var dropoffLocation = new ShotlTimeEstimateRequest.Location(
      toCoordinate.latitude(),
      toCoordinate.longitude()
    );

    var passengers = new ShotlTimeEstimateRequest.Passengers(
      regularPassengers,
      wheelchairPassengers
    );

    var request = new ShotlTimeEstimateRequest(
      areaId,
      userId,
      rideType,
      pickupLocation,
      dropoffLocation,
      passengers,
      desiredPickupTime != null ? desiredPickupTime.getEpochSecond() : null,
      null // desiredDropoffTime not provided in current interface
    );

    // Convert request to JsonNode
    var jsonBody = MAPPER.valueToTree(request);

    LOG.info(
      "[DRT] API REQUEST | context={} | url={} | areaId={} | userId={} | rideType={} | " +
      "from=({},{}) | to=({},{}) | passengers=(regular={}, wheelchair={}) | pickupTime={}",
      context,
      uri,
      areaId,
      userId,
      rideType,
      fromCoordinate.latitude(),
      fromCoordinate.longitude(),
      toCoordinate.latitude(),
      toCoordinate.longitude(),
      regularPassengers,
      wheelchairPassengers,
      desiredPickupTime
    );

    LOG.debug("[DRT] API REQUEST BODY | context={} | body={}", context, jsonBody);

    ShotlApiResponse apiResponse = otpHttpClient.postJsonAndMap(
      uri,
      jsonBody,
      Duration.ofSeconds(60),
      headers(paxAppId),
      is -> {
        try {
          return MAPPER.readValue(is, ShotlApiResponse.class);
        } catch (Exception e) {
          LOG.error("[DRT] API PARSE ERROR | context={} | error={}", context, e.getMessage(), e);
          throw new RuntimeException("Failed to parse Shotl API response", e);
        }
      }
    );

    if (!apiResponse.success()) {
      var reason = apiResponse.reason();
      LOG.warn(
        "[DRT] API REJECTION | context={} | areaId={} | code={} | message={} | displayMessage={} | details={}",
        context,
        areaId,
        reason != null ? reason.code() : "unknown",
        reason != null ? reason.message() : "unknown",
        reason != null ? reason.displayMessage() : null,
        reason != null ? reason.details() : null
      );
      throw new ShotlBusinessRejectionException(reason);
    }

    var data = apiResponse.data();
    LOG.info(
      "[DRT] API SUCCESS | context={} | areaId={} | id={} | userExpectedPickupTime={} | " +
      "userExpectedDropoffTime={} | status={} | vehicleId={}",
      context,
      areaId,
      data.id(),
      data.userExpectedPickupTime(),
      data.userExpectedDropoffTime(),
      data.status(),
      data.vehicleId()
    );

    return convertToArrivalEstimateResponse(data);
  }

  private Map<String, String> headers(String paxAppId) throws IOException {
    return Map.ofEntries(
      entry(ACCEPT_LANGUAGE, "en_US"),
      entry(CONTENT_TYPE, "application/json"),
      entry("Shotl-Passenger-App-Id", paxAppId)
    );
  }

  /**
   * Converts the API response data to the existing ShotlArrivalEstimateResponse format.
   */
  private ShotlArrivalEstimateResponse convertToArrivalEstimateResponse(
    ShotlApiResponse.EstimatedTimesData data
  ) {
    return new ShotlArrivalEstimateResponse(
      data.id(),
      data.userId(),
      data.type(),
      data.status(),
      data.code(),
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(
        data.desiredPickupLocation().latitude(),
        data.desiredPickupLocation().longitude()
      ),
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(
        data.desiredDropoffLocation().latitude(),
        data.desiredDropoffLocation().longitude()
      ),
      convertScheduledPlace(data.scheduledPickupPlace()),
      convertScheduledPlace(data.scheduledDropoffPlace()),
      data.desiredPickupTime(),
      data.desiredDropoffTime(),
      data.userExpectedPickupTime(),
      data.userExpectedDropoffTime(),
      data.petitionTime(),
      new ShotlArrivalEstimateResponse.ShotlPassengers(
        data.passengers().regular(),
        data.passengers().wheelchair()
      ),
      data.vehicleId()
    );
  }

  private ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation convertScheduledPlace(
    ShotlApiResponse.ScheduledGeoLocation place
  ) {
    if (place == null) {
      return null;
    }
    return new ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation(
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(
        place.location().latitude(),
        place.location().longitude()
      ),
      place.name()
    );
  }
}
