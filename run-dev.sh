#!/usr/bin/env bash
# Launch RuneLite in DEVELOPER MODE with the PriceCheck plugin sideloaded.
# Reuses the client the Jagex Launcher already downloaded (~/.runelite/repository2)
# plus a full runelite-api jar so DevTools (auto-enabled by --developer-mode) finds
# VarbitID. Build the plugin first (gradle shadowJar) and copy it into
# ~/.runelite/sideloaded-plugins/.
set -e
JAVA="${JAVA:-/opt/homebrew/opt/openjdk@11/bin/java}"
REPO="$HOME/.runelite/repository2"
API="$HOME/.runelite/dev-gameval.jar"
CP="$(ls "$REPO"/*.jar | tr '\n' ':' | sed 's/:$//')"
exec "$JAVA" \
  -ea -XX:+DisableAttachMechanism -Xmx2g -Xss2m -XX:CompileThreshold=1500 \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED \
  -Dsun.java2d.metal=false -Dsun.java2d.opengl=true \
  -Dapple.awt.application.appearance=system \
  -cp "$CP" net.runelite.client.RuneLite --developer-mode "$@"
