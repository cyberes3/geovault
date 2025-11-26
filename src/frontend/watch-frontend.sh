#!/bin/bash

# Frontend Watch and Build Script
# This script watches the frontend source files for changes and rebuilds automatically

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="${FRONTEND_DIR:-$SCRIPT_DIR}"
WATCH_DIRS=("src" "public" "index.html" "vite.config.js" "tailwind.config.js" "postcss.config.js")
BUILD_COMMAND="npm run build"
DEBOUNCE_INTERVAL=5  # Minimum seconds between builds

# Global variables for debouncing
LAST_BUILD_TIME=0
BUILD_IN_PROGRESS=false
QUEUED_REBUILD=false

# Function to print colored output
print_status() {
    echo -e "${BLUE}[$(date '+%Y-%m-%d %H:%M:%S')]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[$(date '+%Y-%m-%d %H:%M:%S')]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[$(date '+%Y-%m-%d %H:%M:%S')]${NC} $1"
}

print_error() {
    echo -e "${RED}[$(date '+%Y-%m-%d %H:%M:%S')]${NC} $1"
}

# Function to build the frontend
build_frontend() {
    print_status "Building frontend..."
    
    # Ensure we're in the right directory
    if ! cd "$FRONTEND_DIR"; then
        print_error "Failed to change to frontend directory: $FRONTEND_DIR"
        BUILD_IN_PROGRESS=false
        return 1
    fi
    
    # Check if npm is still available
    if ! command -v npm &> /dev/null; then
        print_error "npm command not found during build!"
        BUILD_IN_PROGRESS=false
        return 1
    fi
    
    # Run build with explicit error handling
    if npm run build 2>&1; then
        print_success "Frontend build completed successfully!"
        
        # Update last build time
        LAST_BUILD_TIME=$(date +%s)
        BUILD_IN_PROGRESS=false
        
        # Check if there's a queued rebuild and handle it
        if [ "$QUEUED_REBUILD" = true ]; then
            print_status "Handling queued rebuild after successful build..."
            QUEUED_REBUILD=false
            BUILD_IN_PROGRESS=true
            build_frontend
        fi
    else
        print_error "Frontend build failed! Exit code: $?"
        BUILD_IN_PROGRESS=false
        return 1
    fi
}

# Function to check if enough time has passed since last build
can_build() {
    local current_time
    current_time=$(date +%s)
    local time_diff=$((current_time - LAST_BUILD_TIME))
    
    # Don't build if already in progress or not enough time has passed
    if [ "$BUILD_IN_PROGRESS" = true ] || [ "$time_diff" -lt "$DEBOUNCE_INTERVAL" ]; then
        return 1
    else
        return 0
    fi
}

# Function to handle queued rebuild after timeout
handle_queued_rebuild() {
    if [ "$QUEUED_REBUILD" = true ]; then
        print_status "Processing queued rebuild after timeout..."
        QUEUED_REBUILD=false
        BUILD_IN_PROGRESS=true
        build_frontend
    fi
}

# Function to start queued rebuild timer
start_queued_rebuild_timer() {
    if [ "$QUEUED_REBUILD" = false ]; then
        QUEUED_REBUILD=true
        local current_time
        current_time=$(date +%s)
        local time_diff=$((current_time - LAST_BUILD_TIME))
        local remaining=$((DEBOUNCE_INTERVAL - time_diff))
        
        print_warning "Build queued - will rebuild in $remaining seconds"
        
        # Start background process to handle queued rebuild
        (
            sleep "$remaining"
            handle_queued_rebuild
        ) &
    fi
}

# Function to debounced build (only builds if enough time has passed)
debounced_build() {
    if can_build; then
        BUILD_IN_PROGRESS=true
        QUEUED_REBUILD=false  # Cancel any queued rebuild
        build_frontend
    else
        local current_time
        current_time=$(date +%s)
        local time_diff=$((current_time - LAST_BUILD_TIME))
        
        if [ "$BUILD_IN_PROGRESS" = true ]; then
            print_warning "Build skipped - build already in progress"
            # Queue a rebuild for after current build completes
            start_queued_rebuild_timer
        else
            # Queue a rebuild for after the debounce timeout
            start_queued_rebuild_timer
        fi
    fi
}

# Function to check if inotify-tools is installed
check_dependencies() {
    if ! command -v inotifywait &> /dev/null; then
        print_error "inotifywait is not installed. Please install inotify-tools:"
        print_error "  Ubuntu/Debian: sudo apt-get install inotify-tools"
        print_error "  CentOS/RHEL: sudo yum install inotify-tools"
        print_error "  Fedora: sudo dnf install inotify-tools"
        exit 1
    fi
}

# Function to check if npm is available
check_npm() {
    if ! command -v npm &> /dev/null; then
        print_error "npm is not installed or not in PATH"
        exit 1
    fi
}

# Function to check if node_modules exists
check_node_modules() {
    if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
        print_warning "node_modules not found. Installing dependencies..."
        cd "$FRONTEND_DIR"
        npm install
        print_success "Dependencies installed successfully!"
    fi
}

# Function to start file watcher
start_file_watcher() {
    # Create watch paths
    WATCH_PATHS=""
    for dir in "${WATCH_DIRS[@]}"; do
        if [ -e "$FRONTEND_DIR/$dir" ]; then
            WATCH_PATHS="$WATCH_PATHS $FRONTEND_DIR/$dir"
        else
            print_warning "Watch path $dir does not exist, skipping..."
        fi
    done
    
    if [ -z "$WATCH_PATHS" ]; then
        print_error "No valid watch paths found!"
        return 1
    fi
    
    print_status "Starting file watcher for: $WATCH_PATHS"
    
    # Start inotifywait directly without named pipes
    inotifywait -m -r -e modify,create,delete,move \
        --format '%w%f %e' \
        $WATCH_PATHS | while read -r file event; do
        
        # Skip certain files/directories
        if [[ "$file" =~ (node_modules|\.git|dist|\.cache|\.DS_Store) ]]; then
            continue
        fi
        
        print_status "File changed: $file ($event)"
        
        # Use debounced build to prevent excessive rebuilds
        debounced_build
        
        echo "---"
    done
}

# Main function
main() {
    print_status "Starting frontend watch script..."
    
    # Check dependencies
    check_dependencies
    check_npm
    check_node_modules
    
    # Change to frontend directory
    cd "$FRONTEND_DIR"
    
    # Initial build
    print_status "Performing initial build..."
    build_frontend
    
    print_status "Debouncing enabled: maximum 1 build every $DEBOUNCE_INTERVAL seconds"
    print_status "Queued rebuilds: changes during debounce period will trigger rebuild after timeout"
    print_status "Press Ctrl+C to stop watching"
    echo
    
    # Watch for changes with automatic restart on failure
    while true; do
        print_status "Starting file watcher..."
        
        # Start file watcher
        if start_file_watcher; then
            print_error "File watcher exited unexpectedly"
        else
            print_error "File watcher failed to start"
        fi
        
        print_status "Restarting file watcher in 5 seconds..."
        sleep 5
    done
}

# Handle Ctrl+C gracefully
cleanup() {
    print_status "Stopping watch script..."
    # Kill any background processes
    jobs -p | xargs -r kill 2>/dev/null || true
    exit 0
}

trap cleanup INT TERM

# Run main function
main "$@"
