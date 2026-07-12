/**
 * Bounds-checked array access that's genuinely typed as possibly `undefined`.
 *
 * Plain `arr[index]` is typed as `T` (not `T | undefined`) under this project's tsconfig
 * (`noUncheckedIndexedAccess` is off), and TypeScript's control-flow analysis narrows away even an
 * explicit `const x: T | undefined = arr[index]` annotation immediately after the assignment. This
 * crosses a real function-call boundary so out-of-bounds access is still caught by callers'
 * `if (!x) return` guards instead of silently producing `undefined` typed as `T`.
 */
export function arrayAt<T>(arr: readonly T[], index: number): T | undefined {
  return arr[index];
}
