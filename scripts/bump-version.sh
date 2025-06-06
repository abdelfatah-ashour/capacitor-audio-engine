#!/bin/bash

# Check if version argument is provided
if [ -z "$1" ]; then
    echo "Please provide a version number (e.g., 0.0.2)"
    exit 1
fi

NEW_VERSION=$1

# Update package.json version
npm version $NEW_VERSION --no-git-tag-version

# Update iOS version in podspec
sed -i '' "s/s.version = '.*'/s.version = '$NEW_VERSION'/" CapacitorNativeAudio.podspec

# Update Android version in build.gradle
sed -i '' "s/version = '.*'/version = '$NEW_VERSION'/" android/build.gradle

# Stage all modified files
echo "Staging modified files..."
git add .

# Verify if there are any changes to commit
if ! git diff --staged --quiet; then
    # Verify commit message format
    COMMIT_MSG="chore: bump version to $NEW_VERSION"
    if [[ ! $COMMIT_MSG =~ ^chore:\ .* ]]; then
        echo "Error: Commit message must start with 'chore: '"
        exit 1
    fi

    # Create commit
    git commit -m "$COMMIT_MSG"

    # Verify the last commit message
    LAST_COMMIT_MSG=$(git log -1 --pretty=%B)
    if [[ ! $LAST_COMMIT_MSG =~ ^chore:\ .* ]]; then
        echo "Error: Last commit message does not start with 'chore: '"
        echo "Last commit message: $LAST_COMMIT_MSG"
        exit 1
    fi

    echo "Version bumped to $NEW_VERSION and committed successfully with proper 'chore: ' prefix!"
else
    echo "No changes to commit. Version might already be at $NEW_VERSION"
    exit 1
fi