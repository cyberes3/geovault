# Gitea to GitHub Webhook Mirror Setup

A simple webserver used for mirroring from `git.evulid.cc` to `github.com`. Translates the Gitea webhook format to
something that GitHub understands.

## Setup

### Create GitHub Personal Access Token

1. Go to GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Click "Generate new token (classic)"
3. Give it a descriptive name (e.g., "Gitea Mirror Token")
4. Select the `repo` scope (this gives full control of private repositories)
5. Click "Generate token"
6. **Copy the token immediately** - you won't be able to see it again

### Add PAT as GitHub Secret

1. Go to your GitHub repository (e.g., `https://github.com/Cyberes/geovault`)
2. Navigate to Settings → Secrets and variables → Actions
3. Click "New repository secret"
4. Name: `GITHUB_TOKEN`
5. Value: Paste your PAT from Step 1
6. Click "Add secret"

### Set Up the Webhook Proxy

1. Install Python 3 and pip
2. Install dependencies: `pip install -r requirements.txt`
3. Set environment variables:
    - `export GITHUB_TOKEN=your_token`
    - `export GITHUB_REPO=Cyberes/geovault` (optional, defaults to Cyberes/geovault)
    - `export GITHUB_WORKFLOW_BRANCH=__mirror` (optional, defaults to __mirror)
4. Run: `python webhook-proxy.py`
5. Use a reverse proxy (nginx/caddy) or run behind a process manager (systemd/supervisor)

### Configure Gitea Webhook

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