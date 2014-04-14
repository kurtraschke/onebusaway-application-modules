package org.onebusaway.transit_data_federation.services;

import org.onebusaway.transit_data.model.AutocompleteResultBean;

import java.util.List;

public interface AutocompleteService {

  public List<AutocompleteResultBean> getAutocompleteForQuery(String autocompleteQuery);

}
