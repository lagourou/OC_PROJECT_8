package com.openclassrooms.tourguide.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import tripPricer.Provider;
import tripPricer.TripPricer;

@Service
public class TourGuideService {

	private final ExecutorService executor;
	private List<User> allUsers;
	private final Map<String, User> internalUserMap = new ConcurrentHashMap<>();
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	private final boolean startTracker;

	private final Cache<UUID, VisitedLocation> locationCache = Caffeine.newBuilder()
			.expireAfterWrite(5, TimeUnit.MINUTES)
			.maximumSize(500_000)
			.build();

	private static final String TRIP_PRICER_API_KEY = "test-server-api-key";
	private final boolean testMode = true;

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService, ExecutorService executorService,
			@Value("${tourguide.startTracker:true}") boolean startTracker) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;
		this.executor = executorService;
		this.startTracker = startTracker;

		Locale.setDefault(Locale.US);

		if (testMode) {
			initializeInternalUsers();
		}
		this.tracker = startTracker ? new Tracker(this) : null;
		if (startTracker) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> tracker.stopTracking()));
		}
	}

	public void setAllUsers(List<User> allUsers) {
		if (allUsers == null) {
			throw new IllegalArgumentException("User list must not be null.");
		}
		if (allUsers.isEmpty()) {
			throw new IllegalStateException("User list is empty. Cannot initialize users.");
		}
		this.allUsers = new ArrayList<>(allUsers);
	}

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
		return (!user.getVisitedLocations().isEmpty()) ? user.getLastVisitedLocation() : trackUserLocation(user).join();
	}

	public CompletableFuture<VisitedLocation> trackUserLocation(User user) {
		return CompletableFuture.supplyAsync(() -> {
			VisitedLocation cachedLocation = locationCache.getIfPresent(user.getUserId());
			if (cachedLocation != null)
				return cachedLocation;

			VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());

			user.addToVisitedLocations(visitedLocation);
			List<Attraction> attractions = gpsUtil.getAttractions();
			rewardsService.calculateRewardsAsync(user, attractions);
			locationCache.put(user.getUserId(), visitedLocation);
			return visitedLocation;
		}, executor);
	}

	public void trackAllUsersLocations(List<User> users) {
		List<CompletableFuture<Void>> futures = users.stream()
				.map(user -> trackUserLocation(user).thenAccept(location -> {
				}))
				.toList();

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	}

	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {
		return gpsUtil.getAttractions().stream()
				.sorted(Comparator.comparingDouble(
						attraction -> rewardsService.getDistance(attraction, visitedLocation.location)))
				.limit(5)
				.collect(Collectors.toList());
	}

	public User getUser(String userName) {
		User user = internalUserMap.get(userName.trim());
		if (user == null) {
			throw new IllegalArgumentException("User " + userName + " not found");
		}
		return user;
	}

	public List<User> getAllUsers() {
		return new ArrayList<>(internalUserMap.values());
	}

	public void addUser(User user) {
		internalUserMap.putIfAbsent(user.getUserName(), user);
	}

	public List<Provider> getTripDeals(User user) {
		int rewardPoints = user.getUserRewards().stream().mapToInt(UserReward::getRewardPoints).sum();
		List<Provider> providers = tripPricer.getPrice(
				TRIP_PRICER_API_KEY,
				user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(),
				user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(),
				rewardPoints);
		user.setTripDeals(providers);
		return providers;
	}

	private void initializeInternalUsers() {
		int userCount = InternalTestHelper.getInternalUserNumber();
		for (int i = 0; i < userCount; i++) {
			String userName = "internalUser" + i;
			User user = new User(UUID.randomUUID(), userName, "000", userName + "@tourGuide.com");
			generateUserLocationHistory(user);
			internalUserMap.put(userName, user);
		}
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

	public void shutdown() {
		if (tracker != null) {
			tracker.stopTracking();
		}
	}

}
