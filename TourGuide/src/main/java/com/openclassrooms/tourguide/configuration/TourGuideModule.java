package com.openclassrooms.tourguide.configuration;

import java.util.concurrent.ExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openclassrooms.tourguide.service.RewardsService;

import gpsUtil.GpsUtil;
import rewardCentral.RewardCentral;

@Configuration
public class TourGuideModule {

	@Bean
	public GpsUtil getGpsUtil() {
		return new GpsUtil();
	}

	@Bean
	public RewardsService getRewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral,
			ExecutorService threadPoolExecutor) {
		return new RewardsService(gpsUtil, rewardCentral, threadPoolExecutor);
	}

	@Bean
	public RewardCentral getRewardCentral() {
		return new RewardCentral();
	}

}
