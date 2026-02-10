#!/bin/bash
set -e

GITHUB_URL="https://github.com/vgaj/proxy.git"
GITLAB_URL="https://gitlab.com/viru7/proxy.git"
TEMP_DIR=$(mktemp -d)

trap "rm -rf $TEMP_DIR" EXIT

# Bare clone from GitHub (gets all branches and tags)
git clone --bare "$GITHUB_URL" "$TEMP_DIR/proxy.git"

cd "$TEMP_DIR/proxy.git"

# Push everything to GitLab
git push --mirror "$GITLAB_URL"
