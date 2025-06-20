package com.openclassrooms.tourguide.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.StopWatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.service.RewardsService;
import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;

import gpsUtil.GpsUtil;
import lombok.extern.slf4j.Slf4j;
import rewardCentral.RewardCentral;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gpsUtil.location.VisitedLocation;

@Slf4j
public class TestTrackLocationPerformance {

    private static TourGuideService tourGuideService;
    private static ExecutorService executor;

    @BeforeAll
    public static void initExecutorService() {
        log.info("Initializing ExecutorService");
        executor = Executors.newFixedThreadPool(300);
    }

    private List<List<User>> partitionList(List<User> allUsers, int size) {
        List<List<User>> partitions = new ArrayList<>();
        for (int i = 0; i < allUsers.size(); i += size) {
            partitions.add(List.copyOf(allUsers.subList(i, Math.min(i + size, allUsers.size()))));
        }
        return partitions;
    }

    @Test
    public void highVolumeTrackLocation() {
        GpsUtil gpsUtil = new GpsUtil();
        RewardCentral rewardCentral = new RewardCentral();
        RewardsService rewardsService = new RewardsService(gpsUtil, rewardCentral, executor);

        InternalTestHelper.setInternalUserNumber(100000);
        tourGuideService = new TourGuideService(gpsUtil, rewardsService, executor, false);

        List<User> allUsers = tourGuideService.getAllUsers();
        tourGuideService.setAllUsers(allUsers);

        log.info("Tracking {} users...", allUsers.size());
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        List<List<User>> userBatches = partitionList(allUsers, 10000);

        for (List<User> batch : userBatches) {

            List<CompletableFuture<VisitedLocation>> futures = new ArrayList<>();

            for (User user : batch) {

                futures.add(tourGuideService.trackUserLocation(user));
            }
            futures.forEach(CompletableFuture::join);
        }

        stopWatch.stop();

        log.info("===== FINAL RESULT =====");
        log.info("highVolumeTrackLocation: Time Elapsed: "
                + TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()) + " seconds.");
        assertTrue(TimeUnit.MINUTES.toSeconds(15) >= TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()));
    }

    @AfterAll
    public static void shutdownResources() {
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
