#!/bin/bash

# Check if version argument is provided
if [ -z "$1" ]; then
    echo "Please provide a version number (e.g., 0.0.2)"
    exit 1
fi

NEW_VERSION=$1

# Update package.json version
npm version $NEW_VERSION --no-git-tag-version

# Note: iOS podspec reads version from package.json automatically
# Note: Android library version is managed via package.json for Capacitor plugins
# No separate version files need to be updated

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

    # Create Git tag for the version
    TAG_NAME="v$NEW_VERSION"
    echo "Creating tag $TAG_NAME..."

    if git rev-parse "$TAG_NAME" >/dev/null 2>&1; then
        echo "Warning: Tag $TAG_NAME already exists. Skipping tag creation."
    else
        git tag -a "$TAG_NAME" -m "chore: release version $NEW_VERSION"
        echo "Tag $TAG_NAME created successfully!"
        echo ""
        echo "To push the commit and tag to remote, run:"
        echo "  git push && git push origin $TAG_NAME"
    fi
else
    echo "No changes to commit. Version might already be at $NEW_VERSION"
    exit 1
fi