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
import java.net.URL;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.onebusaway.transit_data_federation.services.realtime.gtfs_realtime.GtfsRealtimeEntityListener;
import org.onebusaway.transit_data_federation.services.realtime.gtfs_realtime.GtfsRealtimeFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.protobuf.ExtensionRegistry;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtimeOneBusAway;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.websocket.DefaultWebSocketListener;
import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;

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
    private GtfsRealtimeListener _listener;
    private Map<String, FeedEntity> _feedEntityById = new ConcurrentHashMap<String, FeedEntity>();
    private ScheduledExecutorService _scheduledExecutorService;
    private ScheduledFuture<?> _refreshTask;
    private static final ExtensionRegistry _registry = ExtensionRegistry.newInstance();
    
    static {
        _registry.add(GtfsRealtimeOneBusAway.obaFeedEntity);
        _registry.add(GtfsRealtimeOneBusAway.obaTripUpdate);
    }
    
    public GtfsRealtimeFeedImpl(URI endpoint, int refreshInterval,
            GtfsRealtimeEntityListener entityListener,
            ScheduledExecutorService scheduledExecutorService) {
        _endpoint = endpoint;
        _refreshInterval = refreshInterval;
        _isPush = _endpoint.getScheme().equals("ws") || _endpoint.getScheme().equals("wss");
        _entityListener = entityListener;
        _scheduledExecutorService = scheduledExecutorService;
    }
    
    @Override
    public void start() {
        if (!_isPush && _refreshInterval > 0) {
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
            if (_listener != null) {
                _listener.close();
                _listener = null;
            }
        } catch (Exception e) {
            _log.error("Exception stopping listener", e);
        }
    }
    
    private void startPush() {
        AsyncHttpClient client = new AsyncHttpClient();
        _listener = new GtfsRealtimeListener();
        WebSocketUpgradeHandler handler = new WebSocketUpgradeHandler.Builder().addWebSocketListener(_listener).build();
        try {
            WebSocket socket = client.prepareGet(_endpoint.toString()).execute(handler).get();
        } catch (Exception e) {
            /*
             * If something went wrong (endpoint down, etc.), try to set up again
             * after _refreshInterval seconds.
             */
            _log.error("Error starting WebSocket client", e);
            _scheduledExecutorService.schedule(new RestartTask(), _refreshInterval, TimeUnit.SECONDS);
            
        }
        
    }
    
    private void handleFeedMessage(FeedMessage fm) {
        FeedHeader fh = fm.getHeader();
        
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
    
    @Override
    public Collection<FeedEntity> getAllFeedEntities() {
        return _feedEntityById.values();
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
    
    private FeedMessage readFeedFromUrl(URL url) throws IOException {
        InputStream in = url.openStream();
        return readFeedFromStream(in);
    }
    
    private class RefreshTask implements Runnable {
        
        @Override
        public void run() {
            try {
                FeedMessage fm = readFeedFromUrl(_endpoint.toURL());
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
            startPush();
        }
    }
    
    private class GtfsRealtimeListener extends DefaultWebSocketListener {
        
        private boolean _closeInProgress = false;
        private WebSocket _socket;
        
        public GtfsRealtimeListener() {
        }
        
        public void close() throws IOException {
            _closeInProgress = true;
            _socket.close();
        }
        
        @Override
        public void onClose(WebSocket socket) {
            if (!_closeInProgress) {
                _log.warn("Unexpected close; attempting restart");
                _scheduledExecutorService.schedule(new RestartTask(), _refreshInterval, TimeUnit.SECONDS);
            }
        }
        
        @Override
        public void onMessage(byte[] message) {
            try {
                InputStream stream = new ByteArrayInputStream(message);
                FeedMessage fm = readFeedFromStream(stream);
                handleFeedMessage(fm);
            } catch (Throwable t) {
                _log.warn("Error updating from GTFS-realtime data sources", t);
            }
        }
        
        @Override
        public void onOpen(WebSocket socket) {
            _socket = socket;
        }
        
        @Override
        public void onError(Throwable t) {
            _log.error("Error streaming GTFS-realtime updates", t);
        }
    }
}
