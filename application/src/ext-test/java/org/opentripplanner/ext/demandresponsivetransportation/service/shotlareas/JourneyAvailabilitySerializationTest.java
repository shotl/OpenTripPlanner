package org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentripplanner.framework.json.ObjectMappers;

/**
 * Tests for the journey-availability request/response serialization.
 */
class JourneyAvailabilitySerializationTest {

  private static final ObjectMapper MAPPER = ObjectMappers.ignoringExtraFields();

  @Test
  void serializesRequestWithDesiredTime() throws Exception {
    var request = new JourneyAvailabilityRequest(
      "area-123",
      1706965200L,
      java.util.List.of(new JourneyAvailabilityRequest.JourneyPair(41.385, 2.173, 41.390, 2.165))
    );

    var json = MAPPER.writeValueAsString(request);

    assertTrue(json.contains("\"area_id\":\"area-123\""));
    assertTrue(json.contains("\"desired_time\":1706965200"));
    assertTrue(json.contains("\"pickup_latitude\":41.385"));
    assertTrue(json.contains("\"dropoff_longitude\":2.165"));
  }

  @Test
  void serializesRequestWithoutDesiredTime() throws Exception {
    var request = new JourneyAvailabilityRequest(
      "area-456",
      null,
      java.util.List.of(new JourneyAvailabilityRequest.JourneyPair(41.0, 2.0, 41.1, 2.1))
    );

    var json = MAPPER.writeValueAsString(request);

    // desired_time should be omitted (NON_NULL)
    assertFalse(json.contains("desired_time"));
    assertTrue(json.contains("\"area_id\":\"area-456\""));
  }

  @Test
  void deserializesSuccessResponse() throws Exception {
    var json =
      """
      {
        "journeys": [
          {
            "available": true,
            "pickup_subarea_id": "subarea-abc",
            "dropoff_subarea_id": "subarea-xyz",
            "pickup_latitude": 41.385,
            "pickup_longitude": 2.173,
            "dropoff_latitude": 41.390,
            "dropoff_longitude": 2.165
          },
          {
            "available": false,
            "pickup_subarea_id": "",
            "dropoff_subarea_id": "",
            "pickup_latitude": 41.400,
            "pickup_longitude": 2.180,
            "dropoff_latitude": 41.410,
            "dropoff_longitude": 2.190
          }
        ]
      }
      """;

    var response = MAPPER.readValue(json, JourneyAvailabilityResponse.class);

    assertNotNull(response);
    assertEquals(2, response.journeys().size());

    var first = response.journeys().get(0);
    assertTrue(first.available());
    assertEquals("subarea-abc", first.pickupSubareaId());
    assertEquals("subarea-xyz", first.dropoffSubareaId());
    assertEquals(41.385, first.pickupLatitude(), 0.001);

    var second = response.journeys().get(1);
    assertFalse(second.available());
  }

  @Test
  void deserializesEmptyJourneys() throws Exception {
    var json =
      """
      { "journeys": [] }
      """;

    var response = MAPPER.readValue(json, JourneyAvailabilityResponse.class);

    assertNotNull(response);
    assertTrue(response.journeys().isEmpty());
  }

  @Test
  void deserializesWithExtraFields() throws Exception {
    // ObjectMappers.ignoringExtraFields() should ignore unknown fields
    var json =
      """
      {
        "journeys": [
          {
            "available": true,
            "pickup_subarea_id": "s1",
            "dropoff_subarea_id": "s2",
            "pickup_latitude": 41.0,
            "pickup_longitude": 2.0,
            "dropoff_latitude": 41.1,
            "dropoff_longitude": 2.1,
            "extra_field": "should_be_ignored"
          }
        ],
        "some_other_field": 42
      }
      """;

    var response = MAPPER.readValue(json, JourneyAvailabilityResponse.class);

    assertNotNull(response);
    assertEquals(1, response.journeys().size());
    assertTrue(response.journeys().get(0).available());
  }
}
