/**
 * Copyright (C) 2011 Brian Ferris <bdferris@onebusaway.org>
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
package org.onebusaway.transit_data_federation.impl.beans;

import org.onebusaway.exceptions.NoSuchAgencyServiceException;
import org.onebusaway.exceptions.ServiceException;
import org.onebusaway.geospatial.model.SearchBounds;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data.model.ListBean;
import org.onebusaway.transit_data.model.SearchQueryBean;
import org.onebusaway.transit_data.model.StopBean;
import org.onebusaway.transit_data.model.StopsBean;
import org.onebusaway.transit_data_federation.model.SearchResult;
import org.onebusaway.transit_data_federation.services.AgencyAndIdLibrary;
import org.onebusaway.transit_data_federation.services.StopSearchService;
import org.onebusaway.transit_data_federation.services.StopSearchService.SearchMode;
import org.onebusaway.transit_data_federation.services.beans.StopBeanService;
import org.onebusaway.transit_data_federation.services.beans.StopsBeanService;
import org.onebusaway.transit_data_federation.services.transit_graph.AgencyEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;

import org.apache.lucene.queryparser.classic.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
class StopsBeanServiceImpl implements StopsBeanService {

  private static Logger _log = LoggerFactory.getLogger(StopsBeanServiceImpl.class);

  private static final double MIN_SCORE = 1.0;

  @Autowired
  private StopSearchService _searchService;

  @Autowired
  private StopBeanService _stopBeanService;

  @Autowired
  private TransitGraphDao _transitGraphDao;

  @Override
  public StopsBean getStops(SearchQueryBean queryBean) throws ServiceException {
    String query = queryBean.getQuery();
    SearchBounds bounds = queryBean.getBounds();
    int maxCount = queryBean.getMaxCount();
    SearchResult<AgencyAndId> sr = null;

    try {
       sr = _searchService.search(query, SearchMode.ALL, bounds, maxCount, MIN_SCORE);
    } catch (ParseException e) {
      _log.error("error executing stop search", e);
      throw new ServiceException();
    } catch (IOException e) {
      _log.error("error executing stop search", e);
      throw new ServiceException();
    }

    List<AgencyAndId> stopIds = sr.getResults();

    boolean limitExceeded = BeanServiceSupport.checkLimitExceeded(stopIds,
        queryBean.getMaxCount());
    List<StopBean> stopBeans = new ArrayList<StopBean>();

    for (AgencyAndId stopId : stopIds) {
      StopBean stopBean = _stopBeanService.getStopForId(stopId);
      if (stopBean == null) {
        throw new ServiceException();
      }

      /**
       * If the stop doesn't have any routes actively serving it, don't include
       * it in the results
       */
      if (stopBean.getRoutes().isEmpty())
        continue;

      stopBeans.add(stopBean);
    }

    return constructResult(stopBeans, limitExceeded);

  }

  @Override
  public ListBean<String> getStopsIdsForAgencyId(String agencyId) {
    AgencyEntry agency = _transitGraphDao.getAgencyForId(agencyId);
    if (agency == null)
      throw new NoSuchAgencyServiceException(agencyId);
    List<String> ids = new ArrayList<String>();
    for (StopEntry stop : agency.getStops()) {
      AgencyAndId id = stop.getId();
      ids.add(AgencyAndIdLibrary.convertToString(id));
    }
    return new ListBean<String>(ids, false);
  }

  private StopsBean constructResult(List<StopBean> stopBeans,
      boolean limitExceeded) {

    Collections.sort(stopBeans, new StopBeanIdComparator());

    StopsBean result = new StopsBean();
    result.setStops(stopBeans);
    result.setLimitExceeded(limitExceeded);
    return result;
  }

}
