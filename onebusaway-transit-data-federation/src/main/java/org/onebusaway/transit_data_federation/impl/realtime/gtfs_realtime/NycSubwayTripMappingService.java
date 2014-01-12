/**
 * Copyright (C) 2013 Kurt Raschke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.transit_data_federation.impl.realtime.gtfs_realtime;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtimeNYCT;

import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.onebusaway.gtfs.services.calendar.CalendarService;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TripEntry;

/**
 *
 * @author kurt
 */
class NycSubwayTripMappingService extends TripMappingService {

  private SetMultimap<String, String> atsIdToTripId = HashMultimap.create();
  private CalendarService _calendarService;

  @Autowired
  public void setCalendarService(CalendarService calendarService) {
    _calendarService = calendarService;
  }

  @Override
  public BlockDescriptor getTripDescriptorAsBlockDescriptor(TripDescriptor trip) {
    if (!trip.hasTripId()) {
      return null;
    }

    Set<AgencyAndId> serviceIdsOnDate = _calendarService.getServiceIdsOnDate(serviceDateFromTripStartDate(trip.getStartDate())); //serviceDateToServiceIdMap.get(serviceDateFromTripStartDate(trip.getStartDate()).getAsDate());
    List<TripEntry> allTrips = _transitGraphDao.getAllTrips();
    TripEntry tripEntry = null;
    String atsTrainId = trip.getExtension(GtfsRealtimeNYCT.nyctTripDescriptor).getTrainId();

    atsIdToTripId.put(atsTrainId, trip.getTripId());

    if (atsIdToTripId.get(atsTrainId).size() > 1) {
      _log.info("Trip IDs for ATS train ID {}: {}", atsTrainId, atsIdToTripId.get(atsTrainId));
    }

    assignTrip:
    for (TripEntry te : allTrips) {
      for (String tripId : atsIdToTripId.get(atsTrainId)) {
        if (serviceIdsOnDate.contains(te.getServiceId().getId())
                && te.getRoute().getId().getId().equals(trip.getRouteId())
                && te.getId().getId().endsWith(tripId)) {
          tripEntry = te;
          _log.info("Matched trip ID {}, ATS train ID {} to trip ID {}", trip.getTripId(), atsTrainId, te.getId().getId());
          break assignTrip;
        }
      }
    }

    if (tripEntry == null) {
      _log.warn("No trip found for ID {}, ATS train ID {}", trip.getTripId(), atsTrainId);
      return null;
    }
    BlockEntry block = tripEntry.getBlock();
    BlockDescriptor blockDescriptor = new BlockDescriptor();
    blockDescriptor.setBlockEntry(block);
    if (trip.hasStartDate()) {
      blockDescriptor.setStartDate(trip.getStartDate());
    }
    if (trip.hasStartTime()) {
      blockDescriptor.setStartTime(trip.getStartTime());
    }
    return blockDescriptor;
  }

  private ServiceDate serviceDateFromTripStartDate(String tripStartDate) {
    int year = Integer.parseInt(tripStartDate.substring(0, 4), 10);
    int month = Integer.parseInt(tripStartDate.substring(4, 6), 10);
    int day = Integer.parseInt(tripStartDate.substring(6, 8), 10);

    return new ServiceDate(year, month, day);
  }
}