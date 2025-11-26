#!/usr/bin/env python3
"""
Simple webhook proxy to forward Gitea webhooks to GitHub repository_dispatch API.

Deploy this to a service like Heroku, Railway, Fly.io, or your own server.
Set the GITHUB_TOKEN environment variable to your GitHub PAT.
"""

from flask import Flask, request, jsonify
import requests
import os
import logging

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Configuration from environment variables
GITHUB_TOKEN = os.environ.get('GITHUB_TOKEN')
GITHUB_REPO = os.environ.get('GITHUB_REPO', 'Cyberes/geovault')
GITHUB_API_URL = f'https://api.github.com/repos/{GITHUB_REPO}/dispatches'

if not GITHUB_TOKEN:
    raise ValueError("GITHUB_TOKEN environment variable must be set")


@app.route('/webhook', methods=['POST'])
def webhook():
    """Receive Gitea webhook and forward to GitHub."""
    try:
        data = request.json
        
        if not data:
            logger.warning("Received empty webhook payload")
            return jsonify({'status': 'error', 'message': 'Empty payload'}), 400
        
        # Extract branch reference
        ref = data.get('ref', '')
        logger.info(f"Received webhook for ref: {ref}")
        
        # Only process master branch pushes
        if ref != 'refs/heads/master':
            logger.info(f"Ignoring non-master branch: {ref}")
            return jsonify({'status': 'ignored', 'reason': 'not master branch'}), 200
        
        # Forward to GitHub repository_dispatch API
        headers = {
            'Authorization': f'Bearer {GITHUB_TOKEN}',
            'Accept': 'application/vnd.github.v3+json',
            'Content-Type': 'application/json'
        }
        
        payload = {
            'event_type': 'gitea-push',
            'client_payload': {
                'ref': ref,
                'repository': {
                    'full_name': data.get('repository', {}).get('full_name', GITHUB_REPO.lower())
                }
            }
        }
        
        logger.info(f"Forwarding to GitHub: {GITHUB_API_URL}")
        response = requests.post(GITHUB_API_URL, json=payload, headers=headers, timeout=10)
        
        if response.status_code == 204:
            logger.info("Successfully triggered GitHub Action")
            return jsonify({'status': 'success', 'message': 'GitHub Action triggered'}), 200
        else:
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
        'token_configured': bool(GITHUB_TOKEN)
    }), 200


if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port)

