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
import java.util.concurrent.ExecutionException;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationService;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationServiceParameters;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.framework.io.OtpHttpClient;
import org.opentripplanner.framework.io.OtpHttpClientFactory;
import org.opentripplanner.framework.json.ObjectMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a ride hailing service for Uber.
 */
public class ShotlService implements DemandResponsiveTransportationService {

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

  public ShotlArrivalEstimateResponse arrivalTimes(
    String paxAppId,
    String areaId,
    String userId,
    String rideType,
    WgsCoordinate fromCoordinate,
    WgsCoordinate toCoordinate,
    int regularPassengers,
    int wheelchairPassengers,
    Instant desiredPickupTime
  ) throws ExecutionException, IOException {
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

    LOG.info("Made arrival time request to Shotl API at following URL: {}", uri);

    ShotlArrivalEstimateResponse response = otpHttpClient.postJsonAndMap(
      uri,
      jsonBody,
      Duration.ofSeconds(60),
      headers(paxAppId),
      is -> {
        try {
          return MAPPER.readValue(is, ShotlArrivalEstimateResponse.class);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    );

    LOG.info("Received {} Shotl arrival time estimates", response);

    return response;
  }

  private Map<String, String> headers(String paxAppId) throws IOException {
    return Map.ofEntries(
      entry(ACCEPT_LANGUAGE, "en_US"),
      entry(CONTENT_TYPE, "application/json"),
      entry("Shotl-Passenger-App-Id", paxAppId)
    );
  }
}
