package com.openclassrooms.tourguide.controller;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openclassrooms.tourguide.dto.NearbyAttractionDTO;
import com.openclassrooms.tourguide.service.RewardsService;
import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import gpsUtil.location.VisitedLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tripPricer.Provider;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TourGuideController {

    private final TourGuideService tourGuideService;
    private final RewardsService rewardsService;

    @RequestMapping("/")
    public String index() {
        return "Greetings from TourGuide!";
    }

    @RequestMapping("/getLocation")
    public VisitedLocation getLocation(@RequestParam String userName) {
        return tourGuideService.getUserLocation(getUser(userName));
    }

    @RequestMapping("/getNearbyAttractions")
    public List<NearbyAttractionDTO> getNearbyAttractions(@RequestParam String userName) {
        User user = getUser(userName);
        VisitedLocation visitedLocation = tourGuideService.getUserLocation(user);

        return tourGuideService.getNearByAttractions(visitedLocation).stream()
                .map(attraction -> new NearbyAttractionDTO(
                        attraction.attractionName,
                        attraction.latitude,
                        attraction.longitude,
                        visitedLocation.location.latitude,
                        visitedLocation.location.longitude,
                        rewardsService.getDistance(attraction, visitedLocation.location),
                        rewardsService.getRewardPoints(attraction, user)))
                .sorted(Comparator.comparingDouble(dto -> dto.distance))
                .limit(5)
                .collect(Collectors.toList());
    }

    @RequestMapping("/getRewards")
    public List<UserReward> getRewards(@RequestParam String userName) {
        return tourGuideService.getUserRewards(getUser(userName)).parallelStream()
                .collect(Collectors.toList());
    }

    @RequestMapping("/getTripDeals")
    public List<Provider> getTripDeals(@RequestParam String userName) {
        return tourGuideService.getTripDeals(getUser(userName)).parallelStream()
                .collect(Collectors.toList());
    }

    private User getUser(String userName) {
        return tourGuideService.getUser(userName);
    }

    @RequestMapping("/addUser")
    public String addUser(@RequestParam String userName, @RequestParam String phoneNumber, @RequestParam String email) {
        if (tourGuideService.getUser(userName) != null) {
            return "User " + userName + " already exists";
        }

        User user = new User(UUID.randomUUID(), userName, phoneNumber, email);
        tourGuideService.addUser(user);

        log.info("Utilisateur ajouté via l'API : " + userName);
        return "User " + userName + " added successfully";
    }
}
