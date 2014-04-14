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

package org.onebusaway.transit_data_federation.bundle.tasks;

import org.onebusaway.container.refresh.RefreshService;
import org.onebusaway.transit_data_federation.impl.RefreshableResources;
import org.onebusaway.transit_data_federation.model.narrative.RouteCollectionNarrative;
import org.onebusaway.transit_data_federation.model.narrative.StopNarrative;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.narrative.NarrativeService;
import org.onebusaway.transit_data_federation.services.transit_graph.RouteCollectionEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.analyzing.BlendedInfixSuggester;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class GenerateAutocompleteIndexTask implements Runnable {

  private static Logger _log = LoggerFactory.getLogger(GenerateAutocompleteIndexTask.class);

  private TransitGraphDao _transitGraphDao;

  private NarrativeService _narrativeService;

  private FederatedTransitDataBundle _bundle;

  private RefreshService _refreshService;

  @Autowired
  public void setTransitGraphDao(TransitGraphDao transitGraphDao) {
    _transitGraphDao = transitGraphDao;
  }

  @Autowired
  public void setNarrativeService(NarrativeService narrativeService) {
    _narrativeService = narrativeService;
  }

  @Autowired
  public void setBundle(FederatedTransitDataBundle bundle) {
    _bundle = bundle;
  }

  @Autowired
  public void setRefreshService(RefreshService refreshService) {
    _refreshService = refreshService;
  }

  @Override
  public void run() {
    try {
      buildIndex();
    } catch (Exception ex) {
      throw new IllegalStateException("error building autocomplete index", ex);
    }
  }

  private void buildIndex() throws IOException {
    File indexPath = _bundle.getAutocompleteIndexPath();
    Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_47);
    BlendedInfixSuggester suggester = new BlendedInfixSuggester(
        Version.LUCENE_47, indexPath, analyzer);

    suggester.build(new TransitGraphAutocompleteIterator());
    suggester.close();
    _refreshService.refresh(RefreshableResources.AUTOCOMPLETE_INDEX);
  }

  private class TransitGraphAutocompleteIterator implements InputIterator {

    private final List<InputEntry> inputEntries = new ArrayList<InputEntry>();

    private final Iterator<InputEntry> inputEntryIterator;

    private InputEntry current;

    public TransitGraphAutocompleteIterator() {

      for (StopEntry stop : _transitGraphDao.getAllStops()) {
        String payload = "stop:" + stop.getId();
        StopNarrative stopNarrative = _narrativeService.getStopForId(stop.getId());

        if (isValue(stopNarrative.getCode())) {
          inputEntries.add(new InputEntry(stopNarrative.getCode(), 4, payload));
          inputEntries.add(new InputEntry(stop.getId().getId(), 1, payload));
        } else {
          inputEntries.add(new InputEntry(stop.getId().getId(), 2, payload));
        }

        if (isValue(stopNarrative.getName())) {
          inputEntries.add(new InputEntry(stopNarrative.getName(), 2, payload));
        }
      }

      for (RouteCollectionEntry routeCollection : _transitGraphDao.getAllRouteCollections()) {
        String payload = "route:" + routeCollection.getId();
        RouteCollectionNarrative routeCollectionNarrative = _narrativeService.getRouteCollectionForId(routeCollection.getId());

        if (isValue(routeCollectionNarrative.getShortName())) {
          inputEntries.add(new InputEntry(
              routeCollectionNarrative.getShortName(), 4, payload));

          if (isValue(routeCollectionNarrative.getLongName())) {
            inputEntries.add(new InputEntry(
                routeCollectionNarrative.getLongName(), 2, payload));
          }

        } else if (isValue(routeCollectionNarrative.getLongName())) {
          inputEntries.add(new InputEntry(
              routeCollectionNarrative.getLongName(), 4, payload));
        }
      }

      inputEntryIterator = inputEntries.iterator();
    }

    @Override
    public Comparator<BytesRef> getComparator() {
      return null;
    }

    @Override
    public BytesRef next() throws IOException {
      if (inputEntryIterator.hasNext()) {
        current = inputEntryIterator.next();
        return current.entry;
      } else {
        return null;
      }
    }

    @Override
    public boolean hasPayloads() {
      return true;
    }

    //TODO: make robust
    @Override
    public BytesRef payload() {
      return current.payload;
    }

    //TODO: make robust
    @Override
    public long weight() {
      return current.weight;
    }

  }

  private static class InputEntry {
    public BytesRef entry;
    public long weight;
    public BytesRef payload;

    public InputEntry(String entry, long weight, String payload) {
      this.entry = new BytesRef(entry);
      this.weight = weight;
      this.payload = new BytesRef(payload);
    }
  }

  private static boolean isValue(String value) {
    return value != null && value.length() > 0;
  }

}
