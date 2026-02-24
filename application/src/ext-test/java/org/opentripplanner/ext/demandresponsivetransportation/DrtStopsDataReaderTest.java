package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DrtStopsDataReaderTest {

  private final DrtStopsDataReader reader = new DrtStopsDataReader();

  @Test
  void readDrtStopsFromCsv() throws IOException {
    var csv = "stop_id\nS0\nS2\nS5\n";
    var stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    var stopIds = reader.readDrtStops(stream);
    assertEquals(Set.of("S0", "S2", "S5"), stopIds);
  }

  @Test
  void readDrtStopsTrimsWhitespace() throws IOException {
    var csv = "stop_id\n  S0  \n S2\n";
    var stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    var stopIds = reader.readDrtStops(stream);
    assertEquals(Set.of("S0", "S2"), stopIds);
  }

  @Test
  void readDrtStopsSkipsEmptyLines() throws IOException {
    var csv = "stop_id\nS0\n\n\nS2\n";
    var stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    var stopIds = reader.readDrtStops(stream);
    assertEquals(Set.of("S0", "S2"), stopIds);
  }

  @Test
  void readDrtStopsHeaderOnly() throws IOException {
    var csv = "stop_id\n";
    var stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    var stopIds = reader.readDrtStops(stream);
    assertTrue(stopIds.isEmpty());
  }

  @Test
  void readGtfsDirectoryWithDrtStops(@TempDir Path tempDir) throws IOException {
    Files.writeString(tempDir.resolve("drt_stops.txt"), "stop_id\nS1\nS3\n");
    var stopIds = reader.readGtfs(tempDir.toFile());
    assertEquals(Set.of("S1", "S3"), stopIds);
  }

  @Test
  void readGtfsDirectoryWithoutDrtStops(@TempDir Path tempDir) {
    var stopIds = reader.readGtfs(tempDir.toFile());
    assertTrue(stopIds.isEmpty());
  }

  @Test
  void readGtfsMissingDirectoryReturnsEmpty() {
    var stopIds = reader.readGtfs(new File("/nonexistent/path"));
    assertTrue(stopIds.isEmpty());
  }
}
