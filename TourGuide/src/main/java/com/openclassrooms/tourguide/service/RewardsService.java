package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

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
	private List<User> allUsers = Collections.synchronizedList(new ArrayList<>());

	private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors() * 4;
	private static final Semaphore semaphore = new Semaphore(MAX_THREADS);
	private int maxAttractionsToCheck = 10;

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
		executor.shutdown();
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

	public void calculateRewards(User user) {

		if (user.getUserId().hashCode() % 5000 == 0) {
			log.info("Processing rewards for user: {}", user.getUserName());
		}

		List<VisitedLocation> userLocations = new ArrayList<>(user.getVisitedLocations());
		List<Attraction> attractions = gpsUtil.getAttractions();

		List<Attraction> attractionsToCheck = attractions.stream()
				.sorted(Comparator.comparingDouble(
						attraction -> cachedDistance(user.getLastVisitedLocation().location, attraction)))
				.limit(Math.max(1, maxAttractionsToCheck))
				.collect(Collectors.toList());

		for (VisitedLocation visitedLocation : userLocations) {
			for (Attraction attraction : attractionsToCheck) {
				if (nearAttraction(visitedLocation, attraction)) {
					boolean alreadyRewarded = user.getUserRewards().stream()
							.anyMatch(r -> r.attraction.attractionId.equals(attraction.attractionId));
					if (!alreadyRewarded) {
						int points = getRewardPoints(attraction, user);
						user.addUserReward(new UserReward(visitedLocation, attraction, points));
					}
				}
			}
		}
	}

	public CompletableFuture<Void> calculateRewardsAsync(User user) {
		return CompletableFuture.runAsync(() -> {
			try {
				semaphore.acquire();
				calculateRewards(user);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Thread interrupted", e);
			} finally {
				semaphore.release();
			}
		}, executor);
	}

	private double cachedDistance(Location location, Attraction attraction) {
		return distanceCache.computeIfAbsent(attraction.attractionName,
				key -> getDistance(location, attraction));
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) <= attractionProximityRange;
	}

	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}

	public int getRewardPoints(Attraction attraction, User user) {
		return Math.max(rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()), 1);
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