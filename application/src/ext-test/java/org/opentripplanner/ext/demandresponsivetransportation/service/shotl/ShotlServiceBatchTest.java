package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ext.demandresponsivetransportation.DrtEstimateItem;
import org.opentripplanner.ext.demandresponsivetransportation.DrtRequestContext;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.framework.io.OtpHttpClientFactory;
import org.opentripplanner.framework.json.ObjectMappers;
import org.opentripplanner.routing.api.request.PassengerFareType;
import org.slf4j.LoggerFactory;

/**
 * The batch call to Shotl rides against a local HTTP stub: one request per batch, results mapped
 * back by key, a 404 falling back to single calls and a failed batch leaving every leg empty.
 */
class ShotlServiceBatchTest {

  private static final ObjectMapper MAPPER = ObjectMappers.ignoringExtraFields();
  private static final Instant AT = Instant.ofEpochSecond(1_760_000_000L);

  private HttpServer server;
  private final AtomicInteger batchCalls = new AtomicInteger();
  private final AtomicInteger singleCalls = new AtomicInteger();
  private final AtomicReference<JsonNode> lastBatch = new AtomicReference<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private ShotlService start(int batchStatus, String batchBody) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v3/drt/time-estimations/batch", exchange -> {
      batchCalls.incrementAndGet();
      lastBatch.set(MAPPER.readTree(exchange.getRequestBody()));
      var bytes = batchBody.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(batchStatus, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.createContext("/v3/drt/time-estimations", exchange -> {
      if (exchange.getRequestURI().getPath().endsWith("/batch")) {
        return;
      }
      singleCalls.incrementAndGet();
      var bytes =
        ("{\"success\":true,\"data\":" + estimate("single") + "}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    var base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    var client = new OtpHttpClientFactory().create(LoggerFactory.getLogger(getClass()));
    return new ShotlService(base, "v3/drt/time-estimations", client);
  }

  private static String estimate(String id) {
    return (
      "{\"id\":\"" +
      id +
      "\",\"status\":\"DRAFT\",\"desired_pickup_location\":{\"latitude\":41.38,\"longitude\":2.17}," +
      "\"desired_dropoff_location\":{\"latitude\":41.40,\"longitude\":2.18}," +
      "\"user_expected_pickup_time\":1760000300,\"user_expected_dropoff_time\":1760000900," +
      "\"passengers\":{\"regular\":1,\"wheelchair\":0},\"shotl_duration_seconds\":600}"
    );
  }

  private static List<DrtEstimateItem> items(int n) {
    return java.util.stream.IntStream.range(0, n)
      .mapToObj(i ->
        new DrtEstimateItem(
          new WgsCoordinate(41.38, 2.17),
          new WgsCoordinate(41.40 + i / 1000.0, 2.18),
          AT
        )
      )
      .toList();
  }

  private static List<ShotlArrivalEstimateResponse> batch(ShotlService s, int n) {
    return s.arrivalTimesBatch(
      "app-1",
      "area-1",
      "user-1",
      "RIDE_NOW",
      1,
      0,
      DrtRequestContext.ACCESS_SHIFTING,
      List.of(new PassengerFareType("REGULAR", 1)),
      true,
      "es",
      items(n)
    );
  }

  @Test
  void sendsOneCallAndMapsTheResultsBackByKey() throws IOException {
    var body =
      "{\"success\":true,\"data\":{\"results\":[" +
      "{\"key\":\"2\",\"success\":true,\"data\":" +
      estimate("e2") +
      "}," +
      "{\"key\":\"0\",\"success\":true,\"data\":" +
      estimate("e0") +
      "}," +
      "{\"key\":\"1\",\"success\":false,\"reason\":{\"code\":\"overbooking\",\"message\":\"full\"}}]}}";
    var service = start(200, body);

    var answers = batch(service, 3);

    assertEquals(1, batchCalls.get());
    assertEquals(0, singleCalls.get());
    assertEquals(3, answers.size());
    assertEquals("e0", answers.get(0).id());
    assertNull(answers.get(1), "a rejected leg has no estimate");
    assertEquals("e2", answers.get(2).id());
    var sent = lastBatch.get();
    assertEquals("area-1", sent.get("area_id").asText());
    assertTrue(sent.get("pickup_shift").asBoolean());
    assertEquals("REGULAR", sent.get("passenger_fare_type").get(0).get("type").asText());
    assertEquals(3, sent.get("items").size());
    assertEquals("1", sent.get("items").get(1).get("key").asText());
    assertEquals(AT.getEpochSecond(), sent.get("items").get(1).get("desired_pickup_time").asLong());
  }

  @Test
  void fallsBackToSingleCallsWhenRidesHasNoBatchRoute() throws IOException {
    var service = start(404, "{\"error\":\"not found\"}");

    var answers = batch(service, 3);
    var again = batch(service, 2);

    assertEquals(1, batchCalls.get(), "a second batch goes leg by leg without asking again");
    assertEquals(5, singleCalls.get());
    assertEquals(3, answers.size());
    assertNotNull(answers.get(0));
    assertEquals(2, again.size());
  }

  @Test
  void leavesEveryLegEmptyWhenTheBatchFails() throws IOException {
    var service = start(500, "{\"error\":\"boom\"}");

    var answers = batch(service, 3);

    assertEquals(1, batchCalls.get());
    assertEquals(0, singleCalls.get(), "no retry leg by leg");
    assertEquals(3, answers.size());
    answers.forEach(a -> assertNull(a));
  }

  @Test
  void keepsBatchesOnWhenRidesAnswersItsOwnNotFound() throws IOException {
    var service = start(
      404,
      "{\"error\":{\"message\":\"user not found\",\"code\":\"resource-not-found\"}}"
    );

    var answers = batch(service, 3);
    batch(service, 2);

    assertEquals(2, batchCalls.get(), "an unknown user does not switch batches off");
    assertEquals(0, singleCalls.get());
    answers.forEach(a -> assertNull(a));
  }

  @Test
  void tellsAMissingRouteFromRidesOwnNotFound() {
    assertTrue(
      ShotlService.routeMissing(
        new RuntimeException("HTTP request failed with status code 404: {\"error\":\"not found\"}")
      )
    );
    assertTrue(ShotlService.routeMissing(new RuntimeException("with status code 405: x")));
    assertFalse(
      ShotlService.routeMissing(
        new RuntimeException("with status code 404: {\"error\":{\"code\":\"resource-not-found\"}}")
      )
    );
    assertFalse(ShotlService.routeMissing(new RuntimeException("with status code 500: x")));
  }

  @Test
  void readsTheStatusOfAFailedCallFromItsMessage() {
    assertEquals(
      404,
      ShotlService.statusOf(new RuntimeException("HTTP request failed with status code 404: x"))
    );
    assertEquals(0, ShotlService.statusOf(new RuntimeException("connection refused")));
  }
}
