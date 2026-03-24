package org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas;

import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.UriBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.opentripplanner.ext.demandresponsivetransportation.JourneyAvailabilityService;
import org.opentripplanner.framework.io.OtpHttpClient;
import org.opentripplanner.framework.json.ObjectMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client for the Shotl Areas journey-availability endpoint.
 * <p>
 * This service makes a single batch HTTP call to check whether multiple
 * DRT journeys are feasible, based on subarea configuration and vehicle
 * movement rules. It is much cheaper than the Shotl rides time-estimation
 * endpoint and should be called as a pre-filter before access shifting.
 */
public class ShotlAreasService implements JourneyAvailabilityService {

  private static final Logger LOG = LoggerFactory.getLogger(ShotlAreasService.class);
  private static final String DEFAULT_PATH = "journey-availability";
  private static final ObjectMapper MAPPER = ObjectMappers.ignoringExtraFields();

  /**
   * HTTP timeout for the journey-availability endpoint. This endpoint is
   * a lightweight local-data lookup on the areas service, so a short
   * timeout is appropriate.
   */
  private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

  private final String journeyAvailabilityUri;
  private final OtpHttpClient otpHttpClient;

  public ShotlAreasService(String baseUrl, OtpHttpClient otpHttpClient) {
    this(baseUrl, DEFAULT_PATH, otpHttpClient);
  }

  ShotlAreasService(String baseUrl, String path, OtpHttpClient otpHttpClient) {
    // Ensure base URL ends with /
    String normalizedBase = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    this.journeyAvailabilityUri = normalizedBase + path;
    this.otpHttpClient = otpHttpClient;
  }

  @Override
  public JourneyAvailabilityResponse checkAvailability(
    String areaId,
    Instant desiredTime,
    List<JourneyPair> journeys
  ) {
    var uri = UriBuilder.fromUri(journeyAvailabilityUri).build();

    var requestJourneys = journeys
      .stream()
      .map(j ->
        new JourneyAvailabilityRequest.JourneyPair(
          j.pickup().latitude(),
          j.pickup().longitude(),
          j.dropoff().latitude(),
          j.dropoff().longitude()
        )
      )
      .toList();

    var request = new JourneyAvailabilityRequest(
      areaId,
      desiredTime != null ? desiredTime.getEpochSecond() : null,
      requestJourneys
    );

    var jsonBody = MAPPER.valueToTree(request);

    LOG.debug(
      "[DRT] Journey availability REQUEST | areaId={} | desiredTime={} | journeyCount={}",
      areaId,
      desiredTime,
      journeys.size()
    );
    LOG.debug("[DRT] Journey availability REQUEST BODY | body={}", jsonBody);

    try {
      var response = otpHttpClient.postJsonAndMap(
        uri,
        jsonBody,
        API_TIMEOUT,
        Map.of(CONTENT_TYPE, "application/json"),
        is -> {
          try {
            return MAPPER.readValue(is, JourneyAvailabilityResponse.class);
          } catch (Exception e) {
            LOG.error("[DRT] Journey availability PARSE ERROR | error={}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse journey-availability response", e);
          }
        }
      );

      int availableCount = (int) response
        .journeys()
        .stream()
        .filter(JourneyAvailabilityResponse.JourneyResult::available)
        .count();

      LOG.debug(
        "[DRT] Journey availability RESPONSE | areaId={} | total={} | available={} | unavailable={}",
        areaId,
        response.journeys().size(),
        availableCount,
        response.journeys().size() - availableCount
      );

      return response;
    } catch (Exception e) {
      LOG.error(
        "[DRT] Journey availability HTTP ERROR | areaId={} | url={} | request={} | error={}",
        areaId,
        uri,
        jsonBody,
        e.getMessage()
      );
      // Return null to indicate the service is unavailable — callers should
      // skip filtering and proceed with all stops (fail-open behavior)
      return null;
    }
  }
}
