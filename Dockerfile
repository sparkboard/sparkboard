# Use https://hub.docker.com/_/oracle-serverjre-8
FROM temurin-18-tools-deps-1.11.1.1113-alpine

# Make a directory
RUN mkdir -p /app
WORKDIR /app

# Copy only the target jar over
RUN clojure -A:build

# Open the port
ENV PORT "8080"

# Run the JAR
CMD java -jar target/sparkboard.jar