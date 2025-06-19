package com.openclassrooms.tourguide.unitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import tripPricer.Provider;

@Slf4j
public class TestTourGuideService {

	private static TourGuideService tourGuideService;

	private static ExecutorService executor;

	private static GpsUtil gpsUtil;
	private static RewardCentral rewardCentral;
	private RewardsService rewardsService;

	@BeforeAll
	public static void initLogger() {
		gpsUtil = new GpsUtil();
		rewardCentral = new RewardCentral();

		log.info("Initializing ExecutorService");
		executor = Executors.newFixedThreadPool(100);

	}

	@BeforeEach
	public void initServices() {
		InternalTestHelper.setInternalUserNumber(0);
		rewardsService = new RewardsService(gpsUtil, rewardCentral, executor);
		tourGuideService = new TourGuideService(gpsUtil, rewardsService, executor, false);
	}

	@Test
	public void getUserLocation() {
		User user = new User(UUID.randomUUID(), "jon", "000", "jon@tourGuide.com");
		CompletableFuture<VisitedLocation> visitedLocation = tourGuideService.trackUserLocation(user);
		assertTrue(visitedLocation.join().userId.equals(user.getUserId()));
	}

	@Test
	public void addUser() {
		User user = new User(UUID.randomUUID(), "jon", "000", "jon@tourGuide.com");
		User user2 = new User(UUID.randomUUID(), "jon2", "000", "jon2@tourGuide.com");

		tourGuideService.addUser(user);
		tourGuideService.addUser(user2);

		User retrivedUser = tourGuideService.getUser(user.getUserName());
		User retrivedUser2 = tourGuideService.getUser(user2.getUserName());

		assertEquals(user, retrivedUser);
		assertEquals(user2, retrivedUser2);
	}

	@Test
	public void getAllUsers() {
		User user = new User(UUID.randomUUID(), "jon", "000", "jon@tourGuide.com");
		User user2 = new User(UUID.randomUUID(), "jon2", "000", "jon2@tourGuide.com");

		tourGuideService.addUser(user);
		tourGuideService.addUser(user2);

		List<User> allUsers = tourGuideService.getAllUsers();

		tourGuideService.setAllUsers(allUsers);

		assertTrue(allUsers.contains(user));
		assertTrue(allUsers.contains(user2));
	}

	@Test
	public void trackUser() {
		User user = new User(UUID.randomUUID(), "jon", "000", "jon@tourGuide.com");
		CompletableFuture<VisitedLocation> visitedLocation = tourGuideService.trackUserLocation(user);

		assertEquals(user.getUserId(), visitedLocation.join().userId);
	}

	@Test
	public void getNearbyAttractions() {
		User user = new User(UUID.randomUUID(), "jon", "000", "jon@tourGuide.com");
		CompletableFuture<VisitedLocation> visitedLocation = tourGuideService.trackUserLocation(user);

		log.info("Number of attractions available : " + gpsUtil.getAttractions().size());

		List<Attraction> attractions = tourGuideService.getNearByAttractions(visitedLocation.join());

		assertTrue(attractions.size() <= 5);
	}

	@Test
	public void getTripDeals() {
		User user = new User(UUID.randomUUID(), "jon", "000", "jon@tourGuide.com");
		user.getUserPreferences().setNumberOfAdults(2);
		user.getUserPreferences().setNumberOfChildren(2);
		user.getUserPreferences().setTripDuration(7);

		List<Provider> providers = tourGuideService.getTripDeals(user);

		assertTrue(!providers.isEmpty() && providers.size() <= 10,
				"The number of suppliers should be between 1 and 10, but was : " + providers.size());
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
