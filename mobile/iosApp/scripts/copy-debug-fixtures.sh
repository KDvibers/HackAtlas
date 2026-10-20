#!/bin/sh
set -eu
# Canonical JSON belongs only to the Debug app bundle. Release never reads/copies it.
if [ "$CONFIGURATION" != "Debug" ]; then
    exit 0
fi
fixture_root="$SRCROOT/../../api/examples"
fixture_destination="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/compose-resources/composeResources/dev.hackatlas.mobile.resources/files/demo"
mkdir -p "$fixture_destination"
for fixture in "$fixture_root"/*.json; do
    [ "$(basename "$fixture")" = "manifest.json" ] && continue
    cp "$fixture" "$fixture_destination/"
done
