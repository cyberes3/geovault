const TOUCH_POINTER_QUERY = '(pointer: coarse)';

export function isTouchPointer() {
  return typeof window !== 'undefined' && window.matchMedia(TOUCH_POINTER_QUERY).matches;
}

export function getInitialCooperativeGestures(mode) {
  // List map: on touch devices, enable cooperative gestures so one-finger swipes can scroll
  // the page while two-finger gestures operate the map. Edit map stays fully interactive.
  return mode === 'list' && isTouchPointer();
}

export function applyListTouchInteractionPolicy(map) {
  if (!map?.cooperativeGestures) return;
  map.cooperativeGestures.enable();
}

export function applyListDesktopInteractionPolicy(map) {
  if (!map?.cooperativeGestures) return;
  map.cooperativeGestures.disable();
}

export function applyEditInteractionPolicy(map) {
  if (!map?.cooperativeGestures) return;
  map.cooperativeGestures.disable();
}
