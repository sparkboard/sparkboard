#! /bin/bash

yarn
yarn shadow-cljs release app
yarn install --production

rm archive.zip 
zip -r archive.zip main.js ../node_modules/
# TODO send to s3 or lambda

# restore node_modules
yarn

# TODO https://stackoverflow.com/questions/34437900/how-to-load-npm-modules-in-aws-lambda/53450086#53450086
