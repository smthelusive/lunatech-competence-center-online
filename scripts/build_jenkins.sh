#!/usr/bin/env bash

set -e
set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $DIR/..

# VALIDATE CC SCHEMA
scripts/cc_check_dependencies.sh
cd core-curriculum
sbt clean validateSchema
cd ..
# END

# BUILD BACKEND & DOCKER
cd backend
sbt clean buildFrontend docker:stage
# END
