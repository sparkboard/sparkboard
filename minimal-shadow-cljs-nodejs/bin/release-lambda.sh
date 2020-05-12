#! /bin/bash

yarn
yarn shadow-cljs release app
yarn install --production

rm archive.zip 
zip -r archive.zip main.js ../node_modules/
# TODO send to s3 or lambda
