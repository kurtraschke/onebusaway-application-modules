/**
 * Copyright (C) 2011 Brian Ferris <bdferris@onebusaway.org>
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
import org.onebusaway.geospatial.model.CoordinateBounds;
import org.onebusaway.geospatial.model.SearchBounds;
import org.onebusaway.geospatial.services.DefaultSearchBoundsVisitor;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data_federation.model.SearchResult;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.StopSearchIndexConstants;
import org.onebusaway.transit_data_federation.services.StopSearchService;
import org.onebusaway.transit_data_federation.services.beans.GeospatialBeanService;

import com.spatial4j.core.context.SpatialContext;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.spatial.vector.PointVectorStrategy;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

@Component
public class StopSearchServiceImpl implements StopSearchService,
    GeospatialBeanService {

  private static Logger _log = LoggerFactory.getLogger(StopSearchServiceImpl.class);

  private static String[] CODE_FIELDS = {StopSearchIndexConstants.FIELD_STOP_CODE};

  private static String[] NAME_FIELDS = {
      StopSearchIndexConstants.FIELD_STOP_NAME,
      StopSearchIndexConstants.FIELD_STOP_DESCRIPTION};

  private FederatedTransitDataBundle _bundle;

  private IndexSearcher _searcher;

  private SpatialContext _spatialContext = DefaultSpatialContext.SPATIAL_CONTEXT;

  private SpatialStrategy _spatialStrategy = new PointVectorStrategy(
      _spatialContext, StopSearchIndexConstants.FIELD_STOP_LOCATION);

  @Autowired
  public void setBundle(FederatedTransitDataBundle bundle) {
    _bundle = bundle;
  }

  @PostConstruct
  @Refreshable(dependsOn = RefreshableResources.STOP_SEARCH_DATA)
  public void initialize() throws IOException {
    File path = _bundle.getStopSearchIndexPath();

    if (path != null && path.exists()) {
      IndexReader reader = DirectoryReader.open(FSDirectory.open(path));
      _searcher = new IndexSearcher(reader);
    } else {
      _searcher = null;
    }
  }

  @Override
  public SearchResult<AgencyAndId> search(String query, SearchMode mode,
      SearchBounds bounds, int maxResultCount, double minScoreToKeep)
      throws ParseException, IOException {

    if (_searcher == null) {
      return new SearchResult<AgencyAndId>();
    }

    BooleanQuery bq = null;
    SpatialArgs sa = null;
    TopDocs top;

    if (query != null && query.length() == 0) {
      query = null;
    }

    if (query != null) {
      bq = new BooleanQuery(true);

      if (mode == SearchMode.CODE_ONLY || mode == SearchMode.ALL) {
        for (String codeFieldName : CODE_FIELDS) {
          bq.add(new TermQuery(new Term(codeFieldName, query.toLowerCase())),
              BooleanClause.Occur.SHOULD);
        }
      }

      if (mode == SearchMode.NAME_ONLY || mode == SearchMode.ALL) {
        String[] queryTerms = query.toLowerCase().split(" ");
        for (String fieldName : NAME_FIELDS) {
          for (String termString : queryTerms) {
            TermQuery termQuery = new TermQuery(new Term(fieldName, termString));
            termQuery.setBoost(2.0f);
            bq.add(termQuery, Occur.SHOULD);

            FuzzyQuery fuzzyQuery = new FuzzyQuery(new Term(fieldName,
                termString), StopSearchIndexConstants.MAX_EDITS);
            bq.add(fuzzyQuery, Occur.SHOULD);
          }
        }
      }
    }

    if (bounds != null) {
      sa = new SpatialArgs(SpatialOperation.IsWithin,
          DefaultSearchBoundsVisitor.shape(bounds));
    }

    if (query != null && bounds != null) {
      Filter spatialFilter = _spatialStrategy.makeFilter(sa);

      top = _searcher.search(bq, spatialFilter, maxResultCount);
    } else if (query != null) {
      top = _searcher.search(bq, maxResultCount);
    } else if (bounds != null) {
      Query spatialQuery = _spatialStrategy.makeQuery(sa);

      top = _searcher.search(spatialQuery, maxResultCount);
    } else {
      throw new IllegalArgumentException(
          "one or more of query, bounds must be non-null");
    }

    Map<AgencyAndId, Float> topScores = new HashMap<AgencyAndId, Float>();

    for (ScoreDoc sd : top.scoreDocs) {
      Document document = _searcher.doc(sd.doc);

      if (sd.score < minScoreToKeep) {
        continue;
      }
      String agencyId = document.get(StopSearchIndexConstants.FIELD_AGENCY_ID);
      String stopId = document.get(StopSearchIndexConstants.FIELD_STOP_ID);
      AgencyAndId id = new AgencyAndId(agencyId, stopId);

      Float existingScore = topScores.get(id);
      if (existingScore == null || existingScore < sd.score)
        topScores.put(id, sd.score);
    }

    List<AgencyAndId> ids = new ArrayList<AgencyAndId>(top.totalHits);
    double[] scores = new double[top.totalHits];

    int index = 0;
    for (AgencyAndId id : topScores.keySet()) {
      ids.add(id);
      scores[index] = topScores.get(id);
      index++;
    }

    return new SearchResult<AgencyAndId>(ids, scores);

  }

  @Override
  public SearchResult<AgencyAndId> searchForStopsByCode(String id,
      int maxResultCount, double minScoreToKeep) throws IOException,
      ParseException {

    return search(id, SearchMode.CODE_ONLY, null, maxResultCount,
        minScoreToKeep);
  }

  @Override
  public SearchResult<AgencyAndId> searchForStopsByName(String name,
      int maxResultCount, double minScoreToKeep) throws IOException,
      ParseException {
    return search(name, SearchMode.NAME_ONLY, null, maxResultCount,
        minScoreToKeep);
  }

  @Override
  public List<AgencyAndId> getStopsByBounds(CoordinateBounds bounds) {
    Query spatialQuery = _spatialStrategy.makeQuery(new SpatialArgs(
        SpatialOperation.IsWithin, _spatialContext.makeRectangle(
            bounds.getMinLon(), bounds.getMaxLon(), bounds.getMinLat(),
            bounds.getMaxLat())));

    try {
      TopDocs top = _searcher.search(spatialQuery,
          _searcher.getIndexReader().numDocs());

      List<AgencyAndId> ids = new ArrayList<AgencyAndId>(top.totalHits);

      for (ScoreDoc sd : top.scoreDocs) {
        Document document = _searcher.doc(sd.doc);
        String agencyId = document.get(StopSearchIndexConstants.FIELD_AGENCY_ID);
        String stopId = document.get(StopSearchIndexConstants.FIELD_STOP_ID);
        AgencyAndId id = new AgencyAndId(agencyId, stopId);

        ids.add(id);
      }
      return ids;
    } catch (IOException e) {
      _log.warn("exception while searching", e);
      return Collections.emptyList();
    }

  }

}
