#! /bin/bash

STACK="sparkboard-lambda-$1"
yarn shadow-cljs release lambda
sam build
sam deploy --stack-name "$STACK" --s3-prefix s3_prefix = "$STACK"

#rm -f archive.zip
#zip -r archive.zip .aws-sam/build/SlackHandler/target/main.js .aws-sam/build/SlackHandler/node_modules/

# TODO send to s3 or lambda

# restore node_modules


# TODO https://stackoverflow.com/questions/34437900/how-to-load-npm-modules-in-aws-lambda/53450086#53450086
