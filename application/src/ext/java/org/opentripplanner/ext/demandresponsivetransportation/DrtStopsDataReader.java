package org.opentripplanner.ext.demandresponsivetransportation;

import com.csvreader.CsvReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import org.opentripplanner.utils.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads DRT-eligible stop IDs from a {@code drt_stops.txt} file in a GTFS feed.
 * <p>
 * The file format is a simple CSV with a single required column: {@code stop_id}.
 * Only stops listed in this file will be considered for DRT access/egress routing.
 * If the file is missing, an empty set is returned (meaning no DRT stop filtering is applied).
 */
public class DrtStopsDataReader {

  private static final Logger LOG = LoggerFactory.getLogger(DrtStopsDataReader.class);
  private static final String DRT_STOPS_FILE = "drt_stops.txt";
  private static final String STOP_ID_COLUMN = "stop_id";

  /**
   * Read DRT-eligible stop IDs from a GTFS directory.
   *
   * @param directory the GTFS directory
   * @return set of stop IDs, empty if file is missing
   */
  public Set<String> readGtfs(File directory) {
    File drtStopsFile = new File(directory, DRT_STOPS_FILE);
    if (!drtStopsFile.exists()) {
      LOG.info(
        "No {} found in GTFS directory {}, DRT stop filtering disabled.",
        DRT_STOPS_FILE,
        directory
      );
      return Set.of();
    }
    try (InputStream stream = new FileInputStream(drtStopsFile)) {
      return readDrtStops(stream);
    } catch (IOException e) {
      LOG.error("Failed to read {} from GTFS directory {}", DRT_STOPS_FILE, directory, e);
      return Set.of();
    }
  }

  /**
   * Read DRT-eligible stop IDs from a GTFS ZIP file.
   *
   * @param file the GTFS ZIP file
   * @return set of stop IDs, empty if file is missing
   */
  public Set<String> readGtfsZip(File file) {
    try (ZipFile zipFile = new ZipFile(file, ZipFile.OPEN_READ)) {
      var entry = zipFile.getEntry(DRT_STOPS_FILE);
      if (entry == null) {
        LOG.info("No {} found in GTFS ZIP {}, DRT stop filtering disabled.", DRT_STOPS_FILE, file);
        return Set.of();
      }
      try (InputStream stream = zipFile.getInputStream(entry)) {
        return readDrtStops(stream);
      }
    } catch (IOException e) {
      LOG.error("Failed to read {} from GTFS ZIP {}", DRT_STOPS_FILE, file, e);
      return Set.of();
    }
  }

  /**
   * Parse the CSV input stream and extract stop IDs.
   */
  Set<String> readDrtStops(InputStream stream) throws IOException {
    Set<String> stopIds = new HashSet<>();
    CsvReader reader = new CsvReader(stream, StandardCharsets.UTF_8);
    reader.readHeaders();

    while (reader.readRecord()) {
      String stopId = reader.get(STOP_ID_COLUMN);
      if (StringUtils.hasValue(stopId)) {
        stopIds.add(stopId.trim());
      } else {
        LOG.warn("Empty stop_id in {} at line {}", DRT_STOPS_FILE, reader.getCurrentRecord() + 1);
      }
    }

    LOG.info("Read {} DRT-eligible stop IDs from {}", stopIds.size(), DRT_STOPS_FILE);
    return stopIds;
  }
}
