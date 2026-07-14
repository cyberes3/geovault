export interface SettingsSnapshot {
  recentDataWindow?: string | null;
}

export function didRecentDataWindowChange(previousSnapshot: SettingsSnapshot | null | undefined, currentSnapshot: SettingsSnapshot | null | undefined): boolean {
  const previous = String(previousSnapshot?.recentDataWindow ?? '');
  const current = String(currentSnapshot?.recentDataWindow ?? '');
  return current !== previous;
}

export function shouldReloadGeometryForSettingsChange(
  refreshMap: boolean,
  changedTrackId: string | number | null | undefined,
  selectedSidebarTrackId: string | number | null | undefined
): boolean {
  if (!refreshMap) return false;
  const changed = changedTrackId == null ? '' : String(changedTrackId).trim();
  if (!changed) return false;
  const selected = selectedSidebarTrackId == null ? '' : String(selectedSidebarTrackId).trim();
  if (!selected) return true;
  return changed === selected;
}
