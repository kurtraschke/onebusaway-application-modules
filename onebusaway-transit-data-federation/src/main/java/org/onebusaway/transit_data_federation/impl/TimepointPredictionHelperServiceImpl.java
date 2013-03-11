/**
 * Copyright (C) 2014 Kurt Raschke <kurt@kurtraschke.com>
 * Copyright (C) 2011 Brian Ferris <bdferris@onebusaway.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onebusaway.transit_data_federation.impl;

import org.onebusaway.collections.MappingLibrary;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.realtime.api.TimepointPredictionRecord;
import org.onebusaway.transit_data.model.trips.TripStatusBean;
import org.onebusaway.transit_data_federation.model.TargetTime;
import org.onebusaway.transit_data_federation.services.AgencyAndIdLibrary;
import org.onebusaway.transit_data_federation.services.PredictionHelperService;
import org.onebusaway.transit_data_federation.services.realtime.BlockLocation;
import org.onebusaway.transit_data_federation.services.realtime.BlockLocationService;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockStopTimeEntry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * PredictionHelperService implementation which applies
 * <code>TimepointPredictionRecord</code>s if available, or schedule deviation
 * if set.
 * 
 * If neither timepoint predictions nor schedule deviation are set, then the
 * returned <code>TimepointPredictionRecords</code> will only have scheduled
 * times set.
 * 
 */
@Component
public class TimepointPredictionHelperServiceImpl implements
    PredictionHelperService {

  @Autowired
  private BlockLocationService _blockLocationService;

  @Override
  public List<TimepointPredictionRecord> getPredictionRecordsForTrip(
      String agencyId, TripStatusBean tripStatus) {
    Long now = new Date().getTime();

    BlockLocation loc = _blockLocationService.getLocationForVehicleAndTime(
        AgencyAndIdLibrary.convertFromString(tripStatus.getVehicleId()),
        new TargetTime(now, now));

    List<BlockStopTimeEntry> stopTimes = new ArrayList<BlockStopTimeEntry>(
        loc.getActiveTrip().getStopTimes());
    Collections.sort(stopTimes, new BlockStopTimeEntryComparator());

    List<TimepointPredictionRecord> timepointPredictions = new ArrayList<TimepointPredictionRecord>();

    for (BlockStopTimeEntry bst : stopTimes) {
      AgencyAndId stopId = bst.getStopTime().getStop().getId();
      TimepointPredictionRecord tpr = new TimepointPredictionRecord();
      tpr.setTimepointId(stopId);
      tpr.setTimepointScheduledTime(tripStatus.getServiceDate()
          + (bst.getStopTime().getArrivalTime() * 1000));

      timepointPredictions.add(tpr);
    }

    if (loc.getTimepointPredictions() != null
        && !loc.getTimepointPredictions().isEmpty()) {
      Map<AgencyAndId, TimepointPredictionRecord> rawTimepoints = MappingLibrary.mapToValue(
          loc.getTimepointPredictions(), "timepointId");
      long lastDelay = 0;

      for (TimepointPredictionRecord tpr : timepointPredictions) {
        AgencyAndId stopId = tpr.getTimepointId();
        long predictedTime;

        /*
         * If we don't actually have a TPR for this stop, then we hold over the
         * delay from the last stop. This seems to be a faithful implementation
         * of the logic described at
         * https://developers.google.com/transit/gtfs-realtime/trip-updates with
         * the exception that we do not handle unknown delays.
         */

        if (rawTimepoints.containsKey(stopId)) {
          predictedTime = rawTimepoints.get(stopId).getTimepointPredictedTime();
          lastDelay = predictedTime - tpr.getTimepointScheduledTime();
          tpr.setTimepointPredictedTime(predictedTime);
        } else {
          tpr.setTimepointPredictedTime(tpr.getTimepointScheduledTime()
              + lastDelay);
        }
      }
    } else if (tripStatus.isScheduleDeviationSet()) {
      // If individual TimepointPredictionRecords are not available, but a
      // schedule deviation is, then fall back to applying that schedule
      // deviation to every stoptime in the trip.
      long scheduleDeviation = Math.round(tripStatus.getScheduleDeviation() * 1000.0);

      for (TimepointPredictionRecord tpr : timepointPredictions) {
        tpr.setTimepointPredictedTime(tpr.getTimepointScheduledTime()
            + scheduleDeviation);
      }

    }

    return timepointPredictions;
  }

  private static class BlockStopTimeEntryComparator implements
      Comparator<BlockStopTimeEntry> {

    @Override
    public int compare(BlockStopTimeEntry o1, BlockStopTimeEntry o2) {
      return o1.getStopTime().getSequence() - o2.getStopTime().getSequence();
    }
  }
}
