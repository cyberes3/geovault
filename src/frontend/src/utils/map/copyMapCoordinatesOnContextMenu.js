/** Single slot so repeated right-clicks do not stack; same ToastContainer + TransitionGroup as other toasts. */
export const MAP_COPY_COORDINATES_TOAST_REPLACE_KEY = 'map-copy-coordinates'

/**
 * Right-click / context menu: copy lat, lng to clipboard and show an info toast (GeoVault ToastContainer styling).
 *
 * @param {import('maplibre-gl').Map} map
 * @param {{ toast?: object }} [deps]
 * @returns {() => void} Teardown (removes the listener)
 */
export function setupCopyMapCoordinatesOnContextMenu(map, deps = {}) {
  const toastApi =
    deps.toast ??
    (typeof window !== 'undefined' ? window.gv_core?.GeoVault?.toast : null)

  function onContextMenu(e) {
    e.preventDefault()
    const { lng, lat } = e.lngLat
    const zoom = Math.round(map.getZoom())
    const coordinateString = `${lat.toFixed(6)}, ${lng.toFixed(6)}`
    const caltopoUrl = `https://caltopo.com/map.html#ll=${lat.toFixed(5)},${lng.toFixed(5)}&z=${zoom}`

    navigator.clipboard.writeText(coordinateString).then(() => {
      if (!toastApi) return
      const html = `Coordinates copied! <a href="${caltopoUrl}" target="_blank" rel="noopener noreferrer">Open in CalTopo</a>`
      toastApi.show('Coordinates copied!', 'info', {
        html,
        duration: 5000,
        dismissible: false,
        replaceKey: MAP_COPY_COORDINATES_TOAST_REPLACE_KEY
      })
    }).catch((err) => {
      console.error('Failed to copy coordinates:', err)
      if (toastApi?.error) {
        toastApi.error('Failed to copy coordinates', {
          replaceKey: MAP_COPY_COORDINATES_TOAST_REPLACE_KEY
        })
      }
    })
  }

  map.on('contextmenu', onContextMenu)
  return () => map.off('contextmenu', onContextMenu)
}
