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

package org.onebusaway.transit_data_federation.impl;

import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data.model.AutocompleteResultBean;
import org.onebusaway.transit_data_federation.services.AgencyAndIdLibrary;
import org.onebusaway.transit_data_federation.services.AutocompleteService;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.beans.RouteBeanService;
import org.onebusaway.transit_data_federation.services.beans.StopBeanService;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.suggest.Lookup.LookupResult;
import org.apache.lucene.search.suggest.analyzing.BlendedInfixSuggester;
import org.apache.lucene.util.Version;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

@Component
public class AutocompleteServiceImpl implements AutocompleteService {

  private FederatedTransitDataBundle _bundle;

  private StopBeanService _stopBeanService;

  private RouteBeanService _routeBeanService;

  private BlendedInfixSuggester _suggester;

  @Autowired
  public void setBundle(FederatedTransitDataBundle bundle) {
    _bundle = bundle;
  }

  @Autowired
  public void setStopBeanService(StopBeanService stopBeanService) {
    _stopBeanService = stopBeanService;
  }

  @Autowired
  public void setRouteBeanService(RouteBeanService routeBeanService) {
    _routeBeanService = routeBeanService;
  }

  @PostConstruct
  @Refreshable(dependsOn = RefreshableResources.AUTOCOMPLETE_INDEX)
  public void initialize() throws IOException {
    File indexPath = _bundle.getAutocompleteIndexPath();

    if (indexPath.exists()) {

      Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_47);
      _suggester = new BlendedInfixSuggester(Version.LUCENE_47, indexPath,
          analyzer);
    } else {
      _suggester = null;
    }
  }

  @Override
  public List<AutocompleteResultBean> getAutocompleteForQuery(
      String autocompleteQuery) {

    List<AutocompleteResultBean> resultsOut = new ArrayList<AutocompleteResultBean>();

    List<LookupResult> results = _suggester.lookup(autocompleteQuery, false, 10);

    for (LookupResult result : results) {
      resultsOut.add(beanForResult(result));
    }

    return resultsOut;
  }

  private AutocompleteResultBean beanForResult(LookupResult result) {
    String[] parts = result.payload.utf8ToString().split(":", 2);

    String type = parts[0];
    AgencyAndId id = AgencyAndIdLibrary.convertFromString(parts[1]);

    Object payloadBean;

    if (type.equals("stop")) {
      payloadBean = _stopBeanService.getStopForId(id);
    } else if (type.equals("route")) {
      payloadBean = _routeBeanService.getRouteForId(id);
    } else {
      throw new IllegalArgumentException("unknown object type: " + type);
    }

    return new AutocompleteResultBean(result.key.toString(), id, payloadBean);

  }

}
