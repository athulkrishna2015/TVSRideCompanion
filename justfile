# Task runner. `just --list` to see recipes.

# Bump version (major|minor|patch, default patch): updates versionName +
# versionCode, commits, tags vX.Y.Z, and pushes. The tag push triggers the
# CI release job, which publishes the APK to a GitHub Release.
bump part="patch":
    bash tools/bump-version.sh {{part}}

# Preview a bump without changing anything.
bump-dry part="patch":
    DRY_RUN=1 bash tools/bump-version.sh {{part}}
