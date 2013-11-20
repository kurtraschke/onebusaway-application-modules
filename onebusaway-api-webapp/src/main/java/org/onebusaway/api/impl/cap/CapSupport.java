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
 
package org.onebusaway.api.impl.cap;

import javax.xml.datatype.DatatypeConfigurationException;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.onebusaway.transit_data.model.RouteBean;
import org.onebusaway.transit_data.model.StopBean;
import org.onebusaway.transit_data.model.service_alerts.ESeverity;
import org.onebusaway.transit_data.model.service_alerts.NaturalLanguageStringBean;
import org.onebusaway.transit_data.model.service_alerts.ServiceAlertBean;
import org.onebusaway.transit_data.model.service_alerts.SituationAffectsBean;
import org.onebusaway.transit_data.model.service_alerts.SituationConsequenceBean;
import org.onebusaway.transit_data.model.service_alerts.TimeRangeBean;
import org.onebusaway.transit_data.model.trips.TripBean;
import org.onebusaway.transit_data.services.TransitDataService;
import oasis.names.tc.emergency.cap._1.Alert;
import oasis.names.tc.emergency.cap._1.Alert.Info;
import oasis.names.tc.emergency.cap._1.Alert.Info.Area;
import oasis.names.tc.emergency.cap._1.Alert.Info.Area.Geocode;
import oasis.names.tc.emergency.cap._1.Alert.Info.CategoryType;
import oasis.names.tc.emergency.cap._1.Alert.Info.CertaintyType;
import oasis.names.tc.emergency.cap._1.Alert.Info.EventCode;
import oasis.names.tc.emergency.cap._1.Alert.Info.SeverityType;
import oasis.names.tc.emergency.cap._1.Alert.Info.UrgencyType;
import oasis.names.tc.emergency.cap._1.Alert.MsgTypeType;
import oasis.names.tc.emergency.cap._1.Alert.ScopeType;
import oasis.names.tc.emergency.cap._1.Alert.StatusType;
import oasis.names.tc.emergency.cap._1.ObjectFactory;

/**
 *
 * @author kurt
 */
@Component
public class CapSupport {

  @Autowired
  private TransitDataService _service;

  private final ObjectFactory capFactory = new ObjectFactory();

  public CapSupport() {
  }

  private Map<String, String> mapNaturalLanguageStringBeans(Collection<NaturalLanguageStringBean> nlsbs) {
    if (nlsbs == null) {
      return Collections.EMPTY_MAP;
    }

    Map<String, String> out = new HashMap<String, String>(nlsbs.size());

    for (NaturalLanguageStringBean nlsb : nlsbs) {
      out.put(nlsb.getLang(), nlsb.getValue());
    }

    return out;
  }

  public Alert buildCapAlert(ServiceAlertBean sab) throws DatatypeConfigurationException {
    Alert theAlert = capFactory.createAlert();

    theAlert.setIdentifier(sab.getId());
    theAlert.setSender("OneBusAway");
    theAlert.setStatus(StatusType.ACTUAL);
    theAlert.setMsgType(MsgTypeType.ALERT);
    theAlert.setScope(ScopeType.PUBLIC);
    theAlert.setSent(XmlSupport.makeXmlGregorianCalendar(sab.getCreationTime() > 0 ? sab.getCreationTime() : System.currentTimeMillis()));
    theAlert.setSource("OneBusAway");

    Map<String, String> summariesByLang = mapNaturalLanguageStringBeans(sab.getSummaries());
    Map<String, String> descriptionsByLang = mapNaturalLanguageStringBeans(sab.getDescriptions());
    Map<String, String> urlsByLang = mapNaturalLanguageStringBeans(sab.getUrls());

    Set<String> langs = new HashSet<String>();
    langs.addAll(summariesByLang.keySet());
    langs.addAll(descriptionsByLang.keySet());

    for (String lang : langs) {
      String summary = summariesByLang.get(lang);
      String description = descriptionsByLang.get(lang);
      String url = urlsByLang.get(lang);

      if (sab.getActiveWindows() != null && !sab.getActiveWindows().isEmpty()) {
        for (TimeRangeBean trb : sab.getActiveWindows()) {
          Info info = capFactory.createAlertInfo();
          fillCapAlertInfo(info, sab);
          fillCapAlertInfoLang(info, lang, summary, description, url);
          fillCapAlertInfoTime(info, trb);

          theAlert.getInfo().add(info);
        }
      } else {
        Info info = capFactory.createAlertInfo();
        fillCapAlertInfo(info, sab);
        fillCapAlertInfoLang(info, lang, summary, description, url);

        theAlert.getInfo().add(info);
      }
    }

    return theAlert;
  }

  private void fillCapAlertInfoLang(Alert.Info info, String lang, String summary, String description, String url) {
    info.setLanguage(lang);

    if (description == null && summary == null) {
      throw new IllegalArgumentException("Can't have null summary and description");
    }

    info.setEvent((description != null) ? description : summary);

    if (summary != null) {
      info.setHeadline(summary);
    }

    if (description != null) {
      info.setDescription(description);
    }

    if (url != null) {
      info.setWeb(url);
    }
  }

  private void fillCapAlertInfoTime(Alert.Info info, TimeRangeBean trb) throws DatatypeConfigurationException {
    info.setOnset(XmlSupport.makeXmlGregorianCalendar(trb.getFrom()));
    info.setExpires(XmlSupport.makeXmlGregorianCalendar(trb.getTo()));
  }

  public void fillCapAlertInfo(Alert.Info info, ServiceAlertBean sab) {
    info.getCategory().add(CategoryType.TRANSPORT);

    info.setUrgency(UrgencyType.UNKNOWN);
    info.setSeverity(mapSeverity(sab.getSeverity()));
    info.setCertainty(CertaintyType.UNKNOWN);

    if (sab.getConsequences() != null) {
      for (SituationConsequenceBean consequence : sab.getConsequences()) {
        if (consequence.getEffect() != null) {
          EventCode ec = capFactory.createAlertInfoEventCode();
          ec.setValueName("consequence");
          ec.setValue(consequence.getEffect().toString());
          info.getEventCode().add(ec);
        }
      }
    }

    if (sab.getAllAffects() != null) {

      for (SituationAffectsBean affects : sab.getAllAffects()) {
        Area area = capFactory.createAlertInfoArea();
        area.setAreaDesc(affectsNarrative(affects));

        if (affects.getAgencyId() != null) {
          Geocode gc = capFactory.createAlertInfoAreaGeocode();
          gc.setValueName("agency_id");
          gc.setValue(affects.getAgencyId());
          area.getGeocode().add(gc);
        }

        if (affects.getDirectionId() != null) {
          Geocode gc = capFactory.createAlertInfoAreaGeocode();
          gc.setValueName("direction_id");
          gc.setValue(affects.getDirectionId());
          area.getGeocode().add(gc);
        }

        if (affects.getRouteId() != null) {
          Geocode gc = capFactory.createAlertInfoAreaGeocode();
          gc.setValueName("route_id");
          gc.setValue(affects.getRouteId());
          area.getGeocode().add(gc);
        }

        if (affects.getStopId() != null) {
          String stopId = affects.getStopId();

          Geocode gc = capFactory.createAlertInfoAreaGeocode();
          gc.setValueName("stop_id");
          gc.setValue(stopId);
          area.getGeocode().add(gc);

          StopBean sb = _service.getStop(stopId);
          area.getCircle().add(sb.getLat() + "," + sb.getLon() + " 0");
        }

        if (affects.getTripId() != null) {
          Geocode gc = capFactory.createAlertInfoAreaGeocode();
          gc.setValueName("trip_id");
          gc.setValue(affects.getTripId());
          area.getGeocode().add(gc);
        }

        info.getArea().add(area);
      }
    }

  }

  private SeverityType mapSeverity(ESeverity severity) {
    switch (severity) {
      case SEVERE:
        return SeverityType.SEVERE;
      case SLIGHT:
        return SeverityType.MODERATE;
      case VERY_SEVERE:
        return SeverityType.EXTREME;
      case VERY_SLIGHT:
      case NORMAL:
      case NO_IMPACT:
        return SeverityType.MINOR;
      case UNDEFINED:
      case UNKNOWN:
      default:
        return SeverityType.UNKNOWN;
    }
  }

  private String affectsNarrative(SituationAffectsBean affects) {
    StringBuilder sb = new StringBuilder();

    if (affects.getAgencyId() != null) {
      sb.append(_service.getAgency(affects.getAgencyId()).getName());
      sb.append(" ");
    }

    if (affects.getRouteId() != null) {
      RouteBean rb = _service.getRouteForId(affects.getRouteId());

      if (rb.getShortName() != null) {
        sb.append(rb.getShortName());
        sb.append(" ");
      }

      if (rb.getLongName() != null) {
        sb.append(rb.getLongName());
        sb.append(" ");
      }
    }

    if (affects.getStopId() != null) {
      sb.append(_service.getStop(affects.getStopId()).getName());
      sb.append(" ");
    }

    if (affects.getTripId() != null) {
      TripBean tb = _service.getTrip(affects.getTripId());

      if (tb.getTripShortName() != null) {
        sb.append(tb.getTripShortName());
        sb.append(" ");
      }

      if (tb.getTripHeadsign() != null) {
        sb.append(tb.getTripHeadsign());
      }
    }

    return sb.toString().trim();
  }
}
