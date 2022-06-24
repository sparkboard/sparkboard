# on M1 mac, add --platform=linux/amd64
FROM --platform=linux/amd64 openjdk:17-alpine
# FROM openjdk:17-alpine

WORKDIR /app

RUN apk add --update-cache mongodb-tools

COPY target/sparkboard.jar target/sparkboard.jar

CMD java --add-opens=java.base/java.nio=ALL-UNNAMED \
         --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
         -jar target/sparkboard.jar
