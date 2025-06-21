# TourGuide – Visit Recommendation System

**TourGuide** is a backend application developed in Java using Spring Boot.
It allows you to track users, recommend the 5 nearest attractions (regardless of distance), and award them reward points based on their visit history.

## Technologies

- **Java 17** - Programming Language
- **Spring Boot 3.4.6** - Application Framework
- **JUnit** - Code Testing Tool
- **JaCoCo** - Java Code Coverage
- **Javadoc** - Automatic Documentation Generation for Java Code
- **Maven** - Dependency Management
- **Docker**: Containerization
- **GitHub Actions**: CI/CD pipeline

## Features

- **User Tracking**: Continuously locates and updates users' position
- **Nearby Attractions**: Suggests the nearest points of interest based on your current location
- **Rewards Management**: Award and record points for each attraction visited
- **Trip Deals**: Generate personalized trip deals based on user preferences
- **Performance Optimization**: Uses parallel processing to efficiently handle massive user volumes

## API Endpoints

- **`GET /home`**
  Simple greeting page

- **`GET /getLocation?userName={name}`**
  Retrieves the user’s current GPS location

- **`GET /getNearbyAttractions?userName={name}`**
  Lists the 5 closest tourist attractions to the user

- **`GET /getRewards?userName={name}`**
  Returns the user’s reward points

- **`GET /getAllCurrentLocations`**
  Returns locations for all tracked users

- **`GET /getTripDeals?userName={name}`**
  Provides personalized trip offers

## Documentation

The project documentation is hosted on GitHub Pages:

- [**JaCoCo Report**](https://lagourou.github.io/OC_PROJECT_8/site/jacoco/index.html) — test coverage
- [**JavaDoc**](https://lagourou.github.io/OC_PROJECT_8/site/apidocs/index.html) — generated Java code documentation
- [**Surefire Report**](https://lagourou.github.io/OC_PROJECT_8/site/surefire-report.html) — test execution results

## Project Architecture

```text

|   Dockerfile              # Docker configuration
|   pom.xml                 # Build tools and dependency management with Maven
|   readme.md               # Technical and functional project documentation
|   .github/workflows/      # CI/CD pipeline configurations
|
+---src
|   +---main
|   |   +---java
|   |   |   \---com
|   |   |       \---openclassrooms
|   |   |           \---tourguide
|   |   |               |   TourguideApplication.java        # Main application
|   |   |               |
|   |   |               +---configuration                    # Security Configuration
|   |   |               |       ExecutorConfig.java
|   |   |               |       TourGuideModule.java
|   |   |               |
|   |   |               +---controller                       # Receives requests and send responses
|   |   |               |       TourGuideController.java
|   |   |               |
|   |   |               +---dto                              # Data Transfer Objects
|   |   |               |       NearbyAttractionDTO.java
|   |   |               |
|   |   |               +---helper                           # Utility functions
|   |   |               |       InternalTestHelper.java
|   |   |               |
|   |   |               +---service                          # Business logic
|   |   |               |       RewardsService.java
|   |   |               |       TourGuideService.java
|   |   |               |
|   |   |               +---tracker                          # Location tracking
|   |   |               |       Tracker.java
|   |   |               |
|   |   |               \---user                             # User management
|   |   |                       User.java
|   |   |                       UserPreferences.java
|   |   |                       UserReward.java
|   |
|   \---test
|       +---java
|       |   \---com
|       |       \---openclassrooms
|       |           \---tourguide
|       |               |   TourguideApplicationTests.java            # Main test class
|       |               |
|       |               +---performance                               # Performance test
|       |               |       TestGetRewardsPerformance.java
|       |               |       TestTrackLocationPerformance.java
|       |               |
|       |               \---service                                   # Service test
|       |                       TestRewardsService.java
|       |                       TestTourGuideService.java

```

## External libraries

Three external JAR libraries, located in the libs/ directory, are used by the application.

1. **gpsUtil.jar**: Provides location tracking using GPS
2. **RewardCentral.jar**: Calculates rewards awarded to users
3. **TripPricer.jar**: Offers rates for travel packages

## CI/CD pipeline implementation

GitHub is used to automate continuous integration and deployment of the project :

### 1. Create a workflow file

In the repository, create the file **.github/workflows/cicd.yml**

### 2. Configure test execution and reporting

- Unit and performance tests using Maven and JUnit
- Generates test coverage reports via JaCoCo
- Builds JavaDocs for public API documentation

### 3. Build and deploy Docker image

After all tests pass, the application is:

- Packaged with Maven

- Dockerized via a Dockerfile in the project root

- Pushed to Docker Hub using your GitHub secrets (DOCKER_USERNAME, DOCKER_PASSWORD)

## Configuration and Installation

### System requirements

- Java 17
- Maven
- Git
- Docker

### Step 1 — Clone the repository

```bash
git clone https://github.com/lagourou/OC_PROJECT_8.git
cd OC_PROJECT_8/TourGuide
```

### Step 2 — Install external JARs into your local Maven repository

```bash
mvn install:install-file -Dfile=./TourGuide/libs/gpsUtil.jar -DgroupId=gpsUtil -DartifactId=gpsUtil -Dversion=1.0.0 -Dpackaging=jar
```

```bash
mvn install:install-file -Dfile=./TourGuide/libs/RewardCentral.jar -DgroupId=rewardCentral -DartifactId=rewardCentral -Dversion=1.0.0 -Dpackaging=jar
```

```bash
 mvn install:install-file -Dfile=./TourGuide/libs/TripPricer.jar -DgroupId=tripPricer -DartifactId=tripPricer -Dversion=1.0.0 -Dpackaging=jar
```

### Step 3 — Build the project

```bash
mvn clean install
```

## Docker configuration

1. Create a Dockerfile

   At the root of the TourGuide project, create a file named Dockerfile.

   Notice: The Dockerfile automatically compiles the application by running **mvn clean package**. You do not need to build the **.jar** manually before creating the Docker image.

2. Install Docker

   Install Docker on the Docker Desktop site: [https://www.docker.com/get-started/](https://www.docker.com/get-started/)

3. Build the Docker image

   In the TourGuide folder, run:

   ```bash
   docker build -t tourguide-docker .
   ```

4. Create the container

   ```bash
   docker run -p 80:8080 tourguide-docker
   ```

   Once started, the application is accessible at: [http://localhost:8080](http://localhost:8080)

   Notice: The Docker image is available publicly on Docker Hub at: [https://hub.docker.com/r/agourou84/p8](https://hub.docker.com/r/agourou84/p8)

## Tests

Run the tests with:

```bash
mvn test
```

Performance tests :

    - TestGetRewardsPerformance
    - TestTrackLocationPerformance

must respect this objective :

      - highVolumeTrackLocation → 100 000 users in less than 15 minutes
      - highVolumeGetRewards → 100 000 users in less than 20 minutes

### Jacoco

Generate the code coverage report with:

```bash
mvn jacoco:report
```

### Surefire

Generate the unit test display report with:

```bash
mvn surefire-report:report
```

Check the reports that are in **target/site/jacoco/** and **target/site/**

## Performance improvement

To ensure good performance, even with a large number of users (up to 100 000), several optimizations have been implemented:

### Parallelism

An `ExecutorService` with **300 threads** allows multiple tasks to be run in parallel, such as:

- Tracking user locations (`trackUserLocation`)

- Calculating rewards (`calculateRewards`)

It speeds up execution time

### Group treatment

Users are divided into **groups of 10,000** using the `partitionList` method.

This helps distribute the load more evenly and avoids memory overflow.

### Calculating rewards

A **Semaphore** controls concurrent execution by allowing a maximum of **64** tasks at a time, thus limiting CPU load.

### Reduce unnecessary calls with a cache

The **Caffeine** library is used to avoid redundant calculations:

- The **distances** between users and attractions are memorized (`cachedDistance`)

- The **reward points** are cached for each user/attraction pair (`rewardPointsCache`)

Add the 5 closest attractions relative to the user's last location

### Asynchronous and cached position tracking

Position tracking is done asynchronously using `CompletableFuture`.

Locations are then cached for 5 minutes using Caffeine, reducing calls to the `GpsUtil` service.

### Release of resources

At the end of heavy processing (ex: performance tests), the `ExecutorService` and the `Tracker` are **cleanly stopped** with `@AfterAll` or

`@PreDestroy`, to free up resources
