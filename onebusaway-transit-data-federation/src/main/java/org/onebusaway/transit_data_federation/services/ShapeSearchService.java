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

package org.onebusaway.transit_data_federation.services;

import org.onebusaway.geospatial.model.SearchBounds;
import org.onebusaway.gtfs.model.AgencyAndId;

import java.util.List;

/**
 * Service interface for finding shapes by spatial search.
 *
 * @author kurt
 *
 */
public interface ShapeSearchService {

  /**
   * Find shapes within bounds.
   *
   * @param bounds search bounds
   * @return matching shapes
   */
  public List<AgencyAndId> search(SearchBounds bounds);
}
