package com.openclassrooms.tourguide.unitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.VisitedLocation;
import lombok.extern.slf4j.Slf4j;
import rewardCentral.RewardCentral;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.service.RewardsService;
import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Slf4j
public class TestRewardsService {

	private static ExecutorService executor;

	private static TourGuideService tourGuideService;

	private final RewardCentral rewardCentral = new RewardCentral();

	@BeforeAll
	public static void initExecutorService() {

		log.info("Initializing ExecutorService");
		executor = Executors.newFixedThreadPool(100);

	}

	@Test
	public void userGetRewards() {
		GpsUtil gpsUtil = new GpsUtil();
		RewardsService rewardsService = new RewardsService(gpsUtil, rewardCentral, executor);

		InternalTestHelper.setInternalUserNumber(0);
		tourGuideService = new TourGuideService(gpsUtil, rewardsService, executor, false);

		User user = new User(UUID.randomUUID(), "jon", "000", "jon@tourGuide.com");
		Attraction attraction = gpsUtil.getAttractions().get(0);
		user.addToVisitedLocations(new VisitedLocation(user.getUserId(), attraction, new Date()));

		rewardsService.calculateRewards(user);

		List<UserReward> userRewards = user.getUserRewards();
		assertEquals(1, userRewards.size());

	}

	@Test
	public void isWithinAttractionProximity() {
		GpsUtil gpsUtil = new GpsUtil();
		RewardsService rewardsService = new RewardsService(gpsUtil, rewardCentral, executor);

		Attraction attraction = gpsUtil.getAttractions().get(0);
		assertTrue(rewardsService.isWithinAttractionProximity(attraction, attraction));
	}

	@Test
	public void nearAllAttractions() {
		GpsUtil gpsUtil = new GpsUtil();
		RewardsService rewardsService = new RewardsService(gpsUtil, new RewardCentral(),
				Executors.newFixedThreadPool(10));

		rewardsService.setProximityBuffer(5000);
		rewardsService.setMaxAttractionsToCheck(gpsUtil.getAttractions().size());

		User user = new User(UUID.randomUUID(), "testUser", "test@example.com", "000");
		user.addToVisitedLocations(
				new VisitedLocation(user.getUserId(), gpsUtil.getAttractions().get(0), new Date()));

		rewardsService.calculateRewards(user);

		int expectedRewardCount = gpsUtil.getAttractions().size();
		int actualRewardCount = user.getUserRewards().size();

		log.info("Expected: " + expectedRewardCount + ", Actual: " + actualRewardCount);
		assertEquals(expectedRewardCount, actualRewardCount);
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
