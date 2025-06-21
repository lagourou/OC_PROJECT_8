package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import rewardCentral.RewardCentral;

@Service
@Slf4j
public class RewardsService {

	private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;
	private final int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private final int attractionProximityRange = 200;

	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;
	private final Map<String, Double> distanceCache = new ConcurrentHashMap<>();
	private final ExecutorService executor;

	private static final int MAX_THREADS = 64;
	private static final Semaphore semaphore = new Semaphore(MAX_THREADS);
	private int maxAttractionsToCheck = 10;
	private List<User> allUsers = new ArrayList<>();

	/**
	 * Creates a rewards management service with GPS and RewardCentral services, and
	 * a thread pool for asynchronous tasks.
	 *
	 * @param gpsUtil         Geolocation service
	 * @param rewardCentral   Service to get reward points
	 * @param executorService Executor for parallel tasks
	 * @throws IllegalStateException if the executor is null or already arrested
	 */
	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral, ExecutorService executorService) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
		this.executor = executorService;

		if (executor == null || executor.isShutdown()) {
			throw new IllegalStateException("ExecutorService must be initialized and active");
		}
	}

	/**
	 * Sets the maximum number of attractions to analyze to calculate rewards.
	 *
	 * @param maxAttractionsToCheck Limit on the number of attractions to be
	 *                              considered
	 */
	public void setMaxAttractionsToCheck(int maxAttractionsToCheck) {
		this.maxAttractionsToCheck = maxAttractionsToCheck;
	}

	/**
	 * Cleanly shuts down the thread pool if it is still active.
	 */
	@PreDestroy
	public void shutdownExecutor() {
		if (!executor.isShutdown()) {
			executor.shutdown();
		}

	}

	/**
	 * Replaces the list of service users with a new one.
	 *
	 * @param allUsers List of users to register
	 * @throws IllegalStateException if the list is empty or null
	 */
	public void setAllUsers(List<User> allUsers) {
		if (allUsers == null || allUsers.isEmpty()) {
			throw new IllegalStateException("User list is empty. Cannot initialize users.");
		}
		if (this.allUsers == null) {
			this.allUsers = new ArrayList<>();
		} else {
			this.allUsers.clear();
		}
		this.allUsers.addAll(allUsers);
	}

	/**
	 * Changes the maximum distance to consider an attraction as close.
	 *
	 * @param proximityBuffer Distance value to use
	 */
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	/**
	 * Resets the proximity distance to its default value.
	 */
	public void setDefaultProximityBuffer() {
		this.proximityBuffer = defaultProximityBuffer;
	}

	/**
	 * Calculates rewards for a user based on their past visits.
	 *
	 * @param user        Concerned user
	 * @param attractions List of available attractions
	 */
	public void calculateRewards(User user, List<Attraction> attractions) {

		if (Math.abs(user.getUserId().hashCode()) % 5000 == 0) {
			log.info("User: {}, visitedLocations: {}, attractionsToCheck: {}",
					user.getUserName(), user.getVisitedLocations().size(), attractions.size());
		}

		List<VisitedLocation> userLocations = user.getVisitedLocations();

		PriorityQueue<Attraction> closestAttractions = new PriorityQueue<>(
				Comparator.comparingDouble(
						attraction -> -cachedDistance(user.getLastVisitedLocation().location, attraction)));

		for (Attraction attraction : attractions) {
			closestAttractions.offer(attraction);
			if (closestAttractions.size() > maxAttractionsToCheck) {
				closestAttractions.poll();
			}
		}

		List<Attraction> attractionsToCheck = new ArrayList<>(closestAttractions);

		Set<UUID> rewardedAttractionIds = user.getUserRewards().stream()
				.map(r -> r.attraction.attractionId)
				.collect(Collectors.toSet());

		for (VisitedLocation visitedLocation : userLocations) {
			for (Attraction attraction : attractionsToCheck) {
				if (nearAttraction(visitedLocation, attraction)
						&& !rewardedAttractionIds.contains(attraction.attractionId)) {
					int points = getRewardPoints(attraction, user);
					user.addUserReward(new UserReward(visitedLocation, attraction, points));
					rewardedAttractionIds.add(attraction.attractionId);
				}
			}
		}
	}

	/**
	 * Calculates rewards for a user asynchronously (in the background).
	 *
	 * @param user        Concerned user
	 * @param attractions List of available attractions
	 * @return An asynchronous task representing the current computation
	 */
	public CompletableFuture<Void> calculateRewardsAsync(User user, List<Attraction> attractions) {
		return CompletableFuture.runAsync(() -> {
			try {

				semaphore.acquire();
				calculateRewards(user, attractions);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Thread interrupted", e);
			} finally {
				semaphore.release();
			}
		}, executor);
	}

	/**
	 * Calculates rewards for a list of users in parallel.
	 *
	 * @param users       User list
	 * @param attractions List of attractions to consider
	 */
	public void calculateRewardsForAllUsers(List<User> users, List<Attraction> attractions) {
		List<CompletableFuture<Void>> futures = users.stream()
				.map(user -> calculateRewardsAsync(user, attractions))

				.collect(Collectors.toList());

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

	}

	/**
	 * Creates a unique key to associate a geographic location with an attraction.
	 *
	 * Latitude and longitude coordinates are rounded to two decimal places to
	 * reduce precision and improve cache reuse.*
	 *
	 * @param location   Reference location
	 * @param attraction Attraction concerned
	 * @return A string representing the associated cache key
	 */
	private String getCacheKey(Location location, Attraction attraction) {

		double lat = Math.round(location.latitude * 100.0) / 100.0;
		double lon = Math.round(location.longitude * 100.0) / 100.0;
		return lat + "," + lon + "_" + attraction.attractionId;
	}

	/**
	 * Calculates the distance between a location and an attraction with caching.
	 *
	 * If the distance has already been calculated for this location and attraction
	 * combination, it is retrieved from the cache. Otherwise, it is calculated and
	 * stored.
	 *
	 * @param location   Reference location
	 * @param attraction Target attraction
	 * @return Distance between location and attraction (in miles)
	 */
	private double cachedDistance(Location location, Attraction attraction) {
		String key = getCacheKey(location, attraction);
		return distanceCache.computeIfAbsent(key, k -> getDistance(location, attraction));
	}

	/**
	 * Check if an attraction is close enough to a location.
	 *
	 * @param attraction Attraction to analyze
	 * @param location   Position à comparer
	 * @return true si la distance est inférieure à la limite, false sinon
	 */
	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) <= attractionProximityRange;
	}

	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}

	private final Cache<String, Integer> rewardPointsCache = Caffeine.newBuilder()
			.maximumSize(100_000)
			.expireAfterWrite(10, TimeUnit.MINUTES)
			.build();

	/**
	 * Returns the number of reward points earned by a user at a given attraction.
	 * Uses a cache to speed up the result.*
	 * 
	 * @param attraction The attraction visited
	 * @param user       The user concerned
	 * @return Number of points awarded
	 */
	public int getRewardPoints(Attraction attraction, User user) {
		String key = attraction.attractionName + ":" + user.getUserId();
		return rewardPointsCache.get(key, k -> Math.max(
				rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()),
				1));
	}

	/**
	 * Calculates the distance between two geographic locations.
	 *
	 * @param loc1 First point (latitude, longitude)
	 * @param loc2 Second point
	 * @return Distance between the two locations in miles
	 */
	public double getDistance(Location loc1, Location loc2) {
		double lat1 = Math.toRadians(loc1.latitude);
		double lon1 = Math.toRadians(loc1.longitude);
		double lat2 = Math.toRadians(loc2.latitude);
		double lon2 = Math.toRadians(loc2.longitude);

		double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
				+ Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

		double nauticalMiles = 60 * Math.toDegrees(angle);
		return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
	}
}