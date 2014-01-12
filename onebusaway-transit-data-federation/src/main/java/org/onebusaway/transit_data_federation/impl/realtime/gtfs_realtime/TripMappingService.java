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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;

import org.onebusaway.transit_data_federation.services.blocks.BlockCalendarService;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;

/**
 * Pluggable component for mapping GTFS-realtime trips to their
 * GTFS counterparts.
 *
 * @author kurt
 */
abstract class TripMappingService {

  protected static final Logger _log = LoggerFactory.getLogger(GtfsRealtimeTripLibrary.class);

  protected BlockCalendarService _blockCalendarService;

  protected GtfsRealtimeEntitySource _entitySource;
  
  protected TransitGraphDao _transitGraphDao;

  public void setBlockCalendarService(BlockCalendarService blockCalendarService) {
    _blockCalendarService = blockCalendarService;
  }
  
  public void setEntitySource(GtfsRealtimeEntitySource entitySource) {
    _entitySource = entitySource;
  }
  
  public void setTransitGraphDao(TransitGraphDao transitGraphDao) {
    _transitGraphDao = transitGraphDao;
  }

  public abstract BlockDescriptor getTripDescriptorAsBlockDescriptor(TripDescriptor trip);
}
