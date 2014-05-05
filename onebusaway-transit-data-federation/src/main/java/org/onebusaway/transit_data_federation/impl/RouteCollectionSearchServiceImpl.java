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
package org.onebusaway.transit_data_federation.impl;

import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.geospatial.DefaultSpatialContext;
import org.onebusaway.geospatial.model.SearchBounds;
import org.onebusaway.geospatial.services.DefaultSearchBoundsVisitor;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data_federation.model.SearchResult;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.RouteCollectionSearchIndexConstants;
import org.onebusaway.transit_data_federation.services.RouteCollectionSearchService;
import org.onebusaway.transit_data_federation.services.StopSearchIndexConstants;

import com.spatial4j.core.context.SpatialContext;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryWrapperFilter;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.join.JoinUtil;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.spatial.vector.PointVectorStrategy;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

@Component
public class RouteCollectionSearchServiceImpl implements
    RouteCollectionSearchService {

  private static String[] NAME_FIELDS = {
      RouteCollectionSearchIndexConstants.FIELD_ROUTE_SHORT_NAME,
      RouteCollectionSearchIndexConstants.FIELD_ROUTE_LONG_NAME};

  private FederatedTransitDataBundle _bundle;

  private IndexSearcher _routeSearcher;

  private IndexSearcher _stopSearcher;

  private SpatialContext _spatialContext = DefaultSpatialContext.SPATIAL_CONTEXT;

  private SpatialStrategy _spatialStrategy = new PointVectorStrategy(
      _spatialContext, StopSearchIndexConstants.FIELD_STOP_LOCATION);

  @Autowired
  public void setBundle(FederatedTransitDataBundle bundle) {
    _bundle = bundle;
  }

  @PostConstruct
  @Refreshable(dependsOn = {
      RefreshableResources.ROUTE_COLLECTION_SEARCH_DATA,
      RefreshableResources.STOP_SEARCH_DATA})
  public void initialize() throws IOException {

    File routeIndexPath = _bundle.getRouteSearchIndexPath();
    File stopIndexPath = _bundle.getStopSearchIndexPath();

    if (routeIndexPath != null && routeIndexPath.exists()) {
      IndexReader reader = DirectoryReader.open(FSDirectory.open(routeIndexPath));
      _routeSearcher = new IndexSearcher(reader);
    } else {
      _routeSearcher = null;
    }

    if (stopIndexPath != null && stopIndexPath.exists()) {
      IndexReader reader = DirectoryReader.open(FSDirectory.open(stopIndexPath));
      _stopSearcher = new IndexSearcher(reader);
    } else {
      _stopSearcher = null;
    }

  }

  @Override
  public SearchResult<AgencyAndId> search(String query, SearchBounds bounds,
      int maxResultCount, double minScoreToKeep) throws IOException,
      ParseException {

    BooleanQuery searchQuery = null;
    Query spatialJoinQuery = null;
    TopDocs top;

    if (_routeSearcher == null) {
      return new SearchResult<AgencyAndId>();
    }

    if (query != null && query.length() == 0) {
      query = null;
    }

    if (query != null) {
      searchQuery = new BooleanQuery(true);
      String[] queryTerms = query.toLowerCase().split(" ");

      for (String fieldName : NAME_FIELDS) {
        for (String termString : queryTerms) {
          TermQuery termQuery = new TermQuery(new Term(fieldName, termString));
          termQuery.setBoost(2.0f);
          searchQuery.add(termQuery, Occur.SHOULD);

          FuzzyQuery fuzzyQuery = new FuzzyQuery(
              new Term(fieldName, termString),
              RouteCollectionSearchIndexConstants.MAX_EDITS);
          searchQuery.add(fuzzyQuery, Occur.SHOULD);
        }
      }
    }

    if (_stopSearcher != null && bounds != null) {
      SpatialArgs sa = new SpatialArgs(SpatialOperation.IsWithin,
          DefaultSearchBoundsVisitor.shape(bounds));

      Query spatialQuery = _spatialStrategy.makeQuery(sa);

      spatialJoinQuery = JoinUtil.createJoinQuery(
          StopSearchIndexConstants.FIELD_AGENCY_AND_ID, false,
          RouteCollectionSearchIndexConstants.FIELD_ROUTE_STOP_AGENCY_AND_ID,
          spatialQuery, _stopSearcher, ScoreMode.Max);
    }

    if (searchQuery != null && spatialJoinQuery != null) {
      top = _routeSearcher.search(searchQuery, new QueryWrapperFilter(
          spatialJoinQuery), maxResultCount);
    } else if (searchQuery != null) {
      top = _routeSearcher.search(searchQuery, maxResultCount);
    } else if (spatialJoinQuery != null) {
      top = _routeSearcher.search(spatialJoinQuery, maxResultCount);
    } else {
      throw new IllegalArgumentException(
          "one or more of query, bounds must be non-null");
    }

    Map<AgencyAndId, Float> topScores = new HashMap<AgencyAndId, Float>();

    for (ScoreDoc sd : top.scoreDocs) {
      Document document = _routeSearcher.doc(sd.doc);

      // Result must have a minimum score to qualify
      if (sd.score < minScoreToKeep) {
        continue;
      }

      // Keep the best score for a particular id
      String agencyId = document.get(RouteCollectionSearchIndexConstants.FIELD_ROUTE_COLLECTION_AGENCY_ID);
      String id = document.get(RouteCollectionSearchIndexConstants.FIELD_ROUTE_COLLECTION_ID);
      AgencyAndId routeId = new AgencyAndId(agencyId, id);
      Float score = topScores.get(routeId);
      if (score == null || score < sd.score) {
        topScores.put(routeId, sd.score);
      }
    }

    List<AgencyAndId> ids = new ArrayList<AgencyAndId>(topScores.size());
    double[] scores = new double[topScores.size()];

    int index = 0;
    for (AgencyAndId id : topScores.keySet()) {
      ids.add(id);
      scores[index] = topScores.get(id);
      index++;
    }

    return new SearchResult<AgencyAndId>(ids, scores);

  }

  @Override
  public SearchResult<AgencyAndId> searchForRoutesByName(String value,
      int maxResultCount, double minScoreToKeep) throws IOException,
      ParseException {
    return search(value, null, maxResultCount, minScoreToKeep);
  }

}
