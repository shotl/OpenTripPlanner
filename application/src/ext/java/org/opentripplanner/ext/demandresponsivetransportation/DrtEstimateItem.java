package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import org.opentripplanner.framework.geometry.WgsCoordinate;

/**
 * One DRT leg of a batch: its pickup and dropoff places and the desired pickup time. Everything
 * else (area, passenger, fares) is shared by the whole batch.
 */
public record DrtEstimateItem(WgsCoordinate from, WgsCoordinate to, Instant pickupTime) {}
