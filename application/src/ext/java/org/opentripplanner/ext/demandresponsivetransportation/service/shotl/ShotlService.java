package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

import static jakarta.ws.rs.core.HttpHeaders.ACCEPT_LANGUAGE;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static java.util.Map.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.UriBuilder;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.opentripplanner.ext.demandresponsivetransportation.BatchEstimatesUnsupportedException;
import org.opentripplanner.ext.demandresponsivetransportation.CachingDemandResponsiveTransportationService;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationServiceParameters;
import org.opentripplanner.ext.demandresponsivetransportation.DrtEstimateItem;
import org.opentripplanner.ext.demandresponsivetransportation.DrtRequestContext;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.framework.io.OtpHttpClient;
import org.opentripplanner.framework.io.OtpHttpClientException;
import org.opentripplanner.framework.json.ObjectMappers;
import org.opentripplanner.routing.api.request.PassengerFareType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a demand responsive transportation service for Shotl.
 */
public class ShotlService extends CachingDemandResponsiveTransportationService {

  private static final Logger LOG = LoggerFactory.getLogger(ShotlService.class);
  private static final String DEFAULT_TIME_ESTIMATE_PATH = "v3/drt/time-estimations";
  private static final String BATCH_SUFFIX = "/batch";
  /** OtpHttpClient names a failed status only in its message ("...with status code 404: ..."). */
  private static final Pattern STATUS_CODE = Pattern.compile("status code (\\d{3})");
  private static final ObjectMapper MAPPER = ObjectMappers.ignoringExtraFields();
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern(
    "yyyy-MM-dd'T'HH:mm:ss'Z'"
  ).withZone(ZoneId.of("UTC"));

  /**
   * HTTP request timeout for Shotl API calls. Kept short to avoid blocking connection pool
   * threads when the Shotl service is slow or unresponsive.
   */
  private static final Duration API_TIMEOUT = Duration.ofSeconds(60);

  private final String timeEstimateUri;

  private final OtpHttpClient otpHttpClient;

  /**
   * Creates a ShotlService with the given config and a shared HTTP client.
   * The HTTP client should be created once and shared across all ShotlService instances
   * to benefit from connection pooling.
   */
  public ShotlService(
    DemandResponsiveTransportationServiceParameters config,
    OtpHttpClient otpHttpClient
  ) {
    this(config.estimationsURL(), DEFAULT_TIME_ESTIMATE_PATH, otpHttpClient);
  }

  ShotlService(String baseUrl, String timeEstimateUri, OtpHttpClient otpHttpClient) {
    this.timeEstimateUri = baseUrl + timeEstimateUri;
    this.otpHttpClient = otpHttpClient;
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
    DrtRequestContext context,
    List<PassengerFareType> passengerFareType,
    boolean pickupShift,
    String acceptLanguage
  ) {
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

    // Always send pickup_shift=true for access/egress and leg decoration requests
    request.setPickupShift(true);

    // Set optional passenger fare types for pricing calculation
    if (passengerFareType != null && !passengerFareType.isEmpty()) {
      var fareTypeInputs = passengerFareType
        .stream()
        .map(ft -> new ShotlTimeEstimateRequest.PassengerFareTypeInput(ft.type(), ft.count()))
        .collect(java.util.stream.Collectors.toList());
      request.setPassengerFareType(fareTypeInputs);
    }

    // Convert request to JsonNode
    var jsonBody = MAPPER.valueToTree(request);

    LOG.debug(
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

    ShotlApiResponse apiResponse;
    try {
      apiResponse = otpHttpClient.postJsonAndMap(
        uri,
        jsonBody,
        API_TIMEOUT,
        headers(paxAppId, acceptLanguage),
        is -> {
          try {
            return MAPPER.readValue(is, ShotlApiResponse.class);
          } catch (Exception e) {
            LOG.error("[DRT] API PARSE ERROR | context={} | error={}", context, e.getMessage(), e);
            throw new RuntimeException("Failed to parse Shotl API response", e);
          }
        }
      );
    } catch (Exception e) {
      LOG.error(
        "[DRT] API HTTP ERROR | context={} | areaId={} | url={} | request={} | error={}",
        context,
        areaId,
        uri,
        jsonBody,
        e.getMessage()
      );
      throw e;
    }

    if (!apiResponse.success()) {
      var reason = apiResponse.reason();
      LOG.warn(
        "[DRT] API REJECTION | context={} | areaId={} | code={} | message={} | displayMessage={} | details={} | request={} | response={}",
        context,
        areaId,
        reason != null ? reason.code() : "unknown",
        reason != null ? reason.message() : "unknown",
        reason != null ? reason.displayMessage() : null,
        reason != null ? reason.details() : null,
        jsonBody,
        apiResponse
      );
      throw new ShotlBusinessRejectionException(reason);
    }

    var data = apiResponse.data();
    LOG.debug(
      "[DRT] API SUCCESS | context={} | areaId={} | id={} | desiredPickupLocation=({},{}) | " +
      "desiredDropoffLocation=({},{}) | scheduledPickupPlace={} | scheduledDropoffPlace={} | " +
      "desiredPickupTime={} | desiredDropoffTime={} | userExpectedPickupTime={} | " +
      "userExpectedDropoffTime={} | doorToDoorDurationSeconds={} | shotlDurationSeconds={} | status={} | vehicleId={}",
      context,
      areaId,
      data.id(),
      data.desiredPickupLocation().latitude(),
      data.desiredPickupLocation().longitude(),
      data.desiredDropoffLocation().latitude(),
      data.desiredDropoffLocation().longitude(),
      data.scheduledPickupPlace(),
      data.scheduledDropoffPlace(),
      formatTimestamp(data.desiredPickupTime()),
      formatTimestamp(data.desiredDropoffTime()),
      formatTimestamp(data.userExpectedPickupTime()),
      formatTimestamp(data.userExpectedDropoffTime()),
      data.doorToDoorDurationSeconds(),
      data.shotlDurationSeconds(),
      data.status(),
      data.vehicleId()
    );

    return convertToArrivalEstimateResponse(data);
  }

  private Map<String, String> headers(String paxAppId, String acceptLanguage) {
    return Map.ofEntries(
      entry(ACCEPT_LANGUAGE, acceptLanguage != null ? acceptLanguage : "en-US"),
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
      data.vehicleId(),
      data.shotlDurationSeconds(),
      data.pickupWalkingSeconds(),
      data.dropoffWalkingSeconds()
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

  private String formatTimestamp(Long epochSeconds) {
    if (epochSeconds == null) {
      return "null";
    }
    return TIMESTAMP_FORMATTER.format(Instant.ofEpochSecond(epochSeconds));
  }

  @Override
  protected List<ShotlArrivalEstimateResponse> queryArrivalTimesBatch(
    String paxAppId,
    String areaId,
    String userId,
    String rideType,
    int regularPassengers,
    int wheelchairPassengers,
    DrtRequestContext context,
    List<PassengerFareType> passengerFareType,
    boolean pickupShift,
    String acceptLanguage,
    List<DrtEstimateItem> items
  ) {
    var uri = UriBuilder.fromUri(timeEstimateUri + BATCH_SUFFIX).build();
    var body = MAPPER.createObjectNode();
    body.put("area_id", areaId);
    body.put("user_id", userId);
    body.put("ride_type", rideType);
    var passengers = body.putObject("passengers");
    passengers.put("regular", regularPassengers);
    passengers.put("wheelchair", wheelchairPassengers);
    // As the single route: always a pickup shift for access, egress and decoration.
    body.put("pickup_shift", true);
    if (passengerFareType != null && !passengerFareType.isEmpty()) {
      var fares = body.putArray("passenger_fare_type");
      for (var fare : passengerFareType) {
        fares.addObject().put("type", fare.type()).put("count", fare.count());
      }
    }
    var array = body.putArray("items");
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      var node = array.addObject();
      node.put("key", String.valueOf(i));
      node
        .putObject("desired_pickup_location")
        .put("latitude", item.from().latitude())
        .put("longitude", item.from().longitude());
      node
        .putObject("desired_dropoff_location")
        .put("latitude", item.to().latitude())
        .put("longitude", item.to().longitude());
      if (item.pickupTime() != null) {
        node.put("desired_pickup_time", item.pickupTime().getEpochSecond());
      }
    }
    LOG.debug(
      "[DRT] BATCH REQUEST | context={} | url={} | areaId={} | items={}",
      context,
      uri,
      areaId,
      items.size()
    );
    ShotlBatchApiResponse answer;
    try {
      answer = otpHttpClient.postJsonAndMap(
        uri,
        body,
        API_TIMEOUT,
        headers(paxAppId, acceptLanguage),
        is -> {
          try {
            return MAPPER.readValue(is, ShotlBatchApiResponse.class);
          } catch (Exception e) {
            throw new RuntimeException("Failed to parse Shotl batch response", e);
          }
        }
      );
    } catch (OtpHttpClientException e) {
      if (routeMissing(e)) {
        throw new BatchEstimatesUnsupportedException("rides has no batch route at " + uri);
      }
      throw e;
    }
    if (!answer.success() || answer.data() == null || answer.data().results() == null) {
      var reason = answer.reason();
      throw new IllegalStateException(
        "batch rejected: " +
        (reason != null ? reason.code() + " " + reason.message() : "no results")
      );
    }
    var byKey = new HashMap<String, ShotlBatchApiResponse.Item>();
    for (var result : answer.data().results()) {
      if (result != null && result.key() != null) {
        byKey.put(result.key(), result);
      }
    }
    var out = new ArrayList<ShotlArrivalEstimateResponse>(items.size());
    for (int i = 0; i < items.size(); i++) {
      var result = byKey.get(String.valueOf(i));
      if (result == null) {
        LOG.warn("[DRT] BATCH ITEM MISSING | context={} | areaId={} | key={}", context, areaId, i);
        out.add(null);
      } else if (!result.success() || result.data() == null) {
        var reason = result.reason();
        LOG.warn(
          "[DRT] BATCH ITEM REJECTION | context={} | areaId={} | key={} | code={} | message={}",
          context,
          areaId,
          i,
          reason != null ? reason.code() : "unknown",
          reason != null ? reason.message() : "unknown"
        );
        out.add(null);
      } else {
        out.add(convertToArrivalEstimateResponse(result.data()));
      }
    }
    return out;
  }

  /**
   * Whether rides answered that the batch route does not exist (an older rides): a 405, or a
   * 404 that is not one of rides' own answers. rides answers a resource it does not find (an
   * unknown user, an unknown area) with the code "resource-not-found": the route exists then,
   * and switching batches off for every request would be wrong.
   */
  static boolean routeMissing(Exception e) {
    var status = statusOf(e);
    if (status == 405) {
      return true;
    }
    var message = e.getMessage();
    return status == 404 && (message == null || !message.contains("resource-not-found"));
  }

  /** The HTTP status a failed call names in its message; 0 when it names none. */
  static int statusOf(Exception e) {
    var message = e.getMessage();
    if (message == null) {
      return 0;
    }
    var matcher = STATUS_CODE.matcher(message);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
  }
}
