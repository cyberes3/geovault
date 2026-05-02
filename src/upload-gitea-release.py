#!/usr/bin/env python3
"""
Build a signed Android app release and upload it as a Gitea draft release.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, urlparse
from urllib.request import Request, urlopen

BUILD_SCRIPT_NAME = "build-android.sh"
ENV_FILE_NAME = ".env"
GENERATE_ICONS_SCRIPT_NAME = "generate-icons.sh"
TARGET_COMMITISH = "master"
APK_METADATA_PATTERN = re.compile(r"(\d{4}-\d{2}-\d{2})([- ])([0-9A-Za-z]+)")


@dataclass(frozen=True)
class ReleaseConfig:
    gitea_base_url: str
    gitea_repo: str
    gitea_token: str
    release_title: str
    asset_title: str
    user_agent: str
    tag_prefix: str

    @property
    def asset_file_prefix(self) -> str:
        return self.asset_title.replace(" ", "-")

    @property
    def repo_api_url(self) -> str:
        return f"{self.gitea_base_url}/api/v1/repos/{self.gitea_repo}"

    @property
    def repo_web_url(self) -> str:
        return f"{self.gitea_base_url}/{self.gitea_repo}"


@dataclass(frozen=True)
class AppConfig:
    tag_prefix: str
    release_title: str
    asset_title: str
    gitea_repo: str


SURVEY_RELEASE = AppConfig(
    tag_prefix="survey",
    release_title="Survey",
    asset_title="GeoVault Survey Data Viewer",
    gitea_repo="cyberes/survey-data-viewer-android",
)

APP_CONFIGS = {
    "android-tracker": AppConfig(
        tag_prefix="tracker",
        release_title="Tracker",
        asset_title="GeoVault Live Tracker",
        gitea_repo="cyberes/geovault-app-release",
    ),
    "android-uploader": AppConfig(
        tag_prefix="uploader",
        release_title="Uploader",
        asset_title="GeoVault Uploader",
        gitea_repo="cyberes/geovault-app-release",
    ),
    "android-places": AppConfig(
        tag_prefix="places",
        release_title="Places",
        asset_title="GeoVault Places",
        gitea_repo="cyberes/geovault-app-release",
    ),
    # Symlink name in geovault vs real repo folder name.
    "android-survey-data-viewer": SURVEY_RELEASE,
    "survey-data-viewer-android": SURVEY_RELEASE,
}


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
            value = value.strip()
            if (
                len(value) >= 2
                and value[0] == value[-1]
                and value[0] in ("'", '"')
            ):
                value = value[1:-1]
            os.environ[key] = value


def require_env(key: str, env_path: Path) -> str:
    value = os.environ.get(key, "").strip()
    if not value:
        die(f"{key} is required. Set it in {env_path}.")
    return value


def normalize_gitea_host(host: str) -> str:
    if host.startswith("ssh."):
        return host.removeprefix("ssh.")
    return host


def parse_gitea_remote_url(remote_url: str) -> tuple[str, str]:
    remote_url = remote_url.strip()
    parsed = urlparse(remote_url)
    if parsed.scheme in {"http", "https", "ssh"} and parsed.hostname:
        host = normalize_gitea_host(parsed.hostname)
        repo_path = parsed.path.lstrip("/")
    elif ":" in remote_url:
        host_part, repo_path = remote_url.split(":", 1)
        host = normalize_gitea_host(host_part.split("@")[-1])
    else:
        die(f"Could not parse Gitea remote URL: {remote_url}")

    repo_path = repo_path.removesuffix(".git").strip("/")
    if not host or repo_path.count("/") < 1:
        die(f"Could not parse Gitea owner/repo from remote URL: {remote_url}")
    return f"https://{host}", repo_path


def load_gitea_remote_config(repo_dir: Path) -> tuple[str, str]:
    remote_url = run(["git", "config", "--get", "remote.origin.url"], cwd=repo_dir)
    return parse_gitea_remote_url(remote_url)


def load_config(env_path: Path, app_config: AppConfig, repo_dir: Path) -> ReleaseConfig:
    gitea_base_url, _ = load_gitea_remote_config(repo_dir)
    return ReleaseConfig(
        gitea_base_url=gitea_base_url.rstrip("/"),
        gitea_repo=app_config.gitea_repo,
        gitea_token=require_env("GITEA_RELEASE_TOKEN", env_path),
        release_title=app_config.release_title,
        asset_title=app_config.asset_title,
        user_agent=os.environ.get("GITEA_UPLOAD_USER_AGENT", "GeoVault-ReleaseUploader/1.0").strip()
        or "GeoVault-ReleaseUploader/1.0",
        tag_prefix=app_config.tag_prefix,
    )


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


def request_json(
    method: str,
    url: str,
    config: ReleaseConfig,
    payload: dict | None = None,
    data: bytes | None = None,
    content_type: str | None = None,
) -> dict:
    headers = {
        "Authorization": f"token {config.gitea_token}",
        "User-Agent": config.user_agent,
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


def create_draft_release(
    config: ReleaseConfig,
    tag: str,
    title: str,
) -> int:
    response = request_json(
        "POST",
        f"{config.repo_api_url}/releases",
        config,
        payload={
            "tag_name": tag,
            "target_commitish": TARGET_COMMITISH,
            "name": title,
            "body": "",
            "draft": True,
            "prerelease": False,
        },
    )
    release_id = response.get("id")
    if not release_id:
        die(f"Failed to create draft release: {json.dumps(response, indent=2)}")
    return int(release_id)


def upload_release_asset(
    config: ReleaseConfig,
    release_id: int,
    apk_path: Path,
) -> str:
    response = request_json(
        "POST",
        f"{config.repo_api_url}/releases/{release_id}/assets"
        f"?name={quote(apk_path.name)}",
        config,
        data=apk_path.read_bytes(),
        content_type="application/octet-stream",
    )
    asset_url = response.get("browser_download_url", "")
    if not asset_url:
        die(
            "Release was created, but asset upload may have failed:\n"
            + json.dumps(response, indent=2)
        )
    return asset_url


def find_release_apk(app_dir: Path, config: ReleaseConfig) -> Path:
    copied_patterns = [
        f"{config.asset_file_prefix}-*.apk",
        f"{config.asset_title} *.apk",
    ]
    copied_apks = sorted(
        (apk for pattern in copied_patterns for apk in app_dir.glob(pattern)),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if copied_apks:
        return copied_apks[0]

    die(
        f"No release APK found with release metadata in its filename. "
        f"Checked copied patterns ({copied_patterns}) under {app_dir}."
    )


def parse_release_apk_name(apk_path: Path, config: ReleaseConfig) -> tuple[str, str]:
    stem = apk_path.stem
    dashed_prefix = f"{config.asset_file_prefix}-"
    spaced_prefix = f"{config.asset_title} "

    if stem.startswith(dashed_prefix):
        suffix = stem.removeprefix(dashed_prefix)
    elif stem.startswith(spaced_prefix):
        suffix = stem.removeprefix(spaced_prefix)
    else:
        suffix = ""

    match = APK_METADATA_PATTERN.fullmatch(suffix)

    if not match:
        die(
            f"Could not read release date and commit fragment from APK filename: "
            f"{apk_path.name}"
        )
    return match.group(1), match.group(3)


def resolve_app_dir(script_dir: Path, app_folder: str) -> tuple[str, Path]:
    app_path = Path(app_folder)
    app_dir = app_path if app_path.is_absolute() else script_dir / app_path
    app_config_key = app_dir.name
    app_dir = app_dir.resolve()

    if app_config_key not in APP_CONFIGS:
        die(
            "Unsupported app folder '{}'. Supported: {}".format(
                app_config_key, ", ".join(sorted(APP_CONFIGS.keys()))
            )
        )
    if not app_dir.is_dir():
        die(f"App folder not found: {app_dir}")
    if not (app_dir / "gradlew").exists():
        die(f"No gradlew found in app folder: {app_dir}")

    return app_config_key, app_dir


def build_release_apk(app_dir: Path) -> None:
    build_script = app_dir / BUILD_SCRIPT_NAME
    if not build_script.exists():
        die(f"Missing build script: {build_script}")

    print("Building release APK...")
    run(["chmod", "+x", str(build_script)], cwd=app_dir)
    run([str(build_script), "release"], cwd=app_dir, stream=True)


def generate_icons(app_dir: Path) -> None:
    generate_icons_script = app_dir / GENERATE_ICONS_SCRIPT_NAME
    if not generate_icons_script.exists():
        die(f"Missing icon generation script: {generate_icons_script}")

    print("Generating app icons...")
    run(["chmod", "+x", str(generate_icons_script)], cwd=app_dir)
    run([str(generate_icons_script)], cwd=app_dir, stream=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and upload Android draft release to Gitea")
    parser.add_argument("app_folder", help="App folder path (e.g. android-tracker)")
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    env_path = script_dir / ENV_FILE_NAME
    load_env(env_path)

    app_config_key, app_dir = resolve_app_dir(script_dir, args.app_folder)
    config = load_config(env_path, APP_CONFIGS[app_config_key], script_dir)

    generate_icons(app_dir)
    build_release_apk(app_dir)

    apk_path = find_release_apk(app_dir, config)
    date_short, commit_fragment = parse_release_apk_name(apk_path, config)
    tag = f"{config.tag_prefix}-{date_short}-{commit_fragment}"
    title = f"{config.release_title} {date_short} {commit_fragment}"

    print(f"Creating draft release: {tag}")
    release_id = create_draft_release(config, tag, title)

    print(f"Uploading asset: {apk_path.name}")
    asset_url = upload_release_asset(config, release_id, apk_path)

    release_url = f"{config.repo_web_url}/releases/tag/{tag}"
    print("Done.")
    print(f"Tag:   {tag}")
    print(f"Title: {title}")
    print(f"Draft: {release_url}")
    print(f"Asset: {asset_url}")


if __name__ == "__main__":
    main()
