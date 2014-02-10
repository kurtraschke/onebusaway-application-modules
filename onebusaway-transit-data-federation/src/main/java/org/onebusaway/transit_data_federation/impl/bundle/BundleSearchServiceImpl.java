/**
 * Copyright (C) 2014 Kurt Raschke <kurt@kurtraschke.com>
 * Copyright (C) 2011 Metropolitan Transportation Authority
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
package org.onebusaway.transit_data_federation.impl.bundle;

import org.onebusaway.container.ConfigurationParameter;
import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data.model.RouteBean;
import org.onebusaway.transit_data_federation.impl.RefreshableResources;
import org.onebusaway.transit_data_federation.model.narrative.StopNarrative;
import org.onebusaway.transit_data_federation.services.AgencyAndIdLibrary;
import org.onebusaway.transit_data_federation.services.AgencyService;
import org.onebusaway.transit_data_federation.services.beans.RoutesBeanService;
import org.onebusaway.transit_data_federation.services.beans.StopsBeanService;
import org.onebusaway.transit_data_federation.services.bundle.BundleSearchService;
import org.onebusaway.transit_data_federation.services.narrative.NarrativeService;
import org.onebusaway.transit_data_federation.services.transit_graph.AgencyEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;

import com.google.common.collect.Multimaps;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.PostConstruct;

/**
 * Proposes suggestions to the user based on bundle content e.g. stop ID or stop
 * code and route short names.
 * 
 * @author asutula
 * 
 */
@Component
public class BundleSearchServiceImpl implements BundleSearchService {

  private static final Logger _log = LoggerFactory.getLogger(BundleSearchServiceImpl.class);

  @Autowired
  private AgencyService _agencyService;
  @Autowired
  private StopsBeanService _stopsBeanService;
  @Autowired
  private RoutesBeanService _routesBeanService;
  @Autowired
  private TransitGraphDao _transitGraphDao;
  @Autowired
  private NarrativeService _narrativeService;

  private boolean _suggestStopCodes = false;
  private final SortedSetMultimap<String, String> suggestions = Multimaps.synchronizedSortedSetMultimap(TreeMultimap.<String, String> create());

  @PostConstruct
  @Refreshable(dependsOn = RefreshableResources.TRANSIT_GRAPH)
  public void init() {
    Runnable initThread = new Runnable() {
      @Override
      public void run() {
        _log.info("Refreshing bundle search suggestions.");
        suggestions.clear();

        List<String> allAgencyIds = _agencyService.getAllAgencyIds();
        for (String agencyId : allAgencyIds) {
          List<RouteBean> routes = _routesBeanService.getRoutesForAgencyId(
              agencyId).getList();
          for (RouteBean route : routes) {
            String shortName = route.getShortName();
            if (shortName != null) {
              generateInputsForString(shortName, "\\s+");
            }
          }

          if (!_suggestStopCodes) {
            List<String> stopIds = _stopsBeanService.getStopsIdsForAgencyId(
                agencyId).getList();
            for (String stopId : stopIds) {
              AgencyAndId agencyAndId = AgencyAndIdLibrary.convertFromString(stopId);
              generateInputsForString(agencyAndId.getId(), null);
            }
          } else {
            AgencyEntry agencyEntry = _transitGraphDao.getAgencyForId(agencyId);
            for (StopEntry stop : agencyEntry.getStops()) {
              AgencyAndId stopId = stop.getId();
              StopNarrative sn = _narrativeService.getStopForId(stopId);
              String stopCode = sn.getCode();

              if (stopCode != null && !stopId.getId().equals(stopCode)) {
                generateInputsForString(stopCode, null);
              }
            }
          }
        }
        _log.info("Done refreshing bundle search suggestions.");
      }
    };

    new Thread(initThread).start();
  }

  /**
   * When <code>true</code>, we will suggest stop code values (
   * <code>stop_code</code> from GTFS) <em>and not</em> stop ID values (
   * <code>stop_id</code> from GTFS).
   * 
   * Set to <code>true</code> for bundles where <code>stop_id</code> contains an
   * internal identifier and <code>stop_code</code> contains the value used by
   * passengers to identify stops.
   * 
   * @param suggestStopCodes
   */
  @ConfigurationParameter
  public void setSuggestStopCodes(boolean suggestStopCodes) {
    _suggestStopCodes = suggestStopCodes;
  }

  private void generateInputsForString(String string, String splitRegex) {
    String[] parts;
    if (splitRegex != null) {
      parts = string.split(splitRegex);
    } else {
      parts = new String[] {string};
    }
    for (String part : parts) {
      int length = part.length();
      for (int i = 0; i < length; i++) {
        String key = part.substring(0, i + 1).toLowerCase();
        suggestions.put(key, string);
      }
    }
  }

  @Override
  public List<String> getSuggestions(String input) {
    List<String> querySuggestions;
    if (!suggestions.containsKey(input)) {
      querySuggestions = Collections.emptyList();
    } else {
      synchronized (suggestions) {
        querySuggestions = new ArrayList<String>(suggestions.get(input));
      }
      if (querySuggestions.size() > 10) {
        querySuggestions = querySuggestions.subList(0, 10);
      }
    }
    return querySuggestions;
  }
}