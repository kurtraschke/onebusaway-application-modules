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

import com.google.transit.realtime.GtfsRealtime;

import org.onebusaway.transit_data_federation.services.transit_graph.BlockEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TripEntry;

/**
 *
 * @author kurt
 */
class DefaultTripMappingService extends TripMappingService {

  @Override
  public BlockDescriptor getTripDescriptorAsBlockDescriptor(GtfsRealtime.TripDescriptor trip) {
    if (!trip.hasTripId()) {
      return null;
    }
    TripEntry tripEntry = _entitySource.getTrip(trip.getTripId());
    if (tripEntry == null) {
      _log.warn("no trip found with id=" + trip.getTripId());
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
}
