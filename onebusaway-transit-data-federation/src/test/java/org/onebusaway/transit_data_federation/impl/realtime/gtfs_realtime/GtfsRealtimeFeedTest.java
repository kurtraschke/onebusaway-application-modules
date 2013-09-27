/**
 * Copyright (C) 2013 Kurt Raschke
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
package org.onebusaway.transit_data_federation.impl.realtime.gtfs_realtime;

import static org.junit.Assert.assertEquals;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporterModule;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.VehiclePositions;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeIncrementalUpdate;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeServlet;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeSink;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeSource;
import org.onebusaway.guice.jsr250.JSR250Module;
import org.onebusaway.guice.jsr250.LifecycleService;
import org.onebusaway.transit_data_federation.services.realtime.gtfs_realtime.GtfsRealtimeEntityListener;
import org.onebusaway.transit_data_federation.services.realtime.gtfs_realtime.GtfsRealtimeFeed;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

/**
 *
 * @author kurt
 */
public class GtfsRealtimeFeedTest {

    private GtfsRealtimeProducerDemo _producer;
    private static Set<FeedEntity> _testEntities;

    @Before
    public void before() throws MalformedURLException {
        Set<FeedEntity> entities = new HashSet<FeedEntity>();
        for (int i = 0; i < 10; i++) {
            FeedEntity.Builder entity = FeedEntity.newBuilder();
            entity.setId("v" + i);
            VehiclePosition.Builder vehicle = VehiclePosition.newBuilder();
            entity.setVehicle(vehicle);
            entities.add(entity.build());
        }

        _testEntities = Collections.unmodifiableSet(entities);

        _producer = new GtfsRealtimeProducerDemo();
        _producer.start();
    }

    @After
    public void after() {
        _producer.stop();
    }

    public void testEndpoint(URI endpoint) throws URISyntaxException, InterruptedException {
        final CountDownLatch lock = new CountDownLatch(_testEntities.size());
        final Map<String, FeedEntity> entities = new HashMap<String, FeedEntity>();

        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
        HttpClientConnectionManager connectionManager = new BasicHttpClientConnectionManager();

        GtfsRealtimeFeed feed = new GtfsRealtimeFeedImpl(endpoint,
                30,
                new GtfsRealtimeEntityListener() {
            @Override
            public void handleNewFeedEntity(FeedEntity fe) {
                System.out.println("Added: " + fe.getId());
                entities.put(fe.getId(), fe);
                lock.countDown();

            }

            @Override
            public void handleDeletedFeedEntity(FeedEntity fe) {
                System.out.println("Deleted: " + fe.getId());
                entities.remove(fe.getId());
                lock.countDown();

            }
        },
                ses,
                connectionManager);
        feed.start();

        lock.await();
        feed.stop();
        ses.shutdown();
        connectionManager.shutdown();

        assertEquals(new HashSet(entities.values()), _testEntities);
    }

    @Test(timeout = 2000)
    public void testStreaming() throws URISyntaxException, InterruptedException {
        testEndpoint(new URI("ws://localhost:8080/trip-updates"));
    }

    @Test(timeout = 1000)
    public void testPolling() throws URISyntaxException, InterruptedException {
        testEndpoint(new URI("http://localhost:8080/trip-updates"));

    }

    @Singleton
    public static class VehiclePositionsProducer {

        private GtfsRealtimeSink _vehiclePositionsSink;

        @Inject
        public void setVehiclePositionsSink(@VehiclePositions GtfsRealtimeSink vehiclePositionsSink) {
            _vehiclePositionsSink = vehiclePositionsSink;
        }

        @PostConstruct
        public void start() {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    runLoop();
                }
            });
        }

        private void runLoop() {
            for (FeedEntity fe : _testEntities) {
                GtfsRealtimeIncrementalUpdate update = new GtfsRealtimeIncrementalUpdate();

                update.addUpdatedEntity(fe);
                _vehiclePositionsSink.handleIncrementalUpdate(update);
            }
        }
    }

    public static class GtfsRealtimeProducerDemo {

        private GtfsRealtimeSource _vehiclePositionsSource;
        private LifecycleService _lifecycleService;

        @Inject
        public void setVehiclePositionsProducer(VehiclePositionsProducer producer) {
            // This is just here to make sure VehiclePositionsProducer gets instantiated.
        }

        @Inject
        public void setVehiclePositionsSource(@VehiclePositions GtfsRealtimeSource vehiclePositionsSource) {
            _vehiclePositionsSource = vehiclePositionsSource;
        }

        @Inject
        public void setLifecycleService(LifecycleService lifecycleService) {
            _lifecycleService = lifecycleService;
        }

        public void start() throws MalformedURLException {
            Set<Module> modules = new HashSet<Module>();
            GtfsRealtimeExporterModule.addModuleAndDependencies(modules);
            JSR250Module.addModuleAndDependencies(modules);
            Injector injector = Guice.createInjector(modules);
            injector.injectMembers(this);

            GtfsRealtimeServlet servlet = injector.getInstance(GtfsRealtimeServlet.class);
            servlet.setSource(_vehiclePositionsSource);
            servlet.setUrl(new URL("http://localhost:8080/trip-updates"));

            _lifecycleService.start();
        }

        public void stop() {
            _lifecycleService.stop();
        }
    }
}
