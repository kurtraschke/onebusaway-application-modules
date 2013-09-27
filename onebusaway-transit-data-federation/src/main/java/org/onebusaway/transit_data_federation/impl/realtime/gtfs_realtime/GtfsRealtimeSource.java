/**
 * Copyright (C) 2011 Google, Inc.
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
package org.onebusaway.transit_data_federation.impl.realtime.gtfs_realtime;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.http.conn.HttpClientConnectionManager;
import org.onebusaway.realtime.api.VehicleLocationListener;
import org.onebusaway.transit_data_federation.services.AgencyService;
import org.onebusaway.transit_data_federation.services.blocks.BlockCalendarService;
import org.onebusaway.transit_data_federation.services.service_alerts.ServiceAlertsService;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;


public class GtfsRealtimeSource {

  private static final Logger _log = LoggerFactory.getLogger(GtfsRealtimeSource.class);

  private AgencyService _agencyService;

  private TransitGraphDao _transitGraphDao;

  private BlockCalendarService _blockCalendarService;

  private VehicleLocationListener _vehicleLocationListener;

  private ServiceAlertsService _serviceAlertService;

  private ScheduledExecutorService _scheduledExecutorService;
  
  private HttpClientConnectionManager _connectionManager;

  private URI _tripUpdatesUri;

  private URI _vehiclePositionsUri;

  private URI _alertsUri;

  private int _refreshInterval = 30;

  private List<String> _agencyIds = new ArrayList<String>();

  private GtfsRealtimeEntitySource _entitySource;

  private GtfsRealtimeTripLibrary _tripsLibrary;

  private GtfsRealtimeAlertLibrary _alertLibrary;

  private GtfsRealtimeFeedImpl _alertsFeed;

  private GtfsRealtimeFeedImpl _tripUpdatesFeed;

  private GtfsRealtimeFeedImpl _vehiclePositionsFeed;

  @Autowired
  public void setAgencyService(AgencyService agencyService) {
    _agencyService = agencyService;
  }

  @Autowired
  public void setTransitGraphDao(TransitGraphDao transitGraphDao) {
    _transitGraphDao = transitGraphDao;
  }

  @Autowired
  public void setBlockCalendarService(BlockCalendarService blockCalendarService) {
    _blockCalendarService = blockCalendarService;
  }

  @Autowired
  public void setVehicleLocationListener(
      VehicleLocationListener vehicleLocationListener) {
    _vehicleLocationListener = vehicleLocationListener;
  }

  @Autowired
  public void setServiceAlertService(ServiceAlertsService serviceAlertService) {
    _serviceAlertService = serviceAlertService;
  }

  @Autowired
  public void setScheduledExecutorService(
      ScheduledExecutorService scheduledExecutorService) {
    _scheduledExecutorService = scheduledExecutorService;
  }
  
  @Autowired
  public void setHttpClientConnectionManager(
          HttpClientConnectionManager connectionManager) {
    _connectionManager = connectionManager;
  }

  public void setTripUpdatesUri(URI tripUpdatesUri) {
    _tripUpdatesUri = tripUpdatesUri;
  }

  public void setVehiclePositionsUri(URI vehiclePositionsUri) {
    _vehiclePositionsUri = vehiclePositionsUri;
  }

  public void setAlertsUri(URI alertsUri) {
    _alertsUri = alertsUri;
  }

  public void setRefreshInterval(int refreshInterval) {
    _refreshInterval = refreshInterval;
  }

  public void setAgencyId(String agencyId) {
    _agencyIds.add(agencyId);
  }

  public void setAgencyIds(List<String> agencyIds) {
    _agencyIds.addAll(agencyIds);
  }

  @PostConstruct
  public void start() {
    if (_agencyIds.isEmpty()) {
      _log.info("no agency ids specified for GtfsRealtimeSource, so defaulting to full agency id set");
      List<String> agencyIds = _agencyService.getAllAgencyIds();
      _agencyIds.addAll(agencyIds);
      if (_agencyIds.size() > 3) {
        _log.warn("The default agency id set is quite large (n="
            + _agencyIds.size()
            + ").  You might consider specifying the applicable agencies for your GtfsRealtimeSource.");
      }
    }

    _entitySource = new GtfsRealtimeEntitySource();
    _entitySource.setAgencyIds(_agencyIds);
    _entitySource.setTransitGraphDao(_transitGraphDao);

    _tripsLibrary = new GtfsRealtimeTripLibrary();
    _tripsLibrary.setBlockCalendarService(_blockCalendarService);
    _tripsLibrary.setEntitySource(_entitySource);

    _alertLibrary = new GtfsRealtimeAlertLibrary();
    _alertLibrary.setEntitySource(_entitySource);

    if (_alertsUri != null) {
        AlertEntityListener alertsListener = new AlertEntityListener();
       alertsListener.setAgencyId(_agencyIds.get(0));
       alertsListener.setAlertLibrary(_alertLibrary);
       alertsListener.setServiceAlertsService(_serviceAlertService);

        _alertsFeed = new GtfsRealtimeFeedImpl(_alertsUri, _refreshInterval,
                alertsListener, _scheduledExecutorService, _connectionManager);

       _alertsFeed.start();
    }

    if (_tripUpdatesUri != null || _vehiclePositionsUri != null) {
        CombinedEntityListener combinedListener = new CombinedEntityListener();
       combinedListener.setTripLibrary(_tripsLibrary);
        combinedListener.setVehicleLocationListener(_vehicleLocationListener);
        if (_tripUpdatesUri != null) {
            _tripUpdatesFeed = new GtfsRealtimeFeedImpl(_tripUpdatesUri, 
                    _refreshInterval, 
                    combinedListener.getTripUpdatesEntityListener(),
                    _scheduledExecutorService,
                    _connectionManager);

            _tripUpdatesFeed.start();
        }

        if (_vehiclePositionsUri != null) {
             _vehiclePositionsFeed = new GtfsRealtimeFeedImpl(_vehiclePositionsUri,
                     _refreshInterval, 
                     combinedListener.getVehiclePositionsListener(),
                     _scheduledExecutorService,
                     _connectionManager);

            _vehiclePositionsFeed.start();
        }
    }
  }

  @PreDestroy
  public void stop() {
    if (_alertsFeed != null) {
      _alertsFeed.stop();
      _alertsFeed = null;
    }
  
    if (_tripUpdatesFeed != null) {
      _tripUpdatesFeed.stop();
      _tripUpdatesFeed = null;
    }


    if (_vehiclePositionsFeed != null) {
      _vehiclePositionsFeed.stop();
      _vehiclePositionsFeed = null;
    }
  }
}
