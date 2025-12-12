#!/bin/bash
# Script to reset git files whose change is only a single new line

# Get list of modified files
modified_files=$(git status --porcelain | grep '^ M' | awk '{print $2}')

if [ -z "$modified_files" ]; then
    echo "No modified files found."
    exit 0
fi

reset_count=0
skipped_count=0

echo "Checking modified files for single-line changes..."
echo ""

for file in $modified_files; do
    # Get diff stats (format: additions deletions filename)
    diff_stats=$(git diff --numstat "$file")
    
    if [ -z "$diff_stats" ]; then
        continue
    fi
    
    # Parse additions and deletions
    additions=$(echo "$diff_stats" | awk '{print $1}')
    deletions=$(echo "$diff_stats" | awk '{print $2}')
    
    # Check if only lines were added at the bottom (any number of additions, no deletions)
    # or if only 1 line was removed
    if [ "$additions" -gt "0" ] && [ "$deletions" = "0" ]; then
        echo "Resetting $file ($additions line(s) added at bottom)"
        git restore "$file"
        reset_count=$((reset_count + 1))
    elif [ "$additions" = "0" ] && [ "$deletions" = "1" ]; then
        echo "Resetting $file (1 line removed)"
        git restore "$file"
        reset_count=$((reset_count + 1))
    else
        skipped_count=$((skipped_count + 1))
        echo "Skipping $file ($additions additions, $deletions deletions)"
    fi
done

echo ""
echo "Summary:"
echo "  Files reset: $reset_count"
echo "  Files skipped: $skipped_count"

