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
package org.onebusaway.transit_data_federation.bundle.tasks;

import org.onebusaway.container.refresh.RefreshService;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data_federation.impl.RefreshableResources;
import org.onebusaway.transit_data_federation.impl.RouteCollectionSearchServiceImpl;
import org.onebusaway.transit_data_federation.model.narrative.RouteCollectionNarrative;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.RouteCollectionSearchIndexConstants;
import org.onebusaway.transit_data_federation.services.RouteCollectionSearchService;
import org.onebusaway.transit_data_federation.services.narrative.NarrativeService;
import org.onebusaway.transit_data_federation.services.transit_graph.RouteCollectionEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.RouteEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopTimeEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;
import org.onebusaway.transit_data_federation.services.transit_graph.TripEntry;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Generate the underlying Lucene search index for route collection searches
 * that will power {@link RouteCollectionSearchServiceImpl} and
 * {@link RouteCollectionSearchService}.
 *
 * @author bdferris
 * @see RouteCollectionSearchService
 * @see RouteCollectionSearchServiceImpl
 */
@Component
public class GenerateRouteCollectionSearchIndexTask implements Runnable {

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
  @Transactional
  public void run() {
    try {
      buildIndex();
    } catch (Exception ex) {
      throw new IllegalStateException("error building route search index", ex);
    }
  }

  private void buildIndex() throws IOException {
    Directory dir = FSDirectory.open(_bundle.getRouteSearchIndexPath());
    Analyzer analyzer = new WhitespaceAnalyzer(Version.LUCENE_47);
    IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_47, analyzer);

    iwc.setOpenMode(OpenMode.CREATE);

    IndexWriter writer = new IndexWriter(dir, iwc);

    for (RouteCollectionEntry routeCollection : _transitGraphDao.getAllRouteCollections()) {
      RouteCollectionNarrative narrative = _narrativeService.getRouteCollectionForId(routeCollection.getId());
      Document document = getRouteCollectionAsDocument(routeCollection,
          narrative);
      writer.addDocument(document);
    }

    writer.forceMerge(1);
    writer.close();

    _refreshService.refresh(RefreshableResources.ROUTE_COLLECTION_SEARCH_DATA);
  }

  private Document getRouteCollectionAsDocument(
      RouteCollectionEntry routeCollection, RouteCollectionNarrative narrative) {

    AgencyAndId routeCollectionId = routeCollection.getId();

    Document document = new Document();

    // Route Collection
    document.add(new StoredField(
        RouteCollectionSearchIndexConstants.FIELD_ROUTE_COLLECTION_AGENCY_ID,
        routeCollectionId.getAgencyId()));

    document.add(new StoredField(
        RouteCollectionSearchIndexConstants.FIELD_ROUTE_COLLECTION_ID,
        routeCollectionId.getId()));

    // Short name
    if (isValue(narrative.getShortName())) {
      Field f = new TextField(
          RouteCollectionSearchIndexConstants.FIELD_ROUTE_SHORT_NAME,
          narrative.getShortName().toLowerCase(), Field.Store.NO);
      document.add(f);
    }

    // Long name
    if (isValue(narrative.getLongName())) {
      Field f = new TextField(
          RouteCollectionSearchIndexConstants.FIELD_ROUTE_LONG_NAME,
          narrative.getLongName().toLowerCase(), Field.Store.NO);
      document.add(f);
    }

    // Description
    if (isValue(narrative.getDescription())) {
      document.add(new TextField(
          RouteCollectionSearchIndexConstants.FIELD_ROUTE_DESCRIPTION,
          narrative.getDescription().toLowerCase(), Field.Store.NO));
    }

    // Stops
    Set<StopEntry> routeCollectionStops = new HashSet<StopEntry>();

    for (RouteEntry route : routeCollection.getChildren()) {
      for (TripEntry trip : route.getTrips()) {
        for (StopTimeEntry st : trip.getStopTimes()) {
          routeCollectionStops.add(st.getStop());
        }
      }
    }

    for (StopEntry stop : routeCollectionStops) {
      document.add(new StringField(
          RouteCollectionSearchIndexConstants.FIELD_ROUTE_STOP_AGENCY_AND_ID,
          stop.getId().toString(), Field.Store.YES));
    }

    return document;
  }

  private static boolean isValue(String value) {
    return value != null && value.length() > 0;
  }
}
