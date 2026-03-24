/**
 * Right-click / context menu: copy lat, lng to clipboard and show the same toast as the main map.
 *
 * @param {import('maplibre-gl').Map} map
 * @param {{ toast?: object, ClipboardDocumentIcon?: object }} [deps]
 * @returns {() => void} Teardown (removes the listener)
 */
export function setupCopyMapCoordinatesOnContextMenu(map, deps = {}) {
  const toastApi =
    deps.toast ??
    (typeof window !== 'undefined' ? window.gv_core?.GeoVault?.toast : null)
  const ClipboardIcon =
    deps.ClipboardDocumentIcon ??
    (typeof window !== 'undefined' ? window.HeroiconsOutline?.ClipboardDocumentIcon : null)

  function onContextMenu(e) {
    e.preventDefault()
    const { lng, lat } = e.lngLat
    const zoom = Math.round(map.getZoom())
    const coordinateString = `${lat.toFixed(6)}, ${lng.toFixed(6)}`
    const caltopoUrl = `https://caltopo.com/map.html#ll=${lat.toFixed(5)},${lng.toFixed(5)}&z=${zoom}`

    navigator.clipboard.writeText(coordinateString).then(() => {
      if (!toastApi) return
      const html = `Coordinates copied! <a href="${caltopoUrl}" target="_blank" rel="noopener noreferrer">Open in CalTopo</a>`
      const opts = {
        html,
        duration: 5000,
        plain: true
      }
      if (ClipboardIcon) {
        opts.icon = ClipboardIcon
      }
      toastApi.show('Coordinates copied!', 'info', opts)
    }).catch((err) => {
      console.error('Failed to copy coordinates:', err)
      if (toastApi?.error) {
        toastApi.error('Failed to copy coordinates')
      }
    })
  }

  map.on('contextmenu', onContextMenu)
  return () => map.off('contextmenu', onContextMenu)
}
