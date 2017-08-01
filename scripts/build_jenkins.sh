#!/usr/bin/env bash

set -e
set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $DIR/..
# BUILD FRONTEND TODO, how are we going to do this properly?
cd cv-editor
bower link
cd ..
cd frontend
bower link cv-editor && bower install
polymer build
# END

# BUILD BACKEND & DOCKER
cd ../backend
sbt clean buildFrontend docker:stage
# END

# ADD FRONTEND FILES TO DOCKER
mkdir -p target/docker/stage/opt/docker/frontend
cd ../frontend
mv -f build/default/** ../backend/target/docker/stage/opt/docker/frontend/

# VALIDATE CC SCHEMA
cd ../core-curriculum
sbt clean validateSchema
# END
