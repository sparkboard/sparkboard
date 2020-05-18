#! /bin/bash

# usage:
# bin/release-lambda.sh <dev, staging, prod>

# validate environment
ENV=$(bin/bb -i "(#{\"dev\" \"staging\" \"prod\"} \"$1\")")
if [ "$ENV" == "" ]; then echo "must provide valid environment" && exit; fi

# install lambda deps
cd lambda && yarn install && cd ..

# compile
yarn shadow-cljs release lambda

# package and deploy
STACK="sparkboard-lambda-$1"
sam build
sam deploy --stack-name "$STACK" --s3-prefix s3_prefix = "$STACK"