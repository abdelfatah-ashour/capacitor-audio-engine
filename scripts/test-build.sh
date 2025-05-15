#!/bin/bash

# Exit on error
set -e

echo "🧹 Cleaning up..."
rm -rf node_modules
rm -f package-lock.json

echo "📦 Installing dependencies..."
npm install

echo "🔨 Building..."
npm run build

echo "✅ Build completed successfully!"