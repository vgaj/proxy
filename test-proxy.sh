#!/bin/bash

# Proxy System Test Script
# Tests the server and Android app on an emulator

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

cleanup() {
    log_info "Cleaning up..."
    if [ -n "$SERVER_PID" ]; then
        kill $SERVER_PID 2>/dev/null || true
        wait $SERVER_PID 2>/dev/null || true
    fi
    # Kill any gradle daemon running the server
    pkill -f "ProxyServer" 2>/dev/null || true
}

trap cleanup EXIT

# Check for emulator
log_info "Checking for connected emulator..."
if ! adb devices | grep -q "device$"; then
    log_error "No Android device/emulator connected"
    log_info "Start an emulator with: emulator -avd <avd_name>"
    exit 1
fi
DEVICE=$(adb devices | grep "device$" | head -1 | cut -f1)
log_info "Found device: $DEVICE"

# Kill any existing server
log_info "Stopping any existing server..."
pkill -f "ProxyServer" 2>/dev/null || true
sleep 1

# Start the server
log_info "Starting proxy server..."
./gradlew run -p server > /tmp/proxy-server.log 2>&1 &
SERVER_PID=$!

# Wait for server to start (check ports)
log_info "Waiting for server to start..."
for i in {1..30}; do
    if ss -tlnp 2>/dev/null | grep -q ":8888.*:9999" || \
       (ss -tlnp 2>/dev/null | grep -q ":8888" && ss -tlnp 2>/dev/null | grep -q ":9999"); then
        log_info "Server is listening on ports 8888 and 9999"
        break
    fi
    if [ $i -eq 30 ]; then
        log_error "Server failed to start within 30 seconds"
        cat /tmp/proxy-server.log
        exit 1
    fi
    sleep 1
done

# Build and install the app
log_info "Building and installing Android app..."
if ! ./gradlew installDebug -p app; then
    log_error "Failed to build/install app"
    exit 1
fi

# Launch the app
log_info "Launching app on emulator..."
adb shell am start -n com.github.vgaj.proxy/.MainActivity
sleep 2

# Check current UI state and configure if needed
log_info "Checking app configuration..."
adb shell uiautomator dump /sdcard/ui_test.xml 2>/dev/null || true
UI_CONTENT=$(adb shell cat /sdcard/ui_test.xml 2>/dev/null | tr -d '\r')

# Check if app is already running
if echo "$UI_CONTENT" | grep -q 'Status: Running'; then
    log_info "App is already running"
else
    # Extract host value - text attribute comes BEFORE resource-id in XML
    # Format: <node ... text="value" resource-id="...etServerHost" ...>
    HOST_VALUE=$(echo "$UI_CONTENT" | tr '<' '\n' | grep "etServerHost" | grep -oP 'text="\K[^"]*')
    log_info "Current host value: '$HOST_VALUE'"

    if [ "$HOST_VALUE" != "10.0.2.2" ]; then
        log_info "Setting host to 10.0.2.2..."
        # Tap on host field to focus it
        adb shell input tap 325 180
        sleep 0.5
        # Type the new value directly (will append to empty field or replace)
        adb shell input text "10.0.2.2"
        sleep 0.5
        # Tap on the "Host" label to unfocus the text field (bounds [21,84][89,127])
        adb shell input tap 55 100
        sleep 0.5
    fi

    # Tap Start button at default position (bounds [21,244][252,370], center ~136,307)
    log_info "Tapping Start button..."
    adb shell input tap 136 307
fi

# Wait for connections to establish
log_info "Waiting for connections to establish..."
sleep 5

# Check for established connections
CONNECTIONS=$(netstat -an 2>/dev/null | grep "9999.*ESTABLISHED" | wc -l | tr -d ' ')
CONNECTIONS=${CONNECTIONS:-0}
log_info "Established connections to server: $CONNECTIONS"

if [ "$CONNECTIONS" -lt 1 ]; then
    log_warn "No connections established yet, waiting longer..."
    sleep 5
    CONNECTIONS=$(netstat -an 2>/dev/null | grep "9999.*ESTABLISHED" | wc -l | tr -d ' ')
    CONNECTIONS=${CONNECTIONS:-0}
    log_info "Established connections to server: $CONNECTIONS"
fi

# Test the proxy with retries
log_info "Testing proxy with HTTP request to example.com..."
HTTP_CODE="000"
for attempt in 1 2 3; do
    HTTP_CODE=$(curl -x http://localhost:8888 -s -o /dev/null -w "%{http_code}" --max-time 15 http://example.com 2>/dev/null || echo "000")
    HTTP_CODE=$(echo "$HTTP_CODE" | tr -d '\n\r ')
    if [ "$HTTP_CODE" = "200" ]; then
        break
    fi
    log_warn "Attempt $attempt failed (HTTP $HTTP_CODE), retrying..."
    sleep 2
done

if [ "$HTTP_CODE" = "200" ]; then
    log_info "Proxy test PASSED - HTTP $HTTP_CODE"
else
    log_error "Proxy test FAILED - HTTP $HTTP_CODE"
    log_info "Server log tail:"
    tail -20 /tmp/proxy-server.log 2>/dev/null || true
    exit 1
fi

# Get app log from UI
log_info "Fetching app connection log..."
adb shell uiautomator dump /sdcard/ui_final.xml 2>/dev/null
APP_LOG=$(adb shell cat /sdcard/ui_final.xml 2>/dev/null | tr '>' '\n' | grep "tvLog" | sed 's/.*text="//' | sed 's/".*//' | sed 's/&#10;/\n/g')

echo ""
echo "=========================================="
echo "APP CONNECTION LOG:"
echo "=========================================="
echo "$APP_LOG"
echo "=========================================="

# Final status check
adb shell uiautomator dump /sdcard/ui_status.xml 2>/dev/null
if adb shell cat /sdcard/ui_status.xml 2>/dev/null | grep -q 'text="Status: Running"'; then
    STATUS="Running"
else
    STATUS="Stopped"
fi

echo ""
echo "=========================================="
echo "TEST SUMMARY"
echo "=========================================="
echo "Server:      Running (ports 8888, 9999)"
echo "App Status:  $STATUS"
echo "Connections: $CONNECTIONS"
echo "Proxy Test:  HTTP $HTTP_CODE"
echo "=========================================="

if [ "$HTTP_CODE" = "200" ] && [ "$STATUS" = "Running" ]; then
    log_info "All tests PASSED"
    exit 0
else
    log_error "Some tests FAILED"
    exit 1
fi
