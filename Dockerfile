# on M1 mac, add --platform=linux/amd64
FROM --platform=linux/amd64 openjdk:17-alpine
# FROM openjdk:17-alpine

WORKDIR /app

COPY target/sparkboard.jar target/sparkboard.jar
CMD java -jar target/sparkboard.jar