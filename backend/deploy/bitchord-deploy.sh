#!/usr/bin/env bash
# Pulls main and rebuilds. The GitHub deploy key is pinned to this script
# (forced command), so a leaked key can do nothing but redeploy main.
set -euo pipefail
SRC=/opt/bitchord-src
# Set REPO_URL to the repository this server should build from.
REPO_URL="${REPO_URL:?set REPO_URL to your repository, e.g. https://github.com/you/muses.git}"
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch main "$REPO_URL" "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin main
git -C "$SRC" reset --hard FETCH_HEAD
echo "deploying $(git -C "$SRC" log -1 --format='%h %s')"
DOMAIN="$(cat /etc/bitchord-domain)" bash "$SRC/backend/deploy/setup.sh"
