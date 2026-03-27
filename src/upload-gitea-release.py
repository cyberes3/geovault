#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import uuid
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen

_SURVEY_RELEASE = (
    "survey",
    "Survey",
    "GeoVault Survey Data Viewer",
    "survey-data-viewer-android",
)

APP_CONFIG = {
    "android-tracker": ("tracker", "Tracker", "GeoVault Live Tracker", "geovault-app-release"),
    "android-uploader": ("uploader", "Uploader", "GeoVault Uploader", "geovault-app-release"),
    "android-places": ("places", "Places", "GeoVault Places", "geovault-app-release"),
    # Symlink name in geovault vs real repo folder name (resolve() follows the link)
    "android-survey-data-viewer": _SURVEY_RELEASE,
    "survey-data-viewer-android": _SURVEY_RELEASE,
}

GITEA_BASE_URL = "https://git.evulid.cc"
GITEA_OWNER = "cyberes"
TARGET_COMMITISH = "master"
USER_AGENT = os.environ.get("GITEA_UPLOAD_USER_AGENT", "GeoVault-ReleaseUploader/1.0").strip() or "GeoVault-ReleaseUploader/1.0"


def die(msg: str) -> None:
    print(msg, file=sys.stderr)
    raise SystemExit(1)


def load_env(env_path: Path) -> None:
    if not env_path.exists():
        return
    for raw in env_path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key and key not in os.environ:
            os.environ[key] = value.strip()


def run(cmd: list[str], cwd: Path | None = None, stream: bool = False) -> str:
    proc = subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        text=True,
        capture_output=not stream,
    )
    if proc.returncode != 0:
        output = ""
        if not stream:
            output = (proc.stderr or "").strip() or (proc.stdout or "").strip()
        if not output:
            output = f"exit code {proc.returncode}"
        die(f"Command failed ({' '.join(cmd)}):\n{output}")
    return (proc.stdout or "").strip()


def request_json(method: str, url: str, token: str, payload: dict | None = None, data: bytes | None = None, content_type: str | None = None) -> dict:
    headers = {
        "Authorization": f"token {token}",
        "User-Agent": USER_AGENT,
    }
    body: bytes | None = None
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif data is not None:
        body = data
        if content_type:
            headers["Content-Type"] = content_type

    req = Request(url=url, method=method, headers=headers, data=body)
    try:
        with urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        die(f"HTTP request failed for {url}: {e}")


def build_multipart_attachment(file_name: str, file_bytes: bytes) -> tuple[bytes, str]:
    boundary = f"----GeoVaultUpload{uuid.uuid4().hex}"
    header = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="attachment"; filename="{file_name}"\r\n'
        "Content-Type: application/vnd.android.package-archive\r\n\r\n"
    ).encode("utf-8")
    trailer = f"\r\n--{boundary}--\r\n".encode("utf-8")
    body = header + file_bytes + trailer
    content_type = f"multipart/form-data; boundary={boundary}"
    return body, content_type


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and upload Android draft release to Gitea")
    parser.add_argument("app_folder", help="App folder path (e.g. android-tracker)")
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    load_env(script_dir / ".env")

    token = os.environ.get("GITEA_RELEASE_TOKEN", "").strip()
    if not token:
        die(f"GITEA_RELEASE_TOKEN is required in {script_dir / '.env'}")

    app_arg = Path(args.app_folder)
    app_dir = app_arg if app_arg.is_absolute() else script_dir / app_arg
    # Basename before resolve() so a symlink (e.g. android-survey-data-viewer) keeps
    # the geovault folder name for APP_CONFIG, not the target directory name.
    app_config_key = app_dir.name
    app_dir = app_dir.resolve()

    if not app_dir.is_dir():
        die(f"App folder not found: {app_dir}")
    if not (app_dir / "gradlew").exists():
        die(f"No gradlew found in app folder: {app_dir}")
    if not (app_dir / "app").is_dir():
        die(f"Expected Android app module at: {app_dir / 'app'}")

    if app_config_key not in APP_CONFIG:
        die(
            "Unsupported app folder '{}'. Supported: {}".format(
                app_config_key, ", ".join(sorted(APP_CONFIG.keys()))
            )
        )

    app_slug, release_title_name, asset_title_name, release_repo = APP_CONFIG[app_config_key]

    full_hash = run(["git", "rev-parse", "HEAD"], cwd=app_dir)
    short_hash = full_hash[:10]
    date_short = run(["git", "log", "-1", "--format=%cd", "--date=short"], cwd=app_dir)

    tag = f"{app_slug}-{date_short}-{short_hash}"
    title = f"{release_title_name} {date_short} {short_hash}"
    asset_name = f"{asset_title_name} {date_short} {short_hash}.apk"

    print(f"Building release APK for {app_config_key}...")
    build_script = app_dir / "build-android.sh"
    if not build_script.exists():
        die(f"Missing build script: {build_script}")
    run(["chmod", "+x", str(build_script)], cwd=app_dir)
    run([str(build_script), "release"], cwd=app_dir, stream=True)

    apk_dir = app_dir / "app" / "build" / "outputs" / "apk" / "release"
    apks = sorted(apk_dir.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not apks:
        die(f"No release APK found in {apk_dir}")
    apk_path = apks[0]

    print(f"Creating draft release: {tag}")
    create_url = f"{GITEA_BASE_URL}/api/v1/repos/{GITEA_OWNER}/{release_repo}/releases"
    create_payload = {
        "tag_name": tag,
        "target_commitish": TARGET_COMMITISH,
        "name": title,
        "body": "",
        "draft": True,
        "prerelease": False,
    }
    create_resp = request_json("POST", create_url, token, payload=create_payload)
    release_id = create_resp.get("id")
    if not release_id:
        die(f"Failed to create draft release: {json.dumps(create_resp, indent=2)}")

    print(f"Uploading asset: {asset_name}")
    upload_url = (
        f"{GITEA_BASE_URL}/api/v1/repos/{GITEA_OWNER}/{release_repo}/releases/"
        f"{release_id}/assets?name={quote(asset_name)}"
    )
    multipart_body, multipart_type = build_multipart_attachment(asset_name, apk_path.read_bytes())
    upload_resp = request_json(
        "POST",
        upload_url,
        token,
        data=multipart_body,
        content_type=multipart_type,
    )

    asset_url = upload_resp.get("browser_download_url", "")
    if not asset_url:
        die(f"Release was created, but asset upload may have failed: {json.dumps(upload_resp, indent=2)}")

    release_url = f"{GITEA_BASE_URL}/{GITEA_OWNER}/{release_repo}/releases/tag/{tag}"
    print("Done.")
    print(f"Tag:   {tag}")
    print(f"Title: {title}")
    print(f"Draft: {release_url}")
    print(f"Asset: {asset_url}")


if __name__ == "__main__":
    main()
