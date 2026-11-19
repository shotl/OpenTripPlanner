package org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ext.demandresponsivetransportation.JourneyAvailabilityService.JourneyPair;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.framework.io.OtpHttpClientFactory;
import org.slf4j.LoggerFactory;

/** The journey-availability call to Shotl areas against a local HTTP stub. */
class ShotlAreasServiceTest {

  private static final List<JourneyPair> JOURNEYS = List.of(
    new JourneyPair(new WgsCoordinate(41.38, 2.17), new WgsCoordinate(41.40, 2.18))
  );

  private HttpServer server;
  private final List<String> requestIds = new CopyOnWriteArrayList<>();
  private final List<String> areaTimezones = new CopyOnWriteArrayList<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private ShotlAreasService start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/journey-availability", exchange -> {
      requestIds.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Request-Id")));
      areaTimezones.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Area-Timezone")));
      var bytes = "{\"journeys\":[{\"available\":true}]}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    var base = "http://127.0.0.1:" + server.getAddress().getPort();
    var client = new OtpHttpClientFactory().create(LoggerFactory.getLogger(getClass()));
    return new ShotlAreasService(base, client);
  }

  @Test
  void sendsTheRequestId() throws IOException {
    var service = start();

    var response = service.checkAvailability("area-1", Instant.EPOCH, JOURNEYS, "quote-1", null);

    assertNotNull(response);
    assertEquals(1, response.journeys().size());
    assertEquals(List.of("quote-1"), requestIds);
  }

  @Test
  void sendsNoRequestIdWithoutOne() throws IOException {
    var service = start();

    assertNotNull(service.checkAvailability("area-1", Instant.EPOCH, JOURNEYS, null, null));

    assertEquals(List.of("null"), requestIds, "no X-Request-Id header");
    assertEquals(List.of("null"), areaTimezones, "no X-Area-Timezone header");
  }

  @Test
  void sendsTheAreaTimezone() throws IOException {
    var service = start();

    var response = service.checkAvailability(
      "area-1",
      Instant.EPOCH,
      JOURNEYS,
      "quote-1",
      "Europe/Madrid"
    );

    assertNotNull(response);
    assertEquals(List.of("quote-1"), requestIds);
    assertEquals(List.of("area-1=Europe/Madrid"), areaTimezones);
  }

  @Test
  void sendsNoAreaTimezoneWithoutAValidOneOrAnArea() throws IOException {
    var service = start();

    assertNotNull(service.checkAvailability("area-1", Instant.EPOCH, JOURNEYS, null, null));
    assertNotNull(service.checkAvailability("area-1", Instant.EPOCH, JOURNEYS, null, "+01:00"));
    assertNotNull(service.checkAvailability(null, Instant.EPOCH, JOURNEYS, null, "Europe/Madrid"));

    assertEquals(List.of("null", "null", "null"), areaTimezones, "no X-Area-Timezone header");
  }
}
