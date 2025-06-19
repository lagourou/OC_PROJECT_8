package com.openclassrooms.tourguide.unitaire;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.time.StopWatch;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.service.RewardsService;
import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.VisitedLocation;
import lombok.extern.slf4j.Slf4j;
import rewardCentral.RewardCentral;

@Slf4j
public class TestPerformance {

	private static TourGuideService tourGuideService;
	private static ExecutorService executor;

	/*
	 * A note on performance improvements:
	 * 
	 * The number of users generated for the high volume tests can be easily
	 * adjusted via this method:
	 * 
	 * InternalTestHelper.setInternalUserNumber(100000);
	 * 
	 * 
	 * These tests can be modified to suit new solutions, just as long as the
	 * performance metrics at the end of the tests remains consistent.
	 * 
	 * These are performance metrics that we are trying to hit:
	 * 
	 * highVolumeTrackLocation: 100,000 users within 15 minutes:
	 * assertTrue(TimeUnit.MINUTES.toSeconds(15) >=
	 * TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()));
	 *
	 * highVolumeGetRewards: 100,000 users within 20 minutes:
	 * assertTrue(TimeUnit.MINUTES.toSeconds(20) >=
	 * TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()));
	 */

	@BeforeAll
	public static void initExecutorService() {

		log.info("Initializing ExecutorService");
		executor = Executors.newFixedThreadPool(150);
	}

	@Test
	public void highVolumeTrackLocation() {

		GpsUtil gpsUtil = new GpsUtil();
		RewardCentral rewardCentral = new RewardCentral();
		RewardsService rewardsService = new RewardsService(gpsUtil, rewardCentral, executor);
		// Users should be incremented up to 100,000, and test finishes within 15
		// minutes
		InternalTestHelper.setInternalUserNumber(100000);
		tourGuideService = new TourGuideService(gpsUtil, rewardsService, executor, false);
		// tourGuideService.testMode = true;

		List<User> allUsers = tourGuideService.getAllUsers();
		tourGuideService.setAllUsers(allUsers);

		log.info("Tracking {} users...", allUsers.size());
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();

		List<List<User>> userBatches = partitionList(allUsers, 1000);

		for (List<User> batch : userBatches) {
			List<CompletableFuture<VisitedLocation>> futures = batch.stream()
					.map(user -> tourGuideService.trackUserLocation(user))
					.collect(Collectors.toList());

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).exceptionally(ex -> {
				log.error("Error tracking user locations in batch: {}", ex.getMessage());
				return null;
			}).join();
		}

		stopWatch.stop();

		log.info("===== FINAL RESULT =====");
		log.info("highVolumeTrackLocation: Time Elapsed: "
				+ TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()) + " seconds.");
		assertTrue(TimeUnit.MINUTES.toSeconds(15) >= TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()));
	}

	@Test
	public void highVolumeGetRewards() {

		// Users should be incremented up to 100,000, and test finishes within 20
		// minutes

		GpsUtil gpsUtil = new GpsUtil();
		RewardCentral rewardCentral = new RewardCentral();
		RewardsService rewardsService = new RewardsService(gpsUtil, rewardCentral, executor);

		rewardsService.setMaxAttractionsToCheck(5);

		InternalTestHelper.setInternalUserNumber(100000);
		tourGuideService = new TourGuideService(gpsUtil, rewardsService, executor, false);
		// tourGuideService.testMode = true;

		Attraction attraction = gpsUtil.getAttractions().get(0);
		List<User> allUsers = tourGuideService.getAllUsers();
		tourGuideService.setAllUsers(allUsers);
		allUsers.forEach(u -> u.addToVisitedLocations(new VisitedLocation(u.getUserId(), attraction, new Date())));

		log.info("Tracking {} users...", allUsers.size());
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();

		List<List<User>> userBatches = partitionList(allUsers, 1000);

		for (List<User> batch : userBatches) {
			List<CompletableFuture<Void>> futures = batch.stream()
					.map(user -> {
						return CompletableFuture.runAsync(() -> rewardsService.calculateRewards(user), executor);
					}).collect(Collectors.toList());

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).exceptionally(ex -> {

				log.error("Error calculatingt rewards for users: {}", ex.getMessage());
				return null;
			})
					.thenRun(() -> log.info("All rewards calculated")).join();

		}
		stopWatch.stop();

		log.info("===== FINAL RESULT =====");
		log.info("highVolumeGetRewards: Time Elapsed: " + TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime())
				+ " seconds.");
		assertTrue(TimeUnit.MINUTES.toSeconds(20) >= TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()));
	}

	private List<List<User>> partitionList(List<User> allUsers, int size) {
		List<List<User>> partitions = new ArrayList<>();
		for (int i = 0; i < allUsers.size(); i += size) {
			partitions.add(List.copyOf(allUsers.subList(i, Math.min(i + size, allUsers.size()))));
		}
		return partitions;
	}

	@AfterAll
	public static void shutdownResources() throws InterruptedException {

		if (tourGuideService != null) {
			tourGuideService.shutdown();
		}
		if (executor != null && !executor.isShutdown()) {
			executor.shutdown();
			try {
				if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
					executor.shutdownNow();
				}
			} catch (InterruptedException e) {
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}

	}

}
