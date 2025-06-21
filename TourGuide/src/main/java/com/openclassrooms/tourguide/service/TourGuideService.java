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

/**
 * Main service that manages the TourGuide app's features:
 * - Tracks user locations
 * - Assigns reward points
 * - Provides recommendations for nearby attractions
 * - Generates personalized travel offers
 */
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

	/**
	 * Builder of the main TourGuide service.
	 *
	 * Initializes the necessary components
	 *
	 * @param gpsUtil         User geolocation service
	 * @param rewardsService  Rewards Management Service
	 * @param executorService Thread pool for asynchronous processing
	 * @param startTracker    Indicates whether to enable automatic user tracking
	 *
	 */

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

	/**
	 * Defines a new user list for the service.
	 *
	 * This method replaces the current users with those provided in
	 * parameters
	 *
	 * The list must not be null or empty, otherwise an error is thrown.
	 *
	 * @param allUsers Liste des utilisateurs à enregistrer
	 * @throws IllegalArgumentException if the list is null
	 * @throws IllegalStateException    if the list is empty
	 */

	public void setAllUsers(List<User> allUsers) {
		if (allUsers == null) {
			throw new IllegalArgumentException("User list must not be null.");
		}
		if (allUsers.isEmpty()) {
			throw new IllegalStateException("User list is empty. Cannot initialize users.");
		}
		this.allUsers = new ArrayList<>(allUsers);
	}

	/**
	 * Returns the list of rewards associated with a given user.
	 *
	 * @param user User for whom you want to get the rewards
	 * @return List of reward points earned by this user
	 */

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	/**
	 * Returns the user's current location.
	 *
	 * If the user already has a saved location, it is returned.
	 * Otherwise, a new location is obtained in real time.
	 *
	 * @param user Concerned user
	 * @return Last known position or updated position
	 */

	public VisitedLocation getUserLocation(User user) {
		return (!user.getVisitedLocations().isEmpty()) ? user.getLastVisitedLocation() : trackUserLocation(user).join();
	}

	/**
	 * Starts location tracking for a given user.
	 *
	 * If a recent position is already available in the cache, it is used
	 * directly.
	 * Otherwise, a new position is retrieved from the GPS service, added to the
	 * user's history, and rewards are calculated in the background.
	 *
	 * @param user The user to locate
	 * @return A CompletableFuture containing the user's new (or old) position
	 */

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

	/**
	 * Starts location tracking for all provided users.
	 *
	 * Each user is located in parallel via asynchronous calls,
	 * and execution waits for all operations to complete before
	 * continuing.
	 *
	 * @param users List of users to follow
	 */

	public void trackAllUsersLocations(List<User> users) {
		List<CompletableFuture<Void>> futures = users.stream()
				.map(user -> trackUserLocation(user).thenAccept(location -> {
				}))
				.toList();

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	}

	/**
	 * Returns the 5 closest attractions to the given position.
	 *
	 * This method sorts all known attractions by distance
	 * from the user's current location, then returns the top 5.
	 *
	 * @param visitedLocation Current position of the user
	 * @return List of the 5 nearest attractions
	 */

	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {
		return gpsUtil.getAttractions().stream()
				.sorted(Comparator.comparingDouble(
						attraction -> rewardsService.getDistance(attraction, visitedLocation.location)))
				.limit(5)
				.collect(Collectors.toList());
	}

	/**
	 * Search for a user by username.
	 *
	 * If the user exists in the internal list, it is returned.
	 * Otherwise, an error is thrown.
	 *
	 * @param userName Username to search for
	 * @return The user corresponding to the given name
	 * @throws IllegalArgumentException if no user is found
	 */

	public User getUser(String userName) {
		User user = internalUserMap.get(userName.trim());
		if (user == null) {
			throw new IllegalArgumentException("User " + userName + " not found");
		}
		return user;
	}

	/**
	 * Returns the full list of registered users.
	 *
	 * This method returns a new list containing all users
	 * currently stored in internal memory.
	 *
	 * @return List of all known users
	 */

	public List<User> getAllUsers() {
		return new ArrayList<>(internalUserMap.values());
	}

	/**
	 * Adds a user to the internal list if it does not already exist.
	 *
	 * If a user with the same name is already registered, they will not be
	 * replaced.
	 *
	 * @param user The user to add
	 */

	public void addUser(User user) {
		internalUserMap.putIfAbsent(user.getUserName(), user);
	}

	/**
	 * Generate a list of personalized travel offers for a user.
	 *
	 * The number of reward points is taken into account to calculate
	 * offers tailored to the user's profile (adults, children,
	 * duration).
	 * The list is then saved to the user's account.
	 *
	 * @param user The user for whom we want to get offers
	 * @return List of suppliers with their travel offers
	 */

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

	/**
	 * Initializes a list of internal test users.
	 *
	 * The number of users to be created is determined by a configuration value.
	 * Each user receives a username, a unique name, and a history of simulated
	 * locations.
	 * These users are added to the service's internal map.
	 */

	private void initializeInternalUsers() {
		int userCount = InternalTestHelper.getInternalUserNumber();
		for (int i = 0; i < userCount; i++) {
			String userName = "internalUser" + i;
			User user = new User(UUID.randomUUID(), userName, "000", userName + "@tourGuide.com");
			generateUserLocationHistory(user);
			internalUserMap.put(userName, user);
		}
	}

	/**
	 * Generates a random location history for a user.
	 *
	 * This method adds three simulated locations with randomly generated
	 * coordinates
	 * and dates to create a movement history.
	 *
	 * @param user The user to add the positions to
	 */

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	/**
	 * Generates a random longitude between -180 and 180 degrees.
	 *
	 * This method is used to simulate geographic coordinates.
	 *
	 * @return A random longitude value
	 */

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	/**
	 * Generates a random latitude between -85.05 and 85.05 degrees.
	 *
	 * @return A random latitude value
	 */
	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	/**
	 * Creates a random date within the last 30 days.
	 *
	 * @return A recent random date
	 */
	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

	/**
	 * Stops user tracking if it is active.
	 */
	public void shutdown() {
		if (tracker != null) {
			tracker.stopTracking();
		}
	}

}
