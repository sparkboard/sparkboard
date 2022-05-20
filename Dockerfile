FROM clojure as build

WORKDIR /app
COPY . .

RUN clojure -P -A:build
RUN bin/build

# on M1 mac, add --platform=linux/amd64
FROM openjdk:17-alpine

COPY --from=build /app/target/sparkboard.jar sparkboard.jar

# Open the port
ENV PORT "8080"

# Run the jar
CMD java -jar sparkboard.jar