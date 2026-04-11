export function didRecentDataWindowChange(previousSnapshot, currentSnapshot) {
  const previous = String(previousSnapshot?.recentDataWindow || '');
  const current = String(currentSnapshot?.recentDataWindow || '');
  return current !== previous;
}

export function shouldReloadGeometryForSettingsChange(refreshMap, changedTrackId, selectedSidebarTrackId) {
  if (refreshMap !== true) return false;
  const changed = changedTrackId == null ? '' : String(changedTrackId).trim();
  if (!changed) return false;
  const selected = selectedSidebarTrackId == null ? '' : String(selectedSidebarTrackId).trim();
  if (!selected) return true;
  return changed === selected;
}
