/**
 * Copyright (C) 2013 Kurt Raschke
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

import java.util.HashMap;
import java.util.Map;

import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data_federation.services.realtime.gtfs_realtime.GtfsRealtimeEntityListener;
import org.onebusaway.transit_data_federation.services.service_alerts.ServiceAlerts;
import org.onebusaway.transit_data_federation.services.service_alerts.ServiceAlerts.ServiceAlert;
import org.onebusaway.transit_data_federation.services.service_alerts.ServiceAlertsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;

/**
 *
 * @author kurt
 */
class AlertEntityListener implements GtfsRealtimeEntityListener {

    private static final Logger _log = LoggerFactory.getLogger(AlertEntityListener.class);
    private Map<AgencyAndId, ServiceAlert> _alertsById = new HashMap<AgencyAndId, ServiceAlerts.ServiceAlert>();
    private GtfsRealtimeAlertLibrary _alertLibrary;
    private ServiceAlertsService _serviceAlertsService;
    private String _agencyId;

    public void setAlertLibrary(GtfsRealtimeAlertLibrary _alertLibrary) {
        this._alertLibrary = _alertLibrary;
    }

    public void setServiceAlertsService(ServiceAlertsService _serviceAlertService) {
        this._serviceAlertsService = _serviceAlertService;
    }

    public void setAgencyId(String _agencyId) {
        this._agencyId = _agencyId;
    }

    @Override
    public void handleNewFeedEntity(FeedEntity fe) {
        Alert alert = fe.getAlert();
        if (alert == null) {
            _log.warn("expected a FeedEntity with an Alert");
            return;
        }
        AgencyAndId id = createId(fe.getId());
        ServiceAlert.Builder serviceAlertBuilder = _alertLibrary.getAlertAsServiceAlert(id, alert);
        ServiceAlert serviceAlert = serviceAlertBuilder.build();
        ServiceAlert existingAlert = _alertsById.get(id);
        if (existingAlert == null || !existingAlert.equals(serviceAlert)) {
            _alertsById.put(id, serviceAlert);
            _serviceAlertsService.createOrUpdateServiceAlert(serviceAlertBuilder, _agencyId);
        }
    }

    @Override
    public void handleDeletedFeedEntity(FeedEntity fe) {
        Alert alert = fe.getAlert();
        if (alert == null) {
            _log.warn("expected a FeedEntity with an Alert");
            return;
        }
        AgencyAndId id = createId(fe.getId());
        _alertsById.remove(id);
        _serviceAlertsService.removeServiceAlert(id);
    }

    private AgencyAndId createId(String id) {
        return new AgencyAndId(_agencyId, id);
    }
}
