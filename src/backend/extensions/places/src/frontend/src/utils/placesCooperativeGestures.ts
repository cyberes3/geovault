import type { MaplibreMap } from '../types/maplibre';

const TOUCH_POINTER_QUERY = '(pointer: coarse)';

export function isTouchPointer(): boolean {
  return window.matchMedia(TOUCH_POINTER_QUERY).matches;
}

export function getInitialCooperativeGestures(mode: string): boolean {
  // List map: on touch devices, enable cooperative gestures so one-finger swipes can scroll
  // the page while two-finger gestures operate the map. Edit map stays fully interactive.
  return mode === 'list' && isTouchPointer();
}

export function applyListTouchInteractionPolicy(map: MaplibreMap): void {
  if (!map.cooperativeGestures) return;
  map.cooperativeGestures.enable();
}

export function applyListDesktopInteractionPolicy(map: MaplibreMap): void {
  if (!map.cooperativeGestures) return;
  map.cooperativeGestures.disable();
}

export function applyEditInteractionPolicy(map: MaplibreMap): void {
  if (!map.cooperativeGestures) return;
  map.cooperativeGestures.disable();
}
