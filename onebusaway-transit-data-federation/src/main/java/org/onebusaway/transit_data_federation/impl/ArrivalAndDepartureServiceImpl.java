package org.onebusaway.transit_data_federation.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.onebusaway.collections.FactoryMap;
import org.onebusaway.collections.Min;
import org.onebusaway.collections.tuple.Pair;
import org.onebusaway.collections.tuple.Tuples;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.transit_data.model.TimeIntervalBean;
import org.onebusaway.transit_data_federation.model.TargetTime;
import org.onebusaway.transit_data_federation.services.ArrivalAndDepartureQuery;
import org.onebusaway.transit_data_federation.services.ArrivalAndDepartureService;
import org.onebusaway.transit_data_federation.services.StopTimeService;
import org.onebusaway.transit_data_federation.services.StopTimeService.EFrequencyStopTimeBehavior;
import org.onebusaway.transit_data_federation.services.blocks.BlockInstance;
import org.onebusaway.transit_data_federation.services.blocks.BlockStatusService;
import org.onebusaway.transit_data_federation.services.realtime.ArrivalAndDepartureInstance;
import org.onebusaway.transit_data_federation.services.realtime.ArrivalAndDepartureTime;
import org.onebusaway.transit_data_federation.services.realtime.BlockLocation;
import org.onebusaway.transit_data_federation.services.realtime.BlockLocationService;
import org.onebusaway.transit_data_federation.services.realtime.ScheduleDeviationSamples;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockConfigurationEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockStopTimeEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockTripEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.FrequencyEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopTimeEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TripEntry;
import org.onebusaway.transit_data_federation.services.tripplanner.StopTimeInstance;
import org.onebusaway.transit_data_federation.services.tripplanner.StopTransfer;
import org.onebusaway.transit_data_federation.services.tripplanner.StopTransferService;
import org.onebusaway.utility.EOutOfRangeStrategy;
import org.onebusaway.utility.InterpolationLibrary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ArrivalAndDepartureServiceImpl implements ArrivalAndDepartureService {

  /**
   * This let's us capture trips that were scheduled to start 30 minutes before
   * the left-most edge of the user's search window, but that might be running
   * up to 30 minutes late.
   */
  static final int MINUTES_BEFORE_BUFFER = 30;

  /**
   * This let's us capture trips that were scheduled to start 10 minutes after
   * the right-most edge of the user's search window, but that might be running
   * up to 10 minutes early.
   */
  static final int MINUTES_AFTER_BUFFER = 10;

  private StopTimeService _stopTimeService;

  private BlockLocationService _blockLocationService;

  private BlockStatusService _blockStatusService;

  private StopTransferService _stopTransferService;

  @Autowired
  public void setStopTimeService(StopTimeService stopTimeService) {
    _stopTimeService = stopTimeService;
  }

  @Autowired
  public void setBlockLocationService(BlockLocationService blockLocationService) {
    _blockLocationService = blockLocationService;
  }

  @Autowired
  public void setBlockStatusService(BlockStatusService blockStatusService) {
    _blockStatusService = blockStatusService;
  }

  @Autowired
  public void setStopTransferService(StopTransferService stopTransferService) {
    _stopTransferService = stopTransferService;
  }

  @Override
  public List<ArrivalAndDepartureInstance> getArrivalsAndDeparturesForStopInTimeRange(
      StopEntry stop, TargetTime targetTime, long fromTime, long toTime) {

    // We add a buffer before and after to catch late and early buses
    Date fromTimeBuffered = new Date(fromTime - MINUTES_BEFORE_BUFFER * 60
        * 1000);
    Date toTimeBuffered = new Date(toTime + MINUTES_AFTER_BUFFER * 60 * 1000);

    List<StopTimeInstance> stis = _stopTimeService.getStopTimeInstancesInTimeRange(
        stop, fromTimeBuffered, toTimeBuffered,
        EFrequencyStopTimeBehavior.INCLUDE_UNSPECIFIED);

    long frequencyOffsetTime = Math.max(targetTime.getTargetTime(), fromTime);

    Map<BlockInstance, List<StopTimeInstance>> stisByBlockId = getStopTimeInstancesByBlockInstance(stis);

    List<ArrivalAndDepartureInstance> instances = new ArrayList<ArrivalAndDepartureInstance>();

    for (Map.Entry<BlockInstance, List<StopTimeInstance>> entry : stisByBlockId.entrySet()) {

      BlockInstance blockInstance = entry.getKey();
      List<BlockLocation> locations = _blockLocationService.getLocationsForBlockInstance(
          blockInstance, targetTime);

      List<StopTimeInstance> stisForBlock = entry.getValue();

      for (StopTimeInstance sti : stisForBlock) {

        applyRealTimeToStopTimeInstance(sti, targetTime, fromTime, toTime,
            frequencyOffsetTime, blockInstance, locations, instances);
      }
    }

    return instances;
  }

  @Override
  public List<ArrivalAndDepartureInstance> getScheduledArrivalsAndDeparturesForStopInTimeRange(
      StopEntry stop, long currentTime, long fromTime, long toTime) {

    List<StopTimeInstance> stis = _stopTimeService.getStopTimeInstancesInTimeRange(
        stop, new Date(fromTime), new Date(toTime),
        EFrequencyStopTimeBehavior.INCLUDE_UNSPECIFIED);

    List<ArrivalAndDepartureInstance> instances = new ArrayList<ArrivalAndDepartureInstance>();

    long frequencyOffsetTime = Math.max(currentTime, fromTime);

    for (StopTimeInstance sti : stis) {

      BlockInstance blockInstance = sti.getBlockInstance();

      ArrivalAndDepartureInstance instance = createArrivalAndDepartureForStopTimeInstance(
          sti, frequencyOffsetTime);

      if (sti.getFrequency() == null) {

        /**
         * We don't need to get the scheduled location of a vehicle unless its
         * in our arrival window
         */
        if (isArrivalAndDepartureBeanInRange(instance, fromTime, toTime)) {

          BlockLocation scheduledLocation = _blockLocationService.getScheduledLocationForBlockInstance(
              blockInstance, currentTime);
          if (scheduledLocation != null)
            applyBlockLocationToInstance(instance, scheduledLocation,
                currentTime);

          instances.add(instance);
        }

      } else {
        if (isFrequencyBasedArrivalInRange(blockInstance, fromTime, toTime)) {
          instances.add(instance);
        }
      }
    }

    return instances;
  }

  @Override
  public List<ArrivalAndDepartureInstance> getNextScheduledBlockTripDeparturesForStop(
      StopEntry stop, long time) {

    List<StopTimeInstance> stopTimes = _stopTimeService.getNextBlockSequenceDeparturesForStop(
        stop, time);

    List<ArrivalAndDepartureInstance> instances = new ArrayList<ArrivalAndDepartureInstance>();

    for (StopTimeInstance sti : stopTimes) {
      ArrivalAndDepartureInstance instance = createArrivalAndDepartureForStopTimeInstance(
          sti, time);
      instances.add(instance);
    }

    return instances;
  }

  @Override
  public ArrivalAndDepartureInstance getArrivalAndDepartureForStop(
      ArrivalAndDepartureQuery query) {

    StopEntry stop = query.getStop();
    int stopSequence = query.getStopSequence();
    TripEntry trip = query.getTrip();
    long serviceDate = query.getServiceDate();
    AgencyAndId vehicleId = query.getVehicleId();
    long time = query.getTime();

    Map<BlockInstance, List<BlockLocation>> locationsByInstance = _blockStatusService.getBlocks(
        trip.getBlock().getId(), serviceDate, vehicleId, time);

    if (locationsByInstance.isEmpty())
      return null;

    Map.Entry<BlockInstance, List<BlockLocation>> entry = locationsByInstance.entrySet().iterator().next();

    BlockInstance blockInstance = entry.getKey();
    List<BlockLocation> locations = entry.getValue();

    int timeOfServiceDate = (int) ((time - serviceDate) / 1000);

    ArrivalAndDepartureInstance instance = createArrivalAndDeparture(
        blockInstance, trip.getId(), stop.getId(), stopSequence, serviceDate,
        timeOfServiceDate, time);

    if (!locations.isEmpty()) {

      /**
       * What if there are multiple locations? Pick the first?
       */
      BlockLocation location = locations.get(0);
      applyBlockLocationToInstance(instance, location, time);
    }

    return instance;
  }

  @Override
  public ArrivalAndDepartureInstance getPreviousStopArrivalAndDeparture(
      ArrivalAndDepartureInstance instance) {

    BlockStopTimeEntry stopTime = instance.getBlockStopTime();
    BlockTripEntry trip = stopTime.getTrip();
    BlockConfigurationEntry blockConfig = trip.getBlockConfiguration();
    List<BlockStopTimeEntry> stopTimes = blockConfig.getStopTimes();

    int index = stopTime.getBlockSequence() - 1;
    if (index < 0)
      return null;

    BlockInstance blockInstance = instance.getBlockInstance();
    BlockStopTimeEntry prevStopTime = stopTimes.get(index);

    ArrivalAndDepartureTime scheduledTime = ArrivalAndDepartureTime.getScheduledTime(
        blockInstance, prevStopTime);

    if (blockInstance.getFrequency() != null) {

      StopTimeEntry pStopTime = prevStopTime.getStopTime();

      int betweenStopDelta = stopTime.getStopTime().getArrivalTime()
          - pStopTime.getDepartureTime();
      int atStopDelta = pStopTime.getDepartureTime()
          - pStopTime.getArrivalTime();

      long scheduledDepartureTime = instance.getScheduledArrivalTime()
          - betweenStopDelta * 1000;
      long scheduledArrivalTime = scheduledDepartureTime - atStopDelta * 1000;

      scheduledTime.setArrivalTime(scheduledArrivalTime);
      scheduledTime.setDepartureTime(scheduledDepartureTime);
    }

    ArrivalAndDepartureInstance prevInstance = new ArrivalAndDepartureInstance(
        blockInstance, prevStopTime, scheduledTime);

    if (instance.isPredictedArrivalTimeSet()) {

      int scheduledDeviation = (int) ((instance.getPredictedArrivalTime() - instance.getScheduledArrivalTime()) / 1000);

      int departureDeviation = propagateScheduleDeviationBackwardBetweenStops(
          prevStopTime, stopTime, scheduledDeviation);
      int arrivalDeviation = propagateScheduleDeviationBackwardAcrossStop(
          prevStopTime, departureDeviation);

      setPredictedArrivalTimeForInstance(prevInstance,
          prevInstance.getScheduledArrivalTime() + arrivalDeviation * 1000);
      setPredictedDepartureTimeForInstance(prevInstance,
          prevInstance.getScheduledDepartureTime() + departureDeviation * 1000);
    }

    return prevInstance;
  }

  @Override
  public ArrivalAndDepartureInstance getNextStopArrivalAndDeparture(
      ArrivalAndDepartureInstance instance) {

    BlockStopTimeEntry stopTime = instance.getBlockStopTime();
    BlockTripEntry trip = stopTime.getTrip();
    BlockConfigurationEntry blockConfig = trip.getBlockConfiguration();
    List<BlockStopTimeEntry> stopTimes = blockConfig.getStopTimes();

    int index = stopTime.getBlockSequence() + 1;
    if (index >= stopTimes.size())
      return null;

    BlockInstance blockInstance = instance.getBlockInstance();
    BlockStopTimeEntry nextStopTime = stopTimes.get(index);

    ArrivalAndDepartureTime scheduledTime = ArrivalAndDepartureTime.getScheduledTime(
        blockInstance, nextStopTime);

    if (blockInstance.getFrequency() != null) {

      StopTimeEntry nStopTime = nextStopTime.getStopTime();

      int betweenStopDelta = nStopTime.getArrivalTime()
          - stopTime.getStopTime().getDepartureTime();
      int atStopDelta = nStopTime.getDepartureTime()
          - nStopTime.getArrivalTime();

      long scheduledArrivalTime = instance.getScheduledDepartureTime()
          + betweenStopDelta * 1000;
      long scheduledDepartureTime = scheduledArrivalTime + atStopDelta * 1000;

      scheduledTime.setArrivalTime(scheduledArrivalTime);
      scheduledTime.setDepartureTime(scheduledDepartureTime);
    }

    ArrivalAndDepartureInstance nextInstance = new ArrivalAndDepartureInstance(
        blockInstance, nextStopTime, scheduledTime);

    if (instance.isPredictedDepartureTimeSet()) {

      int scheduledDeviation = (int) ((instance.getPredictedDepartureTime() - instance.getScheduledDepartureTime()) / 1000);

      int arrivalDeviation = propagateScheduleDeviationForwardBetweenStops(
          stopTime, nextStopTime, scheduledDeviation);
      int departureDeviation = propagateScheduleDeviationForwardAcrossStop(
          nextStopTime, arrivalDeviation);

      setPredictedArrivalTimeForInstance(nextInstance,
          nextInstance.getScheduledArrivalTime() + arrivalDeviation * 1000);
      setPredictedDepartureTimeForInstance(nextInstance,
          nextInstance.getScheduledDepartureTime() + departureDeviation * 1000);
    }

    return nextInstance;
  }

  @Override
  public ArrivalAndDepartureInstance getNextTransferStopArrivalAndDeparture(
      ArrivalAndDepartureInstance instance) {

    BlockStopTimeEntry blockStopTime = instance.getBlockStopTime();
    BlockTripEntry trip = blockStopTime.getTrip();
    BlockConfigurationEntry blockConfig = trip.getBlockConfiguration();
    List<BlockStopTimeEntry> stopTimes = blockConfig.getStopTimes();

    int index = blockStopTime.getBlockSequence() + 1;

    while (true) {

      if (index >= stopTimes.size())
        return null;

      BlockStopTimeEntry nextBlockStopTime = stopTimes.get(index);

      StopTimeEntry nextStopTime = nextBlockStopTime.getStopTime();
      StopEntry nextStop = nextStopTime.getStop();

      List<StopTransfer> transfers = _stopTransferService.getTransfersFromStop(nextStop);

      if (!transfers.isEmpty()) {
        ArrivalAndDepartureInstance nextInstance = new ArrivalAndDepartureInstance(
            instance.getBlockInstance(), nextBlockStopTime);

        BlockInstance nextBlockInstance = nextInstance.getBlockInstance();

        if (nextBlockInstance.getFrequency() != null) {

          int betweenStopDelta = nextStopTime.getArrivalTime()
              - blockStopTime.getStopTime().getDepartureTime();
          int atStopDelta = nextStopTime.getDepartureTime()
              - nextStopTime.getArrivalTime();

          long scheduledArrivalTime = instance.getScheduledDepartureTime()
              + betweenStopDelta * 1000;
          long scheduledDepartureTime = scheduledArrivalTime + atStopDelta
              * 1000;

          nextInstance.setScheduledArrivalTime(scheduledArrivalTime);
          nextInstance.setScheduledDepartureTime(scheduledDepartureTime);
        }

        return nextInstance;
      }

      index++;
    }
  }

  @Override
  public List<Pair<ArrivalAndDepartureInstance>> getNextDeparturesForStopPair(
      StopEntry fromStop, StopEntry toStop, TargetTime targetTime,
      int resultCount, boolean applyRealTime) {

    Date tFrom = new Date(targetTime.getTargetTime());

    int lookBehind = applyRealTime ? MINUTES_BEFORE_BUFFER * 60 : 0;
    int lookAhead = applyRealTime ? MINUTES_AFTER_BUFFER * 60 : 0;

    List<Pair<StopTimeInstance>> pairs = _stopTimeService.getNextDeparturesBetweenStopPair(
        fromStop, toStop, tFrom, lookBehind, lookAhead, resultCount);

    return getArrivalsAndDeparturesFromStopTimeInstancePairs(targetTime, pairs,
        tFrom, null, applyRealTime);
  }

  @Override
  public List<Pair<ArrivalAndDepartureInstance>> getPreviousArrivalsForStopPair(
      StopEntry fromStop, StopEntry toStop, TargetTime targetTime,
      int resultCount, boolean applyRealTime) {

    Date tTo = new Date(targetTime.getTargetTime());

    int lookBehind = applyRealTime ? MINUTES_BEFORE_BUFFER * 60 : 0;
    int lookAhead = applyRealTime ? MINUTES_AFTER_BUFFER * 60 : 0;

    List<Pair<StopTimeInstance>> pairs = _stopTimeService.getPreviousArrivalsBetweenStopPair(
        fromStop, toStop, tTo, lookBehind, lookAhead, resultCount);

    return getArrivalsAndDeparturesFromStopTimeInstancePairs(targetTime, pairs,
        null, tTo, applyRealTime);
  }

  /****
   * Private Methods
   ****/

  private List<Pair<ArrivalAndDepartureInstance>> getArrivalsAndDeparturesFromStopTimeInstancePairs(
      TargetTime targetTime, List<Pair<StopTimeInstance>> pairs, Date tFrom,
      Date tTo, boolean applyRealTime) {

    long frequencyOffsetTime = Math.max(targetTime.getTargetTime(),
        targetTime.getCurrentTime());

    List<Pair<ArrivalAndDepartureInstance>> results = new ArrayList<Pair<ArrivalAndDepartureInstance>>();

    Set<BlockInstance> blockInstances = new HashSet<BlockInstance>();
    for (Pair<StopTimeInstance> pair : pairs)
      blockInstances.add(pair.getFirst().getBlockInstance());

    Map<BlockInstance, List<BlockLocation>> blockLocationsByBlockInstance = new HashMap<BlockInstance, List<BlockLocation>>();

    for (BlockInstance blockInstance : blockInstances) {
      List<BlockLocation> locations = _blockLocationService.getLocationsForBlockInstance(
          blockInstance, targetTime);
      blockLocationsByBlockInstance.put(blockInstance, locations);
    }

    for (Pair<StopTimeInstance> pair : pairs) {

      StopTimeInstance stiFrom = pair.getFirst();
      StopTimeInstance stiTo = pair.getSecond();

      if (applyRealTime) {

        BlockInstance blockInstance = stiFrom.getBlockInstance();
        List<BlockLocation> locations = blockLocationsByBlockInstance.get(blockInstance);

        List<ArrivalAndDepartureInstance> fromInstances = new ArrayList<ArrivalAndDepartureInstance>();
        List<ArrivalAndDepartureInstance> toInstances = new ArrayList<ArrivalAndDepartureInstance>();

        applyRealTimeToStopTimeInstance(stiFrom, targetTime, tFrom.getTime(),
            tTo.getTime(), frequencyOffsetTime, blockInstance, locations,
            fromInstances);

        applyRealTimeToStopTimeInstance(stiFrom, targetTime, tFrom.getTime(),
            tTo.getTime(), frequencyOffsetTime, blockInstance, locations,
            toInstances);

      } else {

        ArrivalAndDepartureInstance instanceFrom = createArrivalAndDepartureForStopTimeInstance(
            stiFrom, frequencyOffsetTime);
        ArrivalAndDepartureInstance instanceTo = createArrivalAndDepartureForStopTimeInstance(
            stiTo, frequencyOffsetTime);

        Pair<ArrivalAndDepartureInstance> instancePair = Tuples.pair(
            instanceFrom, instanceTo);
        results.add(instancePair);
      }
    }

    return results;
  }

  private Map<BlockInstance, List<StopTimeInstance>> getStopTimeInstancesByBlockInstance(
      List<StopTimeInstance> stopTimes) {

    Map<BlockInstance, List<StopTimeInstance>> r = new FactoryMap<BlockInstance, List<StopTimeInstance>>(
        new ArrayList<StopTimeInstance>());

    for (StopTimeInstance stopTime : stopTimes) {
      BlockStopTimeEntry blockStopTime = stopTime.getStopTime();
      BlockTripEntry blockTrip = blockStopTime.getTrip();
      BlockConfigurationEntry blockConfiguration = blockTrip.getBlockConfiguration();
      long serviceDate = stopTime.getServiceDate();
      BlockInstance blockInstance = new BlockInstance(blockConfiguration,
          serviceDate, stopTime.getFrequency());
      r.get(blockInstance).add(stopTime);
    }

    return r;
  }

  private void applyRealTimeToStopTimeInstance(StopTimeInstance sti,
      TargetTime targetTime, long fromTime, long toTime,
      long frequencyOffsetTime, BlockInstance blockInstance,
      List<BlockLocation> locations, List<ArrivalAndDepartureInstance> results) {

    for (BlockLocation location : locations) {

      ArrivalAndDepartureInstance instance = createArrivalAndDepartureForStopTimeInstance(
          sti, frequencyOffsetTime);
      applyBlockLocationToInstance(instance, location,
          targetTime.getTargetTime());

      if (isArrivalAndDepartureBeanInRange(instance, fromTime, toTime))
        results.add(instance);
    }

    if (locations.isEmpty()) {

      ArrivalAndDepartureInstance instance = createArrivalAndDepartureForStopTimeInstance(
          sti, frequencyOffsetTime);

      if (sti.getFrequency() == null) {

        /**
         * We don't need to get the scheduled location of a vehicle unless its
         * in our arrival window
         */
        if (isArrivalAndDepartureBeanInRange(instance, fromTime, toTime)) {

          BlockLocation scheduledLocation = _blockLocationService.getScheduledLocationForBlockInstance(
              blockInstance, targetTime.getTargetTime());

          if (scheduledLocation != null)
            applyBlockLocationToInstance(instance, scheduledLocation,
                targetTime.getTargetTime());

          results.add(instance);
        }

      } else {
        if (isFrequencyBasedArrivalInRange(blockInstance, fromTime, toTime)) {
          results.add(instance);
        }
      }
    }
  }

  private void applyBlockLocationToInstance(
      ArrivalAndDepartureInstance instance, BlockLocation blockLocation,
      long targetTime) {

    instance.setBlockLocation(blockLocation);

    if (blockLocation.isScheduleDeviationSet()
        || blockLocation.areScheduleDeviationsSet()) {

      int scheduleDeviation = getBestScheduleDeviation(instance, blockLocation);
      setPredictedTimesFromScheduleDeviation(instance, blockLocation,
          scheduleDeviation, targetTime);
    }
  }

  private int getBestScheduleDeviation(ArrivalAndDepartureInstance instance,
      BlockLocation blockLocation) {

    ScheduleDeviationSamples scheduleDeviations = blockLocation.getScheduleDeviations();

    if (scheduleDeviations != null && !scheduleDeviations.isEmpty()) {
      int arrivalTime = instance.getBlockStopTime().getStopTime().getArrivalTime();
      return (int) InterpolationLibrary.interpolate(
          scheduleDeviations.getScheduleTimes(),
          scheduleDeviations.getScheduleDeviationMus(), arrivalTime,
          EOutOfRangeStrategy.LAST_VALUE);
    } else if (blockLocation.isScheduleDeviationSet()) {
      return (int) blockLocation.getScheduleDeviation();
    } else {
      return 0;
    }
  }

  private void setPredictedTimesFromScheduleDeviation(
      ArrivalAndDepartureInstance instance, BlockLocation blockLocation,
      int scheduleDeviation, long targetTime) {

    BlockStopTimeEntry blockStopTime = instance.getBlockStopTime();

    int effectiveScheduleTime = (int) (((targetTime - instance.getServiceDate()) / 1000) - scheduleDeviation);

    int arrivalDeviation = calculateArrivalDeviation(
        blockLocation.getNextStop(), blockStopTime, effectiveScheduleTime,
        scheduleDeviation);

    int departureDeviation = calculateDepartureDeviation(
        blockLocation.getNextStop(), blockStopTime, effectiveScheduleTime,
        scheduleDeviation);

    /**
     * Why don't we use the ArrivalAndDepartureTime scheduled arrival and
     * departures here? Because they may have been artificially shifted for a
     * frequency-based method
     */
    ArrivalAndDepartureTime schedule = ArrivalAndDepartureTime.getScheduledTime(
        instance.getBlockInstance(), instance.getBlockStopTime());

    long arrivalTime = schedule.getArrivalTime() + arrivalDeviation * 1000;
    setPredictedArrivalTimeForInstance(instance, arrivalTime);

    TimeIntervalBean predictedArrivalTimeInterval = computePredictedArrivalTimeInterval(
        instance, blockLocation, targetTime);
    instance.setPredictedArrivalInterval(predictedArrivalTimeInterval);

    long departureTime = schedule.getDepartureTime() + departureDeviation
        * 1000;
    setPredictedDepartureTimeForInstance(instance, departureTime);

    TimeIntervalBean predictedDepartureTimeInterval = computePredictedDepartureTimeInterval(
        instance, blockLocation, targetTime);
    instance.setPredictedDepartureInterval(predictedDepartureTimeInterval);

  }

  /**
   * This method both sets the predicted arrival time for an instance, but also
   * updates the scheduled arrival time for a frequency-based instance
   * 
   * @param instance
   * @param arrivalTime
   */
  private void setPredictedArrivalTimeForInstance(
      ArrivalAndDepartureInstance instance, long arrivalTime) {

    instance.setPredictedArrivalTime(arrivalTime);

    if (instance.getFrequency() != null)
      instance.setScheduledArrivalTime(arrivalTime);
  }

  /**
   * This method both sets the predicted departure time for an instance, but
   * also updates the scheduled departure time for a frequency-based instance
   * 
   * @param instance
   * @param departureTime
   */
  private void setPredictedDepartureTimeForInstance(
      ArrivalAndDepartureInstance instance, long departureTime) {

    instance.setPredictedDepartureTime(departureTime);

    if (instance.getFrequency() != null)
      instance.setScheduledDepartureTime(departureTime);
  }

  private int calculateArrivalDeviation(BlockStopTimeEntry nextBlockStopTime,
      BlockStopTimeEntry targetBlockStopTime, int effectiveScheduleTime,
      int scheduleDeviation) {

    // TargetStopTime

    if (nextBlockStopTime == null
        || nextBlockStopTime.getBlockSequence() > targetBlockStopTime.getBlockSequence()) {
      return scheduleDeviation;
    }

    int a = targetBlockStopTime.getAccumulatedSlackTime();
    int b = nextBlockStopTime.getAccumulatedSlackTime();
    double slack = a - b;

    StopTimeEntry nextStopTime = nextBlockStopTime.getStopTime();

    if (nextStopTime.getArrivalTime() <= effectiveScheduleTime
        && effectiveScheduleTime <= nextStopTime.getDepartureTime()) {
      slack -= (effectiveScheduleTime - nextStopTime.getArrivalTime());
    }

    slack = Math.max(slack, 0);

    if (slack > 0 && scheduleDeviation > 0)
      scheduleDeviation -= Math.min(scheduleDeviation, slack);

    return scheduleDeviation;
  }

  private int calculateDepartureDeviation(BlockStopTimeEntry nextBlockStopTime,
      BlockStopTimeEntry targetBlockStopTime, int effectiveScheduleTime,
      int scheduleDeviation) {

    // TargetStopTime
    if (nextBlockStopTime == null
        || nextBlockStopTime.getBlockSequence() > targetBlockStopTime.getBlockSequence()) {
      return scheduleDeviation;
    }

    StopTimeEntry nextStopTime = nextBlockStopTime.getStopTime();
    StopTimeEntry targetStopTime = targetBlockStopTime.getStopTime();

    double slack = targetBlockStopTime.getAccumulatedSlackTime()
        - nextBlockStopTime.getAccumulatedSlackTime();

    slack += targetStopTime.getSlackTime();

    if (nextStopTime.getArrivalTime() <= effectiveScheduleTime
        && effectiveScheduleTime <= nextStopTime.getDepartureTime()) {
      slack -= (effectiveScheduleTime - nextStopTime.getArrivalTime());
    }

    slack = Math.max(slack, 0);

    if (slack > 0 && scheduleDeviation > 0)
      scheduleDeviation -= Math.min(scheduleDeviation, slack);

    return scheduleDeviation;
  }

  private int propagateScheduleDeviationForwardBetweenStops(
      BlockStopTimeEntry prevStopTime, BlockStopTimeEntry nextStopTime,
      int scheduleDeviation) {

    int slack = nextStopTime.getAccumulatedSlackTime()
        - prevStopTime.getAccumulatedSlackTime();

    slack -= prevStopTime.getStopTime().getSlackTime();

    return propagateScheduleDeviationForwardWithSlack(scheduleDeviation, slack);
  }

  private int propagateScheduleDeviationForwardAcrossStop(
      BlockStopTimeEntry stopTime, int scheduleDeviation) {

    int slack = stopTime.getStopTime().getSlackTime();

    return propagateScheduleDeviationForwardWithSlack(scheduleDeviation, slack);
  }

  private int propagateScheduleDeviationBackwardBetweenStops(
      BlockStopTimeEntry prevStopTime, BlockStopTimeEntry nextStopTime,
      int scheduleDeviation) {

    // TODO: Need to think about this

    return scheduleDeviation;
  }

  private int propagateScheduleDeviationBackwardAcrossStop(
      BlockStopTimeEntry stopTime, int scheduleDeviation) {

    return scheduleDeviation;
  }

  private int propagateScheduleDeviationForwardWithSlack(int scheduleDeviation,
      int slack) {
    /**
     * If the vehicle is running early and there is slack built into the
     * schedule, we guess that the vehicle will take that opportunity to pause
     * and let the schedule catch back up. If there is no slack, assume we'll
     * continue to run early.
     */
    if (scheduleDeviation < 0) {
      if (slack > 0)
        return 0;
      return scheduleDeviation;
    }

    /**
     * If we're running behind schedule, we allow any slack to eat up part of
     * our delay.
     */
    return Math.max(0, scheduleDeviation - slack);
  }

  private TimeIntervalBean computePredictedArrivalTimeInterval(
      ArrivalAndDepartureInstance instance, BlockLocation blockLocation,
      long targetTime) {

    BlockStopTimeEntry blockStopTime = instance.getBlockStopTime();
    StopTimeEntry stopTime = blockStopTime.getStopTime();

    // If the vehicle has already passed the stop, then there is no prediction
    // interval
    if (stopTime.getArrivalTime() <= blockLocation.getEffectiveScheduleTime())
      return null;

    ScheduleDeviationSamples samples = blockLocation.getScheduleDeviations();

    if (samples == null || samples.isEmpty())
      return null;

    double mu = InterpolationLibrary.interpolate(samples.getScheduleTimes(),
        samples.getScheduleDeviationMus(), stopTime.getArrivalTime(),
        EOutOfRangeStrategy.LAST_VALUE);
    double sigma = InterpolationLibrary.interpolate(samples.getScheduleTimes(),
        samples.getScheduleDeviationSigmas(), stopTime.getArrivalTime(),
        EOutOfRangeStrategy.LAST_VALUE);

    long from = (long) (instance.getScheduledArrivalTime() + (mu - sigma) * 1000);
    long to = (long) (instance.getScheduledArrivalTime() + (mu + sigma) * 1000);

    return new TimeIntervalBean(from, to);
  }

  private TimeIntervalBean computePredictedDepartureTimeInterval(
      ArrivalAndDepartureInstance instance, BlockLocation blockLocation,
      long targetTime) {

    BlockStopTimeEntry blockStopTime = instance.getBlockStopTime();
    StopTimeEntry stopTime = blockStopTime.getStopTime();

    // If the vehicle has already passed the stop, then there is no prediction
    // interval
    if (stopTime.getDepartureTime() <= blockLocation.getEffectiveScheduleTime())
      return null;

    ScheduleDeviationSamples samples = blockLocation.getScheduleDeviations();

    if (samples == null || samples.isEmpty())
      return null;

    double mu = InterpolationLibrary.interpolate(samples.getScheduleTimes(),
        samples.getScheduleDeviationMus(), stopTime.getDepartureTime(),
        EOutOfRangeStrategy.LAST_VALUE);
    double sigma = InterpolationLibrary.interpolate(samples.getScheduleTimes(),
        samples.getScheduleDeviationSigmas(), stopTime.getDepartureTime(),
        EOutOfRangeStrategy.LAST_VALUE);

    long from = (long) (instance.getScheduledDepartureTime() + (mu - sigma) * 1000);
    long to = (long) (instance.getScheduledDepartureTime() + (mu + sigma) * 1000);

    return new TimeIntervalBean(from, to);
  }

  private boolean isArrivalAndDepartureBeanInRange(
      ArrivalAndDepartureInstance instance, long timeFrom, long timeTo) {
    if (timeFrom <= instance.getScheduledArrivalTime()
        && instance.getScheduledArrivalTime() <= timeTo)
      return true;
    if (timeFrom <= instance.getScheduledDepartureTime()
        && instance.getScheduledDepartureTime() <= timeTo)
      return true;
    if (instance.isPredictedArrivalTimeSet()
        && timeFrom <= instance.getPredictedArrivalTime()
        && instance.getPredictedArrivalTime() <= timeTo)
      return true;
    if (instance.isPredictedDepartureTimeSet()
        && timeFrom <= instance.getPredictedDepartureTime()
        && instance.getPredictedDepartureTime() <= timeTo)
      return true;
    return false;
  }

  private boolean isFrequencyBasedArrivalInRange(BlockInstance blockInstance,
      long fromReduced, long toReduced) {
    FrequencyEntry freq = blockInstance.getFrequency();
    long startTime = blockInstance.getServiceDate() + freq.getStartTime()
        * 1000;
    long endTime = blockInstance.getServiceDate() + freq.getEndTime() * 1000;
    return fromReduced <= endTime && startTime <= toReduced;
  }

  private ArrivalAndDepartureInstance createArrivalAndDepartureForStopTimeInstance(
      StopTimeInstance sti, long frequencyOffsetTime) {

    BlockInstance blockInstance = sti.getBlockInstance();
    BlockStopTimeEntry blockStopTime = sti.getStopTime();

    return createArrivalAndDeparture(blockInstance, blockStopTime,
        frequencyOffsetTime, sti.getFrequencyOffset());
  }

  private ArrivalAndDepartureInstance createArrivalAndDeparture(
      BlockInstance blockInstance, AgencyAndId tripId, AgencyAndId stopId,
      int stopSequence, long serviceDate, int timeOfServiceDate,
      long frequencyOffsetTime) {

    /**
     * Note that this is just a linear search. If this ends up being a
     * performance bottle-neck, we may have to look for a faster method here
     */
    BlockTripEntry blockTrip = getBlockTripEntry(blockInstance.getBlock(),
        tripId);

    if (blockTrip == null)
      return null;

    BlockStopTimeEntry blockStopTime = getBlockStopTime(blockTrip, stopId,
        stopSequence, timeOfServiceDate);

    return createArrivalAndDeparture(blockInstance, blockStopTime,
        frequencyOffsetTime, StopTimeInstance.UNSPECIFIED_FREQUENCY_OFFSET);
  }

  private ArrivalAndDepartureInstance createArrivalAndDeparture(
      BlockInstance blockInstance, BlockStopTimeEntry blockStopTime,
      long frequencyOffsetTime, int frequencyOffset) {

    ArrivalAndDepartureTime scheduledTime = getScheduledTime(blockInstance,
        blockStopTime, frequencyOffsetTime, frequencyOffset);

    return new ArrivalAndDepartureInstance(blockInstance, blockStopTime,
        scheduledTime);
  }

  private BlockTripEntry getBlockTripEntry(BlockConfigurationEntry blockConfig,
      AgencyAndId tripId) {
    for (BlockTripEntry blockTrip : blockConfig.getTrips()) {
      if (blockTrip.getTrip().getId().equals(tripId))
        return blockTrip;
    }
    return null;
  }

  private BlockStopTimeEntry getBlockStopTime(BlockTripEntry blockTrip,
      AgencyAndId stopId, int stopSequence, int timeOfServiceDate) {

    /**
     * We don't iterate over block stop times directly because there is
     * performance penalty with instantiating each. Also note that this will
     * currently miss the case where a stop is visited twice in the same trip.
     */
    TripEntry trip = blockTrip.getTrip();
    List<StopTimeEntry> stopTimes = trip.getStopTimes();

    if (stopSequence > -1) {

      /**
       * If a stop sequence has been specified, we start our search at the
       * specified index, expanding our search until we find the target stop. We
       * allow this flexibility in the case of a bookmarked arrival-departure
       * where the stop sequence has changed slightly due to the addition or
       * subtraction of a previous stop.
       */
      int offset = 0;
      while (true) {
        int before = stopSequence - offset;
        if (isMatch(stopTimes, stopId, before))
          return blockTrip.getStopTimes().get(before);
        int after = stopSequence + offset;
        if (isMatch(stopTimes, stopId, after))
          return blockTrip.getStopTimes().get(after);

        if (before < 0 && after >= stopTimes.size())
          return null;

        offset++;

      }
    } else {

      Min<BlockStopTimeEntry> m = new Min<BlockStopTimeEntry>();
      int index = 0;

      for (StopTimeEntry stopTime : stopTimes) {
        if (stopTime.getStop().getId().equals(stopId)) {
          int a = Math.abs(timeOfServiceDate - stopTime.getArrivalTime());
          int b = Math.abs(timeOfServiceDate - stopTime.getDepartureTime());
          int delta = Math.min(a, b);
          m.add(delta, blockTrip.getStopTimes().get(index));
        }
        index++;
      }

      if (m.isEmpty())
        return null;

      return m.getMinElement();
    }
  }

  private boolean isMatch(List<StopTimeEntry> stopTimes, AgencyAndId stopId,
      int index) {
    if (index < 0 || index >= stopTimes.size())
      return false;
    StopTimeEntry stopTime = stopTimes.get(index);
    StopEntry stop = stopTime.getStop();
    return stop.getId().equals(stopId);
  }

  private ArrivalAndDepartureTime getScheduledTime(BlockInstance blockInstance,
      BlockStopTimeEntry blockStopTime, long frequencyOffsetTime,
      int frequencyOffset) {

    FrequencyEntry frequency = blockInstance.getFrequency();

    if (frequency == null) {

      return ArrivalAndDepartureTime.getScheduledTime(blockInstance,
          blockStopTime);

    } else if (StopTimeInstance.isFrequencyOffsetSpecified(frequencyOffset)) {

      return ArrivalAndDepartureTime.getScheduledTime(blockInstance,
          blockStopTime, frequencyOffset);

    } else {

      long departureTime = frequencyOffsetTime + frequency.getHeadwaySecs()
          * 1000 / 2;

      long freqStartTime = blockInstance.getServiceDate()
          + frequency.getStartTime() * 1000;
      long freqEndTime = blockInstance.getServiceDate()
          + frequency.getEndTime() * 1000;

      if (departureTime < freqStartTime)
        departureTime = freqStartTime;
      if (departureTime > freqEndTime)
        departureTime = freqEndTime;

      /**
       * We need to make sure the arrival time is adjusted relative to the
       * departure time and the layover at the stop.
       */
      StopTimeEntry stopTime = blockStopTime.getStopTime();
      int delta = stopTime.getDepartureTime() - stopTime.getArrivalTime();

      long arrivalTime = departureTime - delta * 1000;

      return new ArrivalAndDepartureTime(arrivalTime, departureTime);
    }
  }
}
