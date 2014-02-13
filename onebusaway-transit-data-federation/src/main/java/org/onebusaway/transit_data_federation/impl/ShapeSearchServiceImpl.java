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
import org.onebusaway.geospatial.DefaultSpatialContext;
import org.onebusaway.geospatial.model.SearchBounds;
import org.onebusaway.geospatial.services.DefaultSearchBoundsVisitor;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.ShapeSearchIndexConstants;
import org.onebusaway.transit_data_federation.services.ShapeSearchService;

import com.spatial4j.core.context.SpatialContext;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.SpatialPrefixTree;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.PostConstruct;

public class ShapeSearchServiceImpl implements ShapeSearchService {

  private static Logger _log = LoggerFactory.getLogger(ShapeSearchServiceImpl.class);

  private FederatedTransitDataBundle _bundle;

  private IndexSearcher _searcher;

  private SpatialContext _spatialContext = DefaultSpatialContext.SPATIAL_CONTEXT;

  private SpatialPrefixTree _spatialPrefixTree = new QuadPrefixTree(
      _spatialContext, ShapeSearchIndexConstants.NUM_TREE_LEVELS);

  private SpatialStrategy _spatialStrategy = new RecursivePrefixTreeStrategy(
      _spatialPrefixTree, ShapeSearchIndexConstants.FIELD_SHAPE_POINTS);

  @Autowired
  public void setBundle(FederatedTransitDataBundle bundle) {
    _bundle = bundle;
  }

  @PostConstruct
  @Refreshable(dependsOn = RefreshableResources.SHAPE_GEOSPATIAL_INDEX)
  public void initialize() throws IOException {
    File path = _bundle.getShapeSearchIndexPath();

    if (path != null && path.exists()) {
      IndexReader reader = DirectoryReader.open(FSDirectory.open(path));
      _searcher = new IndexSearcher(reader);
    } else {
      _searcher = null;
    }
  }

  @Override
  public List<AgencyAndId> search(SearchBounds bounds) {
    DefaultSearchBoundsVisitor v = new DefaultSearchBoundsVisitor();
    bounds.accept(v);

    Query spatialQuery = _spatialStrategy.makeQuery(new SpatialArgs(
        SpatialOperation.Intersects, v.getShape()));

    try {
      TopDocs top = _searcher.search(spatialQuery,
          _searcher.getIndexReader().numDocs());

      List<AgencyAndId> ids = new ArrayList<AgencyAndId>(top.totalHits);

      for (ScoreDoc sd : top.scoreDocs) {
        Document document = _searcher.doc(sd.doc);
        String agencyId = document.get(ShapeSearchIndexConstants.FIELD_AGENCY_ID);
        String shapeId = document.get(ShapeSearchIndexConstants.FIELD_SHAPE_ID);
        AgencyAndId id = new AgencyAndId(agencyId, shapeId);

        ids.add(id);
      }
      return ids;
    } catch (IOException e) {
      _log.warn("exception while searching", e);
      return Collections.emptyList();
    }
  }
}
