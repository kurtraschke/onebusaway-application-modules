/**
 * Copyright (C) 2014 Kurt Raschke <kurt@kurtraschke.com>
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

import org.onebusaway.api.actions.api.ApiActionSupport;
import org.onebusaway.api.model.transit.AutocompleteResultV2Bean;
import org.onebusaway.api.model.transit.BeanFactoryV2;
import org.onebusaway.api.model.transit.ListWithReferencesBean;
import org.onebusaway.exceptions.ServiceException;
import org.onebusaway.transit_data.model.AutocompleteResultBean;
import org.onebusaway.transit_data.services.TransitDataService;

import com.opensymphony.xwork2.validator.annotations.RequiredFieldValidator;

import org.apache.struts2.rest.DefaultHttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

public class AutocompleteAction extends ApiActionSupport {
  private static final long serialVersionUID = 1L;

  private static final int V2 = 2;

  @Autowired
  private TransitDataService _service;

  private String _query;

  public AutocompleteAction() {
    super(V2);
  }

  @RequiredFieldValidator
  public void setQuery(String query) {
    _query = query;
  }

  public String getQuery() {
    return _query;
  }

  public DefaultHttpHeaders index() throws ServiceException {

    if (!isVersion(V2))
      return setUnknownVersionResponse();

    if (hasErrors())
      return setValidationErrorsResponse();

    List<AutocompleteResultBean> results = _service.getAutocompleteForQuery(_query);

    BeanFactoryV2 factory = getBeanFactoryV2();
    ListWithReferencesBean<AutocompleteResultV2Bean> response = factory.getAutocompleteResponse(results);
    return setOkResponse(response);
  }
}
