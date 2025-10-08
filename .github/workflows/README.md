# GitHub Actions Workflows

## Publish to npm

This workflow automatically publishes the package to npm when a new version tag is pushed.

### Setup

Before this workflow can publish to npm, you need to set up an npm access token:

#### 1. Create an npm Access Token

1. Log in to [npmjs.com](https://www.npmjs.com/)
2. Click on your profile picture → **Access Tokens**
3. Click **Generate New Token** → **Classic Token**
4. Select **Automation** (for CI/CD)
5. Copy the generated token

#### 2. Add Token to GitHub Secrets

1. Go to your GitHub repository
2. Navigate to **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Name: `NPM_TOKEN`
5. Value: Paste your npm token
6. Click **Add secret**

### Usage

Once the secret is configured, the workflow will automatically run when you push a tag:

```bash
# Bump version and create tag
npm run bump 1.2.3

# Push commits and tags
git push && git push --tags
```

The workflow will:
1. ✅ Checkout the code
2. 📦 Install dependencies
3. 🔨 Build the package
4. 🚀 Publish to npm with provenance

### Workflow Triggers

- **On**: Push of tags starting with `v` (e.g., `v1.0.0`, `v0.0.2`)
- **Runs on**: Ubuntu latest
- **Node version**: 24

### Features

- **Provenance**: Publishes with npm provenance for enhanced security and transparency
- **Ignore Scripts**: Uses `--ignore-scripts` flag for security (build runs explicitly before publish)
- **CI Install**: Uses `npm ci` for faster, reliable installs in CI environments
- **Explicit Build**: Runs `npm run build` before publishing to ensure package is properly built

