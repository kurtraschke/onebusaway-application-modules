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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.onebusaway.collections.FactoryMap;
import org.onebusaway.collections.Max;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.realtime.api.VehicleLocationListener;
import org.onebusaway.realtime.api.VehicleLocationRecord;
import org.onebusaway.transit_data_federation.services.realtime.gtfs_realtime.GtfsRealtimeEntityListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

/**
 *
 * @author kurt
 */
public class CombinedEntityListener {

    private static final Logger _log = LoggerFactory.getLogger(CombinedEntityListener.class);
    private GtfsRealtimeEntityListener _tripUpdatesListener = new TripUpdatesEntityListener();
    private GtfsRealtimeEntityListener _vehiclePositionsListener = new VehiclePositionsEntityListener();
    private GtfsRealtimeTripLibrary _tripLibrary;
    private VehicleLocationListener _vehicleLocationListener;
    private Map<BlockDescriptor, BlockData> _dataByBlock = new FactoryMap<BlockDescriptor, BlockData>(new BlockData());
    private Map<AgencyAndId, Date> _lastVehicleUpdate = new HashMap<AgencyAndId, Date>();

    public void setTripLibrary(GtfsRealtimeTripLibrary tripLibrary) {
        _tripLibrary = tripLibrary;
    }

    public void setVehicleLocationListener(VehicleLocationListener vehicleLocationListener) {
        _vehicleLocationListener = vehicleLocationListener;
    }

    public GtfsRealtimeEntityListener getTripUpdatesEntityListener() {
        return _tripUpdatesListener;

    }

    public GtfsRealtimeEntityListener getVehiclePositionsListener() {
        return _vehiclePositionsListener;
    }

    private void applyUpdate(CombinedTripUpdatesAndVehiclePosition update) {
        VehicleLocationRecord record = _tripLibrary.createVehicleLocationRecordForUpdate(update);
        if (record != null) {
            AgencyAndId vehicleId = record.getVehicleId();
            Date timestamp = new Date(record.getTimeOfRecord());
            Date prev = _lastVehicleUpdate.get(vehicleId);
            if (prev == null || prev.before(timestamp)) {
                _vehicleLocationListener.handleVehicleLocationRecord(record);
                _lastVehicleUpdate.put(vehicleId, timestamp);
            }
        }

    }

    private void createUpdateForBlockDescriptor(BlockDescriptor block) {
        BlockData bd = _dataByBlock.get(block);

        CombinedTripUpdatesAndVehiclePosition update = new CombinedTripUpdatesAndVehiclePosition();
        update.block = block;
        update.tripUpdates = new ArrayList<TripUpdate>(bd.tripUpdates.values());


        if (!bd.vehiclePositions.isEmpty()) {
            Max<VehiclePosition> latestUpdate = new Max<VehiclePosition>();

            for (VehiclePosition vp : bd.vehiclePositions.values()) {
                latestUpdate.add(vp.getTimestamp(), vp);
            }

            VehiclePosition vehiclePosition = latestUpdate.getMaxElement();
            update.vehiclePosition = vehiclePosition;
            if (vehiclePosition.hasVehicle()) {
                GtfsRealtime.VehicleDescriptor vehicle = vehiclePosition.getVehicle();
                if (vehicle.hasId()) {
                    update.block.setVehicleId(vehicle.getId());
                }
            }

        }

        if (update.block.getVehicleId() == null) {
            for (TripUpdate tripUpdate : update.tripUpdates) {
                if (tripUpdate.hasVehicle()) {
                    GtfsRealtime.VehicleDescriptor vehicle = tripUpdate.getVehicle();
                    if (vehicle.hasId()) {
                        update.block.setVehicleId(vehicle.getId());
                    }
                }
            }
        }

        applyUpdate(update);
    }

    private synchronized void handleNewTripUpdate(FeedEntity fe) {
        String id = fe.getId();
        TripUpdate t = fe.getTripUpdate();

        if (t.hasTrip()) {
            BlockDescriptor bd = _tripLibrary.getTripDescriptorAsBlockDescriptor(t.getTrip());
            BlockData bld = _dataByBlock.get(bd);

            bld.tripUpdates.put(id, t);

            createUpdateForBlockDescriptor(bd);
        } else {
            _log.warn("expected a FeedEntity with a TripUpdate");
        }
    }

    private synchronized void handleDeletedTripUpdate(FeedEntity fe) {
        String id = fe.getId();
        TripUpdate t = fe.getTripUpdate();

        BlockDescriptor bd = _tripLibrary.getTripDescriptorAsBlockDescriptor(t.getTrip());
        BlockData bld = _dataByBlock.get(bd);

        bld.tripUpdates.remove(id);

        createUpdateForBlockDescriptor(bd);
    }

    private synchronized void handleNewVehiclePosition(FeedEntity fe) {
        String id = fe.getId();
        VehiclePosition v = fe.getVehicle();

        if (v.hasTrip()) {
            BlockDescriptor bd = _tripLibrary.getTripDescriptorAsBlockDescriptor(v.getTrip());
            BlockData bld = _dataByBlock.get(bd);

            bld.vehiclePositions.put(id, v);

            createUpdateForBlockDescriptor(bd);
        } else {
            _log.warn("expected a FeedEntity with a TripPosition");
        }
    }

    private synchronized void handleDeletedVehiclePosition(FeedEntity fe) {
        String id = fe.getId();
        VehiclePosition v = fe.getVehicle();

        BlockDescriptor bd = _tripLibrary.getTripDescriptorAsBlockDescriptor(v.getTrip());
        BlockData bld = _dataByBlock.get(bd);

        bld.tripUpdates.remove(id);

        createUpdateForBlockDescriptor(bd);
    }

    private class TripUpdatesEntityListener implements GtfsRealtimeEntityListener {

        @Override
        public void handleNewFeedEntity(FeedEntity fe) {
            handleNewTripUpdate(fe);
        }

        @Override
        public void handleDeletedFeedEntity(FeedEntity fe) {
            handleDeletedTripUpdate(fe);
        }
    }

    private class VehiclePositionsEntityListener implements GtfsRealtimeEntityListener {

        @Override
        public void handleNewFeedEntity(FeedEntity fe) {
            handleNewVehiclePosition(fe);

        }

        @Override
        public void handleDeletedFeedEntity(FeedEntity fe) {
            handleDeletedVehiclePosition(fe);
        }
    }

    public static class BlockData {

        public BlockData() {
        }
        public Map<String, TripUpdate> tripUpdates = new HashMap<String, TripUpdate>();
        public Map<String, VehiclePosition> vehiclePositions = new HashMap<String, VehiclePosition>();
    }
}
