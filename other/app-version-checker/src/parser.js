/** Mirrors ReleaseAssetParser.kt */

export const FULL_SHA_REGEX = /^[0-9a-f]{40}$/;
const TAG_COMMIT_REGEX = /([0-9a-fA-F]{7,40})$/;
export const APK_NAME_REGEX = /^(.+?)\s(\d{4}-\d{2}-\d{2}\s[0-9a-fA-F]{10})\.apk$/;

export function extractCommitRefFromTag(tagName) {
  const m = TAG_COMMIT_REGEX.exec(String(tagName).trim());
  return m ? m[1].toLowerCase() : null;
}

export function parseApkName(assetName) {
  const m = APK_NAME_REGEX.exec(String(assetName).trim());
  if (!m || m.length < 3) return null;
  const appName = m[1].trim();
  const versionLabel = m[2].trim();
  if (!appName || !versionLabel) return null;
  return { appName, versionLabel };
}
