# Gitea to GitHub Webhook Mirror Setup

This guide explains how to set up automatic mirroring from your Gitea repository to GitHub using webhooks.

## Overview

When you push commits to the `master` branch in your Gitea repository, a webhook will trigger a GitHub Action that mirrors the changes to your GitHub repository. The workflow file is located on the `__mirror` branch to avoid conflicts when the workflow tries to update the repository.

## Prerequisites

- A GitHub repository (e.g., `Cyberes/geovault`)
- A GitHub Personal Access Token (PAT) with `repo` scope
- Access to Gitea repository settings

## Step 1: Create GitHub Personal Access Token

1. Go to GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Click "Generate new token (classic)"
3. Give it a descriptive name (e.g., "Gitea Mirror Token")
4. Select the `repo` scope (this gives full control of private repositories)
5. Click "Generate token"
6. **Copy the token immediately** - you won't be able to see it again

## Step 2: Add PAT as GitHub Secret

1. Go to your GitHub repository (e.g., `https://github.com/Cyberes/geovault`)
2. Navigate to Settings → Secrets and variables → Actions
3. Click "New repository secret"
4. Name: `GITHUB_TOKEN`
5. Value: Paste your PAT from Step 1
6. Click "Add secret"

## Step 3: Set Up Webhook Proxy (Required)

Since Gitea webhooks don't support custom headers needed for GitHub's API, you'll need a simple webhook proxy that receives Gitea's webhook and forwards it to GitHub with the proper authentication.

### Option A: Use a Simple Webhook Proxy Service

You can use a service like:
- **Webhook.site** (temporary testing only)
- **Zapier** or **Make.com** (no-code automation)
- **A custom webhook receiver** (see Option B)

### Option B: Use the Provided Webhook Proxy Script

A ready-to-deploy webhook proxy script is included in `installation/webhook-proxy.py`.

**Quick Deploy to Railway:**
1. Go to [railway.app](https://railway.app)
2. Create a new project
3. Deploy from GitHub (or upload the files)
4. Add environment variables:
   - `GITHUB_TOKEN`: Your GitHub PAT
   - `GITHUB_REPO`: `Cyberes/geovault` (or your repo)
   - `GITHUB_WORKFLOW_BRANCH`: `__mirror` (the branch where the workflow file exists)
5. Railway will provide a URL like `https://your-app.railway.app`

**Quick Deploy to Fly.io:**
1. Install flyctl: `curl -L https://fly.io/install.sh | sh`
2. Run `fly launch` in the directory with `webhook-proxy.py`
3. Set secrets: `fly secrets set GITHUB_TOKEN=your_token GITHUB_REPO=Cyberes/geovault GITHUB_WORKFLOW_BRANCH=__mirror`
4. Deploy: `fly deploy`

**Deploy to Your Own Server:**
1. Install Python 3 and pip
2. Install dependencies: `pip install -r requirements.txt`
3. Set environment variables:
   - `export GITHUB_TOKEN=your_token`
   - `export GITHUB_REPO=Cyberes/geovault` (optional, defaults to Cyberes/geovault)
   - `export GITHUB_WORKFLOW_BRANCH=__mirror` (optional, defaults to __mirror)
4. Run: `python webhook-proxy.py`
5. Use a reverse proxy (nginx/caddy) or run behind a process manager (systemd/supervisor)

The proxy will be available at `http://your-server:5000/webhook` (or your configured domain).

## Step 4: Configure Gitea Webhook

1. Go to your Gitea repository: `https://git.evulid.cc/cyberes/geovault`
2. Navigate to Settings → Webhooks
3. Click "Add Webhook" → "Gitea"
4. Configure the webhook:
   - **Target URL**: `https://your-webhook-proxy-url.com/webhook` (replace with your proxy URL from Step 3)
   - **HTTP Method**: POST
   - **Content Type**: application/json
   - **Secret**: (optional, for webhook verification)
   - **Branch Filter**: `master` (only trigger on master branch)
   - **Events**: Check only "Push"
5. Click "Add Webhook"

## Step 5: Test the Webhook

1. Make a test commit to the `master` branch in your Gitea repository
2. Check the webhook delivery in Gitea (Settings → Webhooks → your webhook → Recent Deliveries)
3. Check the GitHub Actions tab in your GitHub repository to see if the workflow ran
4. Verify that the changes appear in your GitHub repository