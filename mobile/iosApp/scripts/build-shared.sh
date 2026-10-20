#!/bin/sh
set -eu
cd "$SRCROOT/.."
sh ./gradlew :shared:embedAndSignAppleFrameworkForXcode
