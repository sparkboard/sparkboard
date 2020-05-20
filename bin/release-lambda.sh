#! /bin/bash

# usage:
# bin/release-lambda.sh <dev, staging, prod>

# validate environment

if [ "$1" == "" ]; then echo "must pass an environment name (eg. dev, staging, prod, matt, dave, ...)" && exit; fi

# install deps
yarn install

# compile
yarn shadow-cljs release lambda

# package and deploy
STACK="sparkboard-lambda-$1"
sam build
sam deploy --stack-name "$STACK" --s3-prefix s3_prefix = "$STACK"