/**
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
package org.onebusaway.api.actions.api.where;

import org.onebusaway.api.actions.api.SpatialActionSupport;
import org.onebusaway.api.impl.MaxCountSupport;
import org.onebusaway.api.model.transit.BeanFactoryV2;
import org.onebusaway.api.model.transit.RouteV2Bean;
import org.onebusaway.exceptions.OutOfServiceAreaServiceException;
import org.onebusaway.exceptions.ServiceException;
import org.onebusaway.transit_data.model.RoutesBean;
import org.onebusaway.transit_data.model.SearchQueryBean;
import org.onebusaway.transit_data.services.TransitDataService;

import org.apache.struts2.rest.DefaultHttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

public class RoutesForLocationAction extends SpatialActionSupport {

  private static final long serialVersionUID = 1L;

  private static final int V1 = 1;

  private static final int V2 = 2;

  private static final double DEFAULT_SEARCH_RADIUS_WITHOUT_QUERY = 500;

  private static final double DEFAULT_SEARCH_RADIUS_WITH_QUERY = 10 * 1000;

  @Autowired
  private TransitDataService _service;

  private String _query;

  private MaxCountSupport _maxCount = new MaxCountSupport(10, 50);

  public RoutesForLocationAction() {
    super(LegacyV1ApiSupport.isDefaultToV1() ? V1 : V2);
  }

  public void setQuery(String query) {
    _query = query;
  }

  public String getQuery() {
    return _query;
  }

  public void setMaxCount(int maxCount) {
    _maxCount.setMaxCount(maxCount);
  }

  public DefaultHttpHeaders index() throws IOException, ServiceException {
    validateSpatialArgs();

    int maxCount = _maxCount.getMaxCount();
    if (maxCount <= 0)
      addFieldError("maxCount", "must be greater than zero");

    if (hasErrors())
      return setValidationErrorsResponse();

    SearchQueryBean routesQuery = new SearchQueryBean();

    if (_query != null)
      routesQuery.setQuery(_query);

    routesQuery.setBounds(getBounds(_query == null ? DEFAULT_SEARCH_RADIUS_WITHOUT_QUERY : DEFAULT_SEARCH_RADIUS_WITH_QUERY));
    routesQuery.setMaxCount(maxCount);

    try {
      RoutesBean result = _service.getRoutes(routesQuery);
      return transformResult(result);
    } catch (OutOfServiceAreaServiceException ex) {
      return transformOutOfRangeResult();
    }
  }

  private DefaultHttpHeaders transformResult(RoutesBean result) {
    if (isVersion(V1)) {
      return setOkResponse(result);
    } else if (isVersion(V2)) {
      BeanFactoryV2 factory = getBeanFactoryV2();
      return setOkResponse(factory.getResponse(result));
    } else {
      return setUnknownVersionResponse();
    }
  }

  private DefaultHttpHeaders transformOutOfRangeResult() {
    if (isVersion(V1)) {
      return setOkResponse(new RoutesBean());
    } else if (isVersion(V2)) {
      BeanFactoryV2 factory = getBeanFactoryV2();
      return setOkResponse(factory.getEmptyList(RouteV2Bean.class, true));
    } else {
      return setUnknownVersionResponse();
    }
  }
}
