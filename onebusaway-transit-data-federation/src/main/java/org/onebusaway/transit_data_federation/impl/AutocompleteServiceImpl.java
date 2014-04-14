package org.onebusaway.transit_data_federation.impl;

import org.onebusaway.container.refresh.Refreshable;
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
      resultsOut.add(new AutocompleteResultBean(result.key.toString(),
          beanForPayload(result.payload.utf8ToString())));
    }

    return resultsOut;
  }

  private Object beanForPayload(String payload) {
    String[] parts = payload.split(":", 2);

    String type = parts[0];
    String id = parts[1];

    if (type.equals("stop")) {
      return _stopBeanService.getStopForId(AgencyAndIdLibrary.convertFromString(id));
    } else if (type.equals("route")) {
      return _routeBeanService.getRouteForId(AgencyAndIdLibrary.convertFromString(id));
    } else {
      throw new IllegalArgumentException("unknown object type: " + type);
    }
  }

}
