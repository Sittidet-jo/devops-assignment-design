#!/bin/bash
STRICT=${STRICT:-false}
CHECK_DOCKER=${CHECK_DOCKER:-false}

fails=0

check() {
    local name=$1
    local cmd=$2
    local must=$3
    
    echo -n "Checking $name... "
    if version=$($cmd 2>&1); then
        short_ver=$(echo "$version" | head -n1 | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -n1)
        echo "OK ($short_ver)"
    else
        if [ "$must" == "true" ]; then
            echo "MISSING (Required)"
            fails=$((fails + 1))
        else
            echo "MISSING (Optional)"
        fi
    fi
}

echo "=== Tooling Check ==="
check "git" "git --version" true
check "curl" "curl --version" true
check "jq" "jq --version" true
check "python3" "python3 --version" true
check "node" "node -v" true
check "npm" "npm -v" true
check "docker" "docker version --format {{.Client.Version}}" true
check "kustomize" "kustomize version" true
check "trivy" "trivy --version" true
check "yq" "yq --version" true

# Docker Socket Check
if [ "$CHECK_DOCKER" == "true" ]; then
    echo -n "Checking Docker Socket... "
    if [ -S /var/run/docker.sock ]; then
        echo "OK"
    else
        echo "MISSING"
    fi
fi

if [ $fails -gt 0 ]; then
    echo "Total Missing Required Tools: $fails"
    if [ "$STRICT" == "true" ]; then
        echo "Strict mode enabled. Exiting..."
        exit 1
    fi
else
    echo "All required tools are present."
fi