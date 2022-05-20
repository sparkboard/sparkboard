FROM clojure as build

WORKDIR /app
COPY . .

RUN clojure -P
RUN clojure -A:build

FROM --platform=linux/amd64 openjdk:17-alpine

COPY --from=build /app/target/sparkboard.jar sparkboard.jar

# Open the port
ENV PORT "8080"

# Run the jar
CMD java -jar sparkboard.jar