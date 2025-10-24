#!/bin/sh

docker build -f api/Dockerfile -t kling-express-api .

docker login
docker tag kling-express-api:latest akang943578/kling-express-api:latest
docker push akang943578/kling-express-api:latest
