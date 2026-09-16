#!/usr/bin/env python3
"""
Simple webhook proxy to forward Gitea webhooks to GitHub repository_dispatch API.

Deploy this to a service like Heroku, Railway, Fly.io, or your own server.
Set GITHUB_TOKEN and WEBHOOK_SECRET. WEBHOOK_SECRET must match the Gitea webhook Secret.
"""

import logging
import os

import requests
from flask import Flask, jsonify, request

from gitea_signature import verify_gitea_signature

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

GITHUB_TOKEN = os.environ.get('GITHUB_TOKEN')
GITHUB_REPO = os.environ.get('GITHUB_REPO', 'cyberes3/geovault')
GITHUB_WORKFLOW_BRANCH = os.environ.get('GITHUB_WORKFLOW_BRANCH', '__mirror')
GITHUB_WORKFLOW_NAME = os.environ.get('GITHUB_WORKFLOW_NAME', 'mirror.yml')
WEBHOOK_SECRET = os.environ.get('WEBHOOK_SECRET')
GITHUB_API_URL = f'https://api.github.com/repos/{GITHUB_REPO}/actions/workflows/{GITHUB_WORKFLOW_NAME}/dispatches'

if not GITHUB_TOKEN:
    raise ValueError("GITHUB_TOKEN environment variable must be set")

if not WEBHOOK_SECRET:
    raise ValueError("WEBHOOK_SECRET environment variable must be set")


@app.route('/webhook', methods=['POST'])
def webhook():
    """Receive Gitea webhook and forward to GitHub."""
    try:
        signature = request.headers.get('X-Gitea-Signature', '')
        if not signature:
            logger.warning("Rejected webhook with missing signature")
            return jsonify({'status': 'error', 'message': 'Missing signature'}), 401

        body = request.get_data()
        if not verify_gitea_signature(WEBHOOK_SECRET, body, signature):
            logger.warning("Rejected webhook with invalid signature")
            return jsonify({'status': 'error', 'message': 'Invalid signature'}), 401

        data = request.get_json(silent=True)
        if not data:
            logger.warning("Received empty webhook payload")
            return jsonify({'status': 'error', 'message': 'Empty payload'}), 400

        ref = data.get('ref', '')
        logger.info(f"Received webhook for ref: {ref}")

        if ref != 'refs/heads/master':
            logger.info(f"Ignoring non-master branch: {ref}")
            return jsonify({'status': 'ignored', 'reason': 'not master branch'}), 200

        headers = {
            'Authorization': f'Bearer {GITHUB_TOKEN}',
            'Accept': 'application/vnd.github.v3+json',
            'Content-Type': 'application/json'
        }

        payload = {
            'ref': GITHUB_WORKFLOW_BRANCH,
            'inputs': {
                'ref': ref
            }
        }

        logger.info(f"Forwarding to GitHub: {GITHUB_API_URL}")
        response = requests.post(GITHUB_API_URL, json=payload, headers=headers, timeout=10)

        if response.status_code == 204:
            logger.info("Successfully triggered GitHub Action")
            return jsonify({'status': 'success', 'message': 'GitHub Action triggered'}), 200

        logger.error(f"GitHub API error: {response.status_code} - {response.text}")
        return jsonify({
            'status': 'error',
            'github_status': response.status_code,
            'message': response.text
        }), response.status_code

    except Exception as e:
        logger.error(f"Error processing webhook: {str(e)}", exc_info=True)
        return jsonify({'status': 'error', 'message': str(e)}), 500


@app.route('/health', methods=['GET'])
def health():
    """Health check endpoint."""
    return jsonify({
        'status': 'healthy',
        'github_repo': GITHUB_REPO,
        'workflow_branch': GITHUB_WORKFLOW_BRANCH,
        'token_configured': bool(GITHUB_TOKEN)
    }), 200


if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port)
