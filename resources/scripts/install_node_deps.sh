#!/bin/bash
set -euo pipefail

DEBUG=${DEBUG:-false}
STRICT_FROZEN=${STRICT_FROZEN:-false}
YARN_REGISTRY=${YARN_REGISTRY:-"https://registry.npmjs.org"}

log() { echo "[NodeJS-Install] $*"; }
debug() { [[ "$DEBUG" == "true" ]] && echo "[DEBUG] $*"; }

if [ -f "yarn.lock" ]; then
    LOCK_FILE="yarn.lock"
    TOOL="yarn"
elif [ -f "package-lock.json" ]; then
    LOCK_FILE="package-lock.json"
    TOOL="npm"
else
    log "WARN: No lockfile found. Using npm (no-lock mode)."
    TOOL="npm-no-lock"
fi

# คำนวณ Hash ของ Lockfile
if [ "$TOOL" == "npm-no-lock" ]; then
    LOCK_HASH="no-lock"
else
    LOCK_HASH=$(sha1sum "$LOCK_FILE" | awk '{print $1}')
fi

debug "Tool: $TOOL, Hash: $LOCK_HASH"

# เช็คว่าต้อง install ใหม่ไหม
if [ -d "node_modules" ] && [ -f "node_modules/.lockhash" ] && [ "$(cat node_modules/.lockhash)" == "$LOCK_HASH" ]; then
    log "node_modules matches $LOCK_FILE ($LOCK_HASH) -> Skipping install."
    exit 0
fi

# ล้างของเก่า
rm -rf node_modules || true

# เริ่ม Install
if [ "$TOOL" == "yarn" ]; then
    CACHE_DIR=".cache/yarn/${LOCK_HASH}"
    mkdir -p "$CACHE_DIR"
    export YARN_CACHE_FOLDER="$CACHE_DIR"
    export COREPACK_ENABLE=0

    yarn config set registry "$YARN_REGISTRY" >/dev/null

    log "Installing with Yarn..."
    if ! yarn install --frozen-lockfile --ignore-optional --prefer-offline --no-progress --network-timeout 600000; then
        log "Frozen lockfile failed."
        if [ "$STRICT_FROZEN" == "true" ]; then
            log "ERROR: strictFrozen=true -> Fail pipeline."
            exit 1
        fi
        log "Fallback -> yarn install (rewrite lockfile)"
        yarn install --ignore-optional --prefer-offline --no-progress --network-timeout 600000
    fi

elif [ "$TOOL" == "npm" ]; then
    CACHE_DIR=".cache/npm/${LOCK_HASH}"
    mkdir -p "$CACHE_DIR"
    npm config set cache "$CACHE_DIR" --location=project
    npm config set registry "https://registry.npmjs.org" --location=project
    
    log "Installing with npm ci..."
    npm ci --no-audit --progress=false

else
    # NPM No Lock
    mkdir -p .cache/npm/no-lock
    npm config set cache .cache/npm/no-lock --location=project
    npm install --no-audit --progress=false
fi

# บันทึก Hash ไว้เช็คครั้งหน้า
mkdir -p node_modules
echo "$LOCK_HASH" > node_modules/.lockhash
log "Install complete."