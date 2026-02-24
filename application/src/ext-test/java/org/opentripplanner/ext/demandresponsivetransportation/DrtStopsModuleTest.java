package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentripplanner.graph_builder.ConfiguredDataSource;
import org.opentripplanner.gtfs.graphbuilder.GtfsFeedParameters;
import org.opentripplanner.gtfs.graphbuilder.GtfsFeedParametersBuilder;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.service.SiteRepository;
import org.opentripplanner.transit.service.TimetableRepository;

class DrtStopsModuleTest {

  @Test
  void loadsAndResolvesStopsFromDrtStopsTxt(@TempDir Path tempDir) throws IOException {
    // Create a GTFS directory with drt_stops.txt
    Files.writeString(tempDir.resolve("drt_stops.txt"), "stop_id\nS1\nS3\n");

    // Create stops in the timetable repository
    var siteRepoBuilder = new SiteRepository().withContext();
    var testModel = new TimetableRepositoryForTest(siteRepoBuilder);

    RegularStop s1 = testModel.stop("S1", 47.0, 19.0).build();
    RegularStop s2 = testModel.stop("S2", 47.1, 19.1).build();
    RegularStop s3 = testModel.stop("S3", 47.2, 19.2).build();

    siteRepoBuilder.withRegularStop(s1);
    siteRepoBuilder.withRegularStop(s2);
    siteRepoBuilder.withRegularStop(s3);

    var siteRepo = siteRepoBuilder.build();
    var timetableRepository = new TimetableRepository(siteRepo, new Deduplicator());

    // Create a configured data source pointing to the temp directory
    var dataSource = createConfiguredDataSource(tempDir);

    // Run the module
    var module = new DrtStopsModule(List.of(dataSource), timetableRepository);
    module.buildGraph();

    // Verify
    var eligibleStops = timetableRepository.getDrtEligibleStops();
    assertEquals(2, eligibleStops.size());

    var eligibleIds = eligibleStops
      .stream()
      .map(s -> s.getId().getId())
      .collect(Collectors.toSet());
    assertEquals(Set.of("S1", "S3"), eligibleIds);
  }

  @Test
  void noDrtStopsFileReturnsEmpty(@TempDir Path tempDir) {
    // No drt_stops.txt in directory
    var siteRepoBuilder = new SiteRepository().withContext();
    var testModel = new TimetableRepositoryForTest(siteRepoBuilder);

    RegularStop s1 = testModel.stop("S1", 47.0, 19.0).build();
    siteRepoBuilder.withRegularStop(s1);

    var siteRepo = siteRepoBuilder.build();
    var timetableRepository = new TimetableRepository(siteRepo, new Deduplicator());

    var dataSource = createConfiguredDataSource(tempDir);

    var module = new DrtStopsModule(List.of(dataSource), timetableRepository);
    module.buildGraph();

    assertTrue(timetableRepository.getDrtEligibleStops().isEmpty());
  }

  @Test
  void unresolvedStopIdsAreIgnored(@TempDir Path tempDir) throws IOException {
    // drt_stops.txt references S1 and S_NONEXISTENT
    Files.writeString(tempDir.resolve("drt_stops.txt"), "stop_id\nS1\nS_NONEXISTENT\n");

    var siteRepoBuilder = new SiteRepository().withContext();
    var testModel = new TimetableRepositoryForTest(siteRepoBuilder);

    RegularStop s1 = testModel.stop("S1", 47.0, 19.0).build();
    siteRepoBuilder.withRegularStop(s1);

    var siteRepo = siteRepoBuilder.build();
    var timetableRepository = new TimetableRepository(siteRepo, new Deduplicator());

    var dataSource = createConfiguredDataSource(tempDir);

    var module = new DrtStopsModule(List.of(dataSource), timetableRepository);
    module.buildGraph();

    var eligibleStops = timetableRepository.getDrtEligibleStops();
    assertEquals(1, eligibleStops.size());
    assertEquals("S1", eligibleStops.iterator().next().getId().getId());
  }

  private ConfiguredDataSource<GtfsFeedParameters> createConfiguredDataSource(Path directory) {
    return new ConfiguredDataSource<>(
      new org.opentripplanner.datastore.file.DirectoryDataSource(
        directory.toFile(),
        org.opentripplanner.datastore.api.FileType.GTFS
      ),
      new GtfsFeedParametersBuilder().build()
    );
  }
}
