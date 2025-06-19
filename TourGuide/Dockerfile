FROM maven:3.9.9-ibm-semeru-21-noble

WORKDIR /app

COPY . .

RUN mvn clean package

FROM openjdk:26-jdk

WORKDIR /app

ARG JAR_FILE=target/*jar

COPY ${JAR_FILE} /app/tourguide.jar

ENTRYPOINT [ "java","-jar", "/app/tourguide.jar" ]

