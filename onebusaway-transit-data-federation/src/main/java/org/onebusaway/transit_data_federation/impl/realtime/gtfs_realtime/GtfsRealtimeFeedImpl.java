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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClients;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.onebusaway.transit_data_federation.services.realtime.gtfs_realtime.GtfsRealtimeEntityListener;
import org.onebusaway.transit_data_federation.services.realtime.gtfs_realtime.GtfsRealtimeFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ExtensionRegistry;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtimeNYCT;
import com.google.transit.realtime.GtfsRealtimeOneBusAway;

/**
 *
 * @author kurt
 */
public class GtfsRealtimeFeedImpl implements GtfsRealtimeFeed {

    private static final Logger _log = LoggerFactory.getLogger(GtfsRealtimeFeedImpl.class);
    private URI _endpoint;
    private int _refreshInterval;
    private GtfsRealtimeEntityListener _entityListener;
    private boolean _isPush;
    private WebSocketClient _webSocketClient;
    private GtfsRealtimeClientSocket _socket;
    private Map<String, FeedEntity> _feedEntityById = new ConcurrentHashMap<String, FeedEntity>();
    private ScheduledExecutorService _scheduledExecutorService;
    private ScheduledFuture<?> _refreshTask;
    private HttpClientConnectionManager _connectionManager;
    private CloseableHttpClient _httpClient;
    private long lastUpdateIndex = -1;
    private static final ExtensionRegistry _registry = ExtensionRegistry.newInstance();

    static {
        _registry.add(GtfsRealtimeOneBusAway.obaFeedEntity);
        _registry.add(GtfsRealtimeOneBusAway.obaTripUpdate);
        _registry.add(GtfsRealtimeOneBusAway.obaFeedHeader);
        _registry.add(GtfsRealtimeNYCT.nyctFeedHeader);
        _registry.add(GtfsRealtimeNYCT.nyctStopTimeUpdate);
        _registry.add(GtfsRealtimeNYCT.nyctTripDescriptor);
    }

    public GtfsRealtimeFeedImpl(URI endpoint, int refreshInterval,
            GtfsRealtimeEntityListener entityListener,
            ScheduledExecutorService scheduledExecutorService,
            HttpClientConnectionManager connectionManager) {
        _endpoint = endpoint;
        _refreshInterval = refreshInterval;
        _isPush = _endpoint.getScheme().equals("ws") || _endpoint.getScheme().equals("wss");
        _entityListener = entityListener;
        _scheduledExecutorService = scheduledExecutorService;
        _connectionManager = connectionManager;
    }

    @Override
    public void start() {
        if (!_isPush && _refreshInterval > 0) {
            CacheConfig cacheConfig = CacheConfig.custom().setSharedCache(false).build();

            _httpClient = CachingHttpClients.custom()
                    .setCacheConfig(cacheConfig)
                    .setConnectionManager(_connectionManager)
                    .build();

            _refreshTask = _scheduledExecutorService.scheduleAtFixedRate(
                    new GtfsRealtimeFeedImpl.RefreshTask(), 0, _refreshInterval, TimeUnit.SECONDS);
        } else if (_isPush) {
            startPush();
        }
    }

    @Override
    public void stop() {
        if (_refreshTask != null) {
            _refreshTask.cancel(true);
            _refreshTask = null;
        }

        try {
            if (_socket != null) {
                _socket.close();
                _socket = null;
            }
        } catch (Exception e) {
            _log.error("Exception stopping WebSocket connection", e);
        }

        try {
            if (_webSocketClient != null) {
                _webSocketClient.stop();
                _webSocketClient = null;
            }
        } catch (Exception e) {
            _log.error("Exception stopping WebSocket client", e);
        }
    }

    private void startPush() {
        _webSocketClient = new WebSocketClient();
        _socket = new GtfsRealtimeClientSocket();
        try {
            _webSocketClient.start();
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            _webSocketClient.connect(_socket, _endpoint, request);
            _log.debug(String.format("Connecting to : %s%n", _endpoint));
        } catch (Throwable t) {
            /*
             * If something went wrong (endpoint down, etc.), try to set up again
             * after _refreshInterval seconds.
             */
            _log.error("Error starting WebSocket client", t);
            _scheduledExecutorService.schedule(new RestartTask(), _refreshInterval, TimeUnit.SECONDS);
        }
    }

    private void handleFeedMessage(FeedMessage fm) {
        FeedHeader fh = fm.getHeader();
        
        if (fh.getIncrementality() == FeedHeader.Incrementality.DIFFERENTIAL
                && fh.hasExtension(GtfsRealtimeOneBusAway.obaFeedHeader)) {
            GtfsRealtimeOneBusAway.OneBusAwayFeedHeader ofh = fh.getExtension(GtfsRealtimeOneBusAway.obaFeedHeader);

            if (ofh.hasIncrementalIndex()) {
                long thisUpdateIndex = ofh.getIncrementalIndex();

                if (lastUpdateIndex > 0 && lastUpdateIndex + 1 != thisUpdateIndex) {
                    _log.warn("Missed incremental update detected (expected "
                            + (lastUpdateIndex + 1) + ", got " + thisUpdateIndex + ") ; restarting.");
                    _scheduledExecutorService.schedule(new RestartTask(), _refreshInterval, TimeUnit.SECONDS);
                } else {
                    lastUpdateIndex = thisUpdateIndex;
                }
            }
        }
        
        Set<String> startingEntities = _feedEntityById.keySet();
        Set<String> newEntities = new HashSet<String>();

        for (FeedEntity fe : fm.getEntityList()) {
            if (fe.hasIsDeleted() && fe.getIsDeleted()) {
                FeedEntity deletedEntity = _feedEntityById.remove(fe.getId());
                if (deletedEntity != null) {
                    _entityListener.handleDeletedFeedEntity(deletedEntity);
                }
            } else {
                newEntities.add(fe.getId());
                _feedEntityById.put(fe.getId(), fe);
                _entityListener.handleNewFeedEntity(fe);
            }
        }

        if (fh.getIncrementality() == FeedHeader.Incrementality.FULL_DATASET) {
            startingEntities.removeAll(newEntities);
            for (String deletedEntityId : startingEntities) {
                FeedEntity deletedEntity = _feedEntityById.remove(deletedEntityId);
                if (deletedEntity != null) {
                    _entityListener.handleDeletedFeedEntity(deletedEntity);
                }
            }
        }
    }

    private FeedMessage readFeedFromStream(InputStream in) throws IOException {
        try {
            return FeedMessage.parseFrom(in, _registry);
        } finally {
            try {
                in.close();
            } catch (IOException ex) {
                _log.error("Error closing stream", ex);
            }
        }
    }

    private FeedMessage readFeedFromUri(URI uri) throws IOException {
        HttpGet get = new HttpGet(uri);
        CloseableHttpResponse r = _httpClient.execute(get);
        HttpEntity e = r.getEntity();

        try {
            InputStream s = e.getContent();
            return readFeedFromStream(s);
        } finally {
            r.close();
        }
    }

    private class RefreshTask implements Runnable {

        @Override
        public void run() {
            try {
                FeedMessage fm = readFeedFromUri(_endpoint);
                handleFeedMessage(fm);
            } catch (Throwable t) {
                _log.warn("Error updating from GTFS-realtime data sources", t);
            }
        }
    }

    private class RestartTask implements Runnable {

        @Override
        public void run() {
            stop();
            for (FeedEntity fe : _feedEntityById.values()) {
                _entityListener.handleDeletedFeedEntity(fe);
            }
            _feedEntityById.clear();
            lastUpdateIndex = -1;
            startPush();
        }
    }

    @WebSocket(maxMessageSize = 16384000, maxIdleTime = 300)
    public class GtfsRealtimeClientSocket {

        private boolean _closeInProgress = false;
        private Session _session;

        public GtfsRealtimeClientSocket() {
        }

        public void close() throws IOException {
            if (_session != null) {
                _closeInProgress = true;
                _session.close(new CloseStatus(StatusCode.SHUTDOWN, "Client shutting down."));
            }
        }

        @OnWebSocketError
        public void onError(Throwable error) {
            _log.error("Error streaming GTFS-realtime updates", error);

        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            _log.info(String.format("Connection closed: %d - %s%n", statusCode, reason));
            _session = null;
            if (!_closeInProgress) {
                _log.warn("Unexpected close; attempting restart");
                _scheduledExecutorService.schedule(new RestartTask(), _refreshInterval, TimeUnit.SECONDS);
            }
        }

        @OnWebSocketConnect
        public void onConnect(Session session) {
            _session = session;
        }

        @OnWebSocketMessage
        public void onMessage(byte buf[], int offset, int length) {
            try {
                InputStream stream = new ByteArrayInputStream(buf, offset, length);
                FeedMessage fm = readFeedFromStream(stream);
                handleFeedMessage(fm);
            } catch (Throwable t) {
                _log.warn("Error updating from GTFS-realtime data sources", t);
            }
        }
    }
}
