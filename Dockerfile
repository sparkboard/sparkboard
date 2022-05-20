# Use https://hub.docker.com/_/oracle-serverjre-8
FROM openjdk:17-alpine

# Make a directory
RUN mkdir -p /app
WORKDIR /app

# Copy only the target jar over
COPY target/sparkboard.jar .

# Open the port
ENV PORT "8080"

# Run the JAR
CMD java -jar sparkboard.jar