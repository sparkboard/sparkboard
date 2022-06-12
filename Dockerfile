FROM openjdk:17-alpine
WORKDIR /app

COPY target/sparkboard.jar target/sparkboard.jar
CMD java -jar target/sparkboard.jar