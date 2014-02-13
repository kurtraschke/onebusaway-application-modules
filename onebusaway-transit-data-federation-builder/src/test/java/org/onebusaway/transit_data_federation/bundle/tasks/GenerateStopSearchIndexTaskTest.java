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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.stop;

import org.onebusaway.container.refresh.RefreshService;
import org.onebusaway.geospatial.model.CoordinateBounds;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data_federation.impl.StopSearchServiceImpl;
import org.onebusaway.transit_data_federation.impl.transit_graph.StopEntryImpl;
import org.onebusaway.transit_data_federation.model.SearchResult;
import org.onebusaway.transit_data_federation.model.narrative.StopNarrative;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.narrative.NarrativeService;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;

import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.queryparser.classic.ParseException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class GenerateStopSearchIndexTaskTest {

  private static final double MIN_SCORE = 1.0;

  private GenerateStopSearchIndexTask _task;

  private TransitGraphDao _transitGraphDao;

  private NarrativeService _narrativeService;

  private FederatedTransitDataBundle _bundle;

  private RefreshService _refreshService;

  @Before
  public void setup() throws IOException {

    _task = new GenerateStopSearchIndexTask();

    _bundle = Mockito.mock(FederatedTransitDataBundle.class);
    _task.setBundle(_bundle);

    File path = File.createTempFile(
        GenerateStopSearchIndexTask.class.getName(), ".tmp");
    path.delete();
    path.deleteOnExit();
    Mockito.when(_bundle.getStopSearchIndexPath()).thenReturn(path);

    _transitGraphDao = Mockito.mock(TransitGraphDao.class);
    _task.setTransitGraphDao(_transitGraphDao);

    _narrativeService = Mockito.mock(NarrativeService.class);
    _task.setNarrativeService(_narrativeService);

    _refreshService = Mockito.mock(RefreshService.class);
    _task.setRefreshService(_refreshService);
  }

  @Test
  public void testGenerateStopSearchIndex() throws CorruptIndexException,
      IOException, ParseException {

    StopEntryImpl stopA = stop("921", -0.5, -0.5);
    StopEntryImpl stopB = stop("921S", -0.5, 0.5);
    StopEntryImpl stopC = stop("333", 0.5, -0.5);

    StopNarrative.Builder stopNarrativeA = StopNarrative.builder();
    stopNarrativeA.setCode("921");
    stopNarrativeA.setName("AAA Station");

    StopNarrative.Builder stopNarrativeB = StopNarrative.builder();
    stopNarrativeB.setCode("921S");
    stopNarrativeB.setName("BBB Station");

    StopNarrative.Builder stopNarrativeC = StopNarrative.builder();
    stopNarrativeC.setCode("444");
    stopNarrativeC.setName("CCC Station");

    Mockito.when(_transitGraphDao.getAllStops()).thenReturn(
        Arrays.asList((StopEntry) stopA, stopB, stopC));

    Mockito.when(_narrativeService.getStopForId(stopA.getId())).thenReturn(
        stopNarrativeA.create());
    Mockito.when(_narrativeService.getStopForId(stopB.getId())).thenReturn(
        stopNarrativeB.create());
    Mockito.when(_narrativeService.getStopForId(stopC.getId())).thenReturn(
        stopNarrativeC.create());

    _task.run();

    StopSearchServiceImpl searchService = new StopSearchServiceImpl();
    searchService.setBundle(_bundle);
    searchService.initialize();
    SearchResult<AgencyAndId> ids = searchService.searchForStopsByCode("921", 10, MIN_SCORE);
    assertEquals(1, ids.size());
    assertEquals(new AgencyAndId("1", "921"), ids.getResult(0));

    ids = searchService.searchForStopsByCode("921S", 10, MIN_SCORE);
    assertEquals(1, ids.size());
    assertTrue(ids.getResults().contains(new AgencyAndId("1", "921S")));

    ids = searchService.searchForStopsByCode("333", 10, MIN_SCORE);
    assertEquals(0, ids.size());

    ids = searchService.searchForStopsByCode("444", 10, MIN_SCORE);
    assertEquals(1, ids.size());
    assertTrue(ids.getResults().contains(new AgencyAndId("1", "333")));

    List<AgencyAndId> stops = searchService.getStopsByBounds(new CoordinateBounds(
        -1, -1, 0, 0));
    assertEquals(1, stops.size());
    assertTrue(stops.contains(stopA.getId()));

    stops = searchService.getStopsByBounds(new CoordinateBounds(0, -1, 1, 0));
    assertEquals(1, stops.size());
    assertTrue(stops.contains(stopC.getId()));

    stops = searchService.getStopsByBounds(new CoordinateBounds(-1, -1, 1, 0));
    assertEquals(2, stops.size());
    assertTrue(stops.contains(stopA.getId()));
    assertTrue(stops.contains(stopC.getId()));

    stops = searchService.getStopsByBounds(new CoordinateBounds(-1, -1, 1, 1));
    assertEquals(3, stops.size());
    assertTrue(stops.contains(stopA.getId()));
    assertTrue(stops.contains(stopB.getId()));
    assertTrue(stops.contains(stopC.getId()));

    stops = searchService.getStopsByBounds(new CoordinateBounds(0.8, 0.8, 1, 1));
    assertEquals(0, stops.size());
  }
}
