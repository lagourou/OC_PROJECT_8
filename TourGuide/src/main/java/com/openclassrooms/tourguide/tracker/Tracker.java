package com.openclassrooms.tourguide.tracker;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.StopWatch;

import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Tracker extends Thread {

	private static final long trackingPollingInterval = TimeUnit.MINUTES.toSeconds(5);
	private final ScheduledExecutorService executorService = Executors
			.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
	private final TourGuideService tourGuideService;
	private boolean stop = false;

	public Tracker(TourGuideService tourGuideService) {
		this.tourGuideService = tourGuideService;

		executorService.scheduleAtFixedRate(this::trackUsers, 0, trackingPollingInterval,
				TimeUnit.SECONDS);
	}

	/**
	 * Assures to shut down the Tracker thread
	 */
	public void stopTracking() {
		stop = true;
		executorService.shutdownNow();
	}

	public void trackUsers() {

		if (stop) {
			log.debug("Tracker stopping");
			return;
		}

		List<User> users = tourGuideService.getAllUsers();
		log.debug("Begin Tracker. Tracking " + users.size() + " users.");

		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		users.parallelStream().forEach(u -> tourGuideService.trackUserLocation(u));
		stopWatch.stop();
		log.debug("Tracker Time Elapsed: " + TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()) + " seconds.");
	}
}
