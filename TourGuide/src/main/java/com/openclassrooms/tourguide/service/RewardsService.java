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

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral, ExecutorService executorService) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
		this.executor = executorService;

		if (executor == null || executor.isShutdown()) {
			throw new IllegalStateException("ExecutorService must be initialized and active");
		}
	}

	public void setMaxAttractionsToCheck(int maxAttractionsToCheck) {
		this.maxAttractionsToCheck = maxAttractionsToCheck;
	}

	@PreDestroy
	public void shutdownExecutor() {
		if (!executor.isShutdown()) {
			executor.shutdown();
		}

	}

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

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	public void setDefaultProximityBuffer() {
		this.proximityBuffer = defaultProximityBuffer;
	}

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

	public void calculateRewardsForAllUsers(List<User> users, List<Attraction> attractions) {
		List<CompletableFuture<Void>> futures = users.stream()
				.map(user -> calculateRewardsAsync(user, attractions))

				.collect(Collectors.toList());

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

	}

	private String getCacheKey(Location location, Attraction attraction) {

		double lat = Math.round(location.latitude * 100.0) / 100.0;
		double lon = Math.round(location.longitude * 100.0) / 100.0;
		return lat + "," + lon + "_" + attraction.attractionId;
	}

	private double cachedDistance(Location location, Attraction attraction) {
		String key = getCacheKey(location, attraction);
		return distanceCache.computeIfAbsent(key, k -> getDistance(location, attraction));
	}

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

	public int getRewardPoints(Attraction attraction, User user) {
		String key = attraction.attractionName + ":" + user.getUserId();
		return rewardPointsCache.get(key, k -> Math.max(
				rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()),
				1));
	}

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