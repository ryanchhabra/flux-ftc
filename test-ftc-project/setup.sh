#!/bin/bash
# Recreates the FTC test project used to verify Flux.
#
# The FTC Robot Controller SDK is FIRST's repo (~300 MB with history), so it is NOT committed here.
# This script clones it, applies the Flux wiring, and drops in the test OpModes from fixtures/.
#
#   ./test-ftc-project/setup.sh
#
# Afterwards:
#   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
#   export ANDROID_HOME=$HOME/Library/Android/sdk
#   cd test-ftc-project/sdk
#   ./gradlew :TeamCode:installDebug   # once, to get the Flux runtime onto the device
#   ./gradlew :TeamCode:fluxDeploy     # from then on
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="$HERE/sdk"
PINNED="$(cat "$HERE/fixtures/sdk-commit.txt" 2>/dev/null || echo "")"

if [ -d "$SDK" ]; then
  echo "sdk/ already exists -- delete it first if you want a clean checkout."
  exit 1
fi

echo "Cloning the FTC Robot Controller SDK..."
git clone https://github.com/FIRST-Tech-Challenge/FtcRobotController.git "$SDK"

# Pin to the SDK commit Flux was last verified against. Newer is usually fine, but if something
# breaks, checking out this exact commit tells you whether the SDK changed under you.
if [ -n "$PINNED" ]; then
  echo "Checking out verified SDK commit $PINNED"
  git -C "$SDK" checkout --quiet "$PINNED" || echo "  (couldn't check out $PINNED; staying on default branch)"
fi

echo "Applying Flux wiring (composite build + runtime dependency + plugin)..."
git -C "$SDK" apply "$HERE/fixtures/wiring.patch"

echo "Installing test OpModes..."
cp "$HERE"/fixtures/teamcode/* "$SDK/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/"

# local.properties tells Gradle where the Android SDK lives; it is machine-specific and gitignored.
if [ -n "$ANDROID_HOME" ]; then
  echo "sdk.dir=$ANDROID_HOME" > "$SDK/local.properties"
elif [ -d "$HOME/Library/Android/sdk" ]; then
  echo "sdk.dir=$HOME/Library/Android/sdk" > "$SDK/local.properties"
else
  echo "NOTE: set ANDROID_HOME, or write sdk.dir=<path> into sdk/local.properties yourself."
fi

echo
echo "Done. See the header of this script for what to run next."
