FROM openjdk:17-alpine
WORKDIR /app

COPY target/sparkboard.jar sparkboard.jar
CMD java -jar sparkboard.jar