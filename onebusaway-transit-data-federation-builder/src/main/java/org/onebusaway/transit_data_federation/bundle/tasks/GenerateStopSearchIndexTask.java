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
import org.onebusaway.geospatial.DefaultSpatialContext;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data_federation.impl.RefreshableResources;
import org.onebusaway.transit_data_federation.impl.StopSearchServiceImpl;
import org.onebusaway.transit_data_federation.model.narrative.StopNarrative;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.SearchIndexConstants;
import org.onebusaway.transit_data_federation.services.StopSearchIndexConstants;
import org.onebusaway.transit_data_federation.services.StopSearchService;
import org.onebusaway.transit_data_federation.services.narrative.NarrativeService;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;

import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.shape.Point;

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
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.vector.PointVectorStrategy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Generate the underlying Lucene search index for stop searches that will power
 * {@link StopSearchServiceImpl} and {@link StopSearchService}.
 *
 * @author bdferris
 * @see StopSearchServiceImpl
 * @see StopSearchService
 */
@Component
public class GenerateStopSearchIndexTask implements Runnable {

  private static Logger _log = LoggerFactory.getLogger(GenerateStopSearchIndexTask.class);

  private TransitGraphDao _transitGraphDao;

  private NarrativeService _narrativeService;

  private FederatedTransitDataBundle _bundle;

  private RefreshService _refreshService;

  private SpatialContext _spatialContext = DefaultSpatialContext.SPATIAL_CONTEXT;

  private SpatialStrategy _spatialStrategy = new PointVectorStrategy(
      _spatialContext, StopSearchIndexConstants.FIELD_STOP_LOCATION);

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
      throw new IllegalStateException("error building stop search index", ex);
    }
  }

  private void buildIndex() throws IOException {
    Directory dir = FSDirectory.open(_bundle.getStopSearchIndexPath());
    Analyzer analyzer = new WhitespaceAnalyzer(SearchIndexConstants.LUCENE_VERSION);
    IndexWriterConfig iwc = new IndexWriterConfig(SearchIndexConstants.LUCENE_VERSION, analyzer);

    iwc.setOpenMode(OpenMode.CREATE);

    IndexWriter writer = new IndexWriter(dir, iwc);

    for (StopEntry stopEntry : _transitGraphDao.getAllStops()) {
      StopNarrative narrative = _narrativeService.getStopForId(stopEntry.getId());
      Document document = getStopAsDocument(stopEntry, narrative);
      writer.addDocument(document);
    }

    writer.forceMerge(1);
    writer.close();
    _refreshService.refresh(RefreshableResources.STOP_SEARCH_DATA);
  }

  private Document getStopAsDocument(StopEntry stopEntry,
      StopNarrative narrative) {

    Document document = new Document();

    // Id
    AgencyAndId id = stopEntry.getId();
    document.add(new StoredField(StopSearchIndexConstants.FIELD_AGENCY_ID,
        id.getAgencyId()));
    document.add(new StringField(StopSearchIndexConstants.FIELD_STOP_ID,
        id.getId(), Field.Store.YES));
    document.add(new StringField(StopSearchIndexConstants.FIELD_AGENCY_AND_ID,
        id.toString(), Field.Store.YES));

    // Code
    if (isValue(narrative.getCode())) {
      document.add(new StringField(StopSearchIndexConstants.FIELD_STOP_CODE,
          narrative.getCode().toLowerCase(), Field.Store.NO));
    } else {
      document.add(new StringField(StopSearchIndexConstants.FIELD_STOP_CODE,
          stopEntry.getId().getId().toLowerCase(), Field.Store.NO));
    }

    // Name
    if (isValue(narrative.getName())) {
      document.add(new TextField(StopSearchIndexConstants.FIELD_STOP_NAME,
          narrative.getName().toLowerCase(), Field.Store.NO));
    }

    // Description
    if (isValue(narrative.getDescription())) {
      document.add(new TextField(
          StopSearchIndexConstants.FIELD_STOP_DESCRIPTION,
          narrative.getDescription().toLowerCase(), Field.Store.NO));
    }

    // Location
    Point stopPoint = _spatialContext.makePoint(stopEntry.getStopLon(),
        stopEntry.getStopLat());
    for (Field f : _spatialStrategy.createIndexableFields(stopPoint)) {
      document.add(f);
    }

    return document;
  }

  private static boolean isValue(String value) {
    return value != null && value.length() > 0;
  }
}
