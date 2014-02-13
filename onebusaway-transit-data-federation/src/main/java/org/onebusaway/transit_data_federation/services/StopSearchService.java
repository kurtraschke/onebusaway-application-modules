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
package org.onebusaway.transit_data_federation.services;

import org.onebusaway.geospatial.model.SearchBounds;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.transit_data_federation.model.SearchResult;

import org.apache.lucene.queryparser.classic.ParseException;

import java.io.IOException;

/**
 * Service interface for searching for {@link Stop} ids by stop code and stop
 * name.
 *
 * @author bdferris
 */
public interface StopSearchService {

  public enum SearchMode {
    CODE_ONLY, NAME_ONLY, ALL
  }

  /**
   * Search for stop ids by stop code (see {@link Stop#getCode()}). Typically
   * default to a search against {@link Stop#getId()} if no code is specified
   * for a stop.
   *
   * @param code the stop code query
   * @param maxResultCount maximum number of results to return
   * @param minScoreToKeep implementation-specific score cutoff for search
   *          results
   * @return a search result for matching stop ids
   * @throws IOException
   * @throws ParseException
   */
  @Deprecated
  public SearchResult<AgencyAndId> searchForStopsByCode(String code,
      int maxResultCount, double minScoreToKeep) throws IOException,
      ParseException;

  /**
   * Search for stop ids by stop name (see {@link Stop#getName()})
   *
   * @param code the stop name query
   * @param maxResultCount maximum number of results to return
   * @param minScoreToKeep implementation-specific score cutoff for search
   *          results
   * @return a search result for matching stop ids
   * @throws IOException
   * @throws ParseException
   */
  @Deprecated
  public SearchResult<AgencyAndId> searchForStopsByName(String name,
      int maxResultCount, double minScoreToKeep) throws IOException,
      ParseException;

  /**
   * Search for stops, by some combination of name, code, id, and location.
   *
   *
   * @param query search query
   * @param mode {@link SearchMode} specifiying the fields to search; may be
   *          <code>null</code> if <code>query</code> is also <code>null</code>
   * @param bounds bounds for stop search, or <code>null</code> for unbounded
   *          search
   * @param maxResultCount maximum number of results to return
   * @param minScoreToKeep implementation-specific score cutoff for search
   *          results
   * @return
   * @throws ParseException
   * @throws IOException
   */

  public SearchResult<AgencyAndId> search(String query, SearchMode mode,
      SearchBounds bounds, int maxResultCount, double minScoreToKeep)
      throws ParseException, IOException;
}
