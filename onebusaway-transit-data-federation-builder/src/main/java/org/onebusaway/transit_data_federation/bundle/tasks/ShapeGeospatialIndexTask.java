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
import org.onebusaway.transit_data_federation.model.ShapePoints;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.ShapeSearchIndexConstants;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;
import org.onebusaway.transit_data_federation.services.transit_graph.TripEntry;

import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.shape.Point;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.SpatialPrefixTree;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
class ShapeGeospatialIndexTask implements Runnable {

  private static Logger _log = LoggerFactory.getLogger(ShapeGeospatialIndexTask.class);

  private TransitGraphDao _transitGraphDao;

  private ShapePointHelper _shapePointHelper;

  private FederatedTransitDataBundle _bundle;

  private RefreshService _refreshService;

  private SpatialContext _spatialContext = DefaultSpatialContext.SPATIAL_CONTEXT;

  private SpatialPrefixTree _spatialPrefixTree = new QuadPrefixTree(
      _spatialContext, ShapeSearchIndexConstants.NUM_TREE_LEVELS);

  private SpatialStrategy _spatialStrategy = new RecursivePrefixTreeStrategy(
      _spatialPrefixTree, ShapeSearchIndexConstants.FIELD_SHAPE_POINTS);

  @Autowired
  public void setTransitGraphDao(TransitGraphDao transitGraphDao) {
    _transitGraphDao = transitGraphDao;
  }

  @Autowired
  public void setShapePointHelper(ShapePointHelper shapePointHelper) {
    _shapePointHelper = shapePointHelper;
  }

  @Autowired
  public void setRefreshService(RefreshService refreshService) {
    _refreshService = refreshService;
  }

  @Autowired
  public void setBundle(FederatedTransitDataBundle bundle) {
    _bundle = bundle;
  }

  @Override
  public void run() {
    try {
      buildIndex();
    } catch (Exception ex) {
      throw new IllegalStateException(
          "error creating shape geospatial index data", ex);
    }
  }

  /****
   * Private Methods
   ****/

  private void buildIndex() throws IOException {
    Directory dir = FSDirectory.open(_bundle.getShapeSearchIndexPath());
    Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_47);
    IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_47, analyzer);

    iwc.setOpenMode(OpenMode.CREATE);

    IndexWriter writer = new IndexWriter(dir, iwc);

    for (AgencyAndId shapeId : getAllShapeIds()) {
      ShapePoints shapePoints = _shapePointHelper.getShapePointsForShapeId(shapeId);
      Document document = getShapeAsDocument(shapeId, shapePoints);
      writer.addDocument(document);
    }

    writer.forceMerge(1);
    writer.close();
    _refreshService.refresh(RefreshableResources.SHAPE_GEOSPATIAL_INDEX);
  }

  private Document getShapeAsDocument(AgencyAndId shapeId,
      ShapePoints shapePoints) {
    Document document = new Document();

    document.add(new StoredField(ShapeSearchIndexConstants.FIELD_AGENCY_ID,
        shapeId.getAgencyId()));
    document.add(new StringField(ShapeSearchIndexConstants.FIELD_SHAPE_ID,
        shapeId.getId(), Field.Store.YES));

    for (Field f : _spatialStrategy.createIndexableFields(_spatialContext.makeLineString(shapePointsToPointList(shapePoints)))) {
      document.add(f);
    }

    return document;
  }

  private List<Point> shapePointsToPointList(ShapePoints shapePoints) {
    double[] lats = shapePoints.getLats();
    double[] lons = shapePoints.getLons();

    List<Point> points = new ArrayList<Point>(shapePoints.getSize());

    for (int i = 0; i < shapePoints.getSize(); i++) {
      Point stopPoint = _spatialContext.makePoint(lons[i], lats[i]);
      points.add(stopPoint);
    }

    return points;
  }

  private Set<AgencyAndId> getAllShapeIds() {
    Set<AgencyAndId> shapeIds = new HashSet<AgencyAndId>();

    for (TripEntry trip : _transitGraphDao.getAllTrips()) {
      AgencyAndId shapeId = trip.getShapeId();
      if (shapeId != null)
        shapeIds.add(shapeId);
    }

    return shapeIds;
  }
}
