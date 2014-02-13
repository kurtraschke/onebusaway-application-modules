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

import org.onebusaway.container.cache.Cacheable;
import org.onebusaway.exceptions.InvalidArgumentServiceException;
import org.onebusaway.exceptions.NoSuchAgencyServiceException;
import org.onebusaway.exceptions.ServiceException;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data.model.ListBean;
import org.onebusaway.transit_data.model.RouteBean;
import org.onebusaway.transit_data.model.RoutesBean;
import org.onebusaway.transit_data.model.SearchQueryBean;
import org.onebusaway.transit_data_federation.model.SearchResult;
import org.onebusaway.transit_data_federation.services.AgencyAndIdLibrary;
import org.onebusaway.transit_data_federation.services.RouteCollectionSearchService;
import org.onebusaway.transit_data_federation.services.RouteService;
import org.onebusaway.transit_data_federation.services.beans.RouteBeanService;
import org.onebusaway.transit_data_federation.services.beans.RoutesBeanService;
import org.onebusaway.transit_data_federation.services.beans.StopBeanService;
import org.onebusaway.transit_data_federation.services.transit_graph.AgencyEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.RouteCollectionEntry;
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
class RoutesBeanServiceImpl implements RoutesBeanService {

  private static Logger _log = LoggerFactory.getLogger(RoutesBeanServiceImpl.class);

  @Autowired
  private RouteService _routeService;

  @Autowired
  private RouteCollectionSearchService _searchService;

  @Autowired
  private RouteBeanService _routeBeanService;

  @Autowired
  private StopBeanService _stopService;

  @Autowired
  private TransitGraphDao _graphDao;

  @Override
  public RoutesBean getRoutesForQuery(SearchQueryBean query)
      throws ServiceException {

    SearchResult<AgencyAndId> searchResult;

    try {
     searchResult = _searchService.search(query.getQuery(), query.getBounds(), query.getMaxCount(), query.getMinScoreToKeep());
    } catch (IOException e) {
      throw new ServiceException();
    } catch (ParseException e) {
      throw new InvalidArgumentServiceException("query", "queryParseError");
    }

    List<RouteBean> routeBeans = new ArrayList<RouteBean>(searchResult.size());

    for (AgencyAndId routeId: searchResult.getResults()) {
      routeBeans.add(_routeBeanService.getRouteForId(routeId));
    }

    boolean limitExceeded = BeanServiceSupport.checkLimitExceeded(routeBeans,
        query.getMaxCount());
    return constructResult(routeBeans, limitExceeded);
  }

  @Cacheable
  @Override
  public ListBean<String> getRouteIdsForAgencyId(String agencyId) {
    AgencyEntry agency = _graphDao.getAgencyForId(agencyId);
    if (agency == null)
      throw new NoSuchAgencyServiceException(agencyId);
    List<String> ids = new ArrayList<String>();
    for (RouteCollectionEntry routeCollection : agency.getRouteCollections()) {
      AgencyAndId id = routeCollection.getId();
      ids.add(AgencyAndIdLibrary.convertToString(id));
    }
    return new ListBean<String>(ids, false);
  }

  @Cacheable
  @Override
  public ListBean<RouteBean> getRoutesForAgencyId(String agencyId) {
    AgencyEntry agency = _graphDao.getAgencyForId(agencyId);
    if (agency == null)
      throw new NoSuchAgencyServiceException(agencyId);
    List<RouteBean> routes = new ArrayList<RouteBean>();
    for (RouteCollectionEntry routeCollection : agency.getRouteCollections()) {
      AgencyAndId routeId = routeCollection.getId();
      RouteBean route = _routeBeanService.getRouteForId(routeId);
      routes.add(route);
    }
    return new ListBean<RouteBean>(routes, false);
  }

  /****
   * Private Methods
   ****/

  private RoutesBean constructResult(List<RouteBean> routeBeans,
      boolean limitExceeded) {

    Collections.sort(routeBeans, new RouteBeanIdComparator());

    RoutesBean result = new RoutesBean();
    result.setRoutes(routeBeans);
    result.setLimitExceeded(limitExceeded);
    return result;
  }

}
