/**
 * Shared arrow icon for track direction on MapLibre maps.
 * Rasterizes SVG to image data for consistent sprite rendering.
 */

export const ARROW_PATH_D =
  'M29.9,28.6l-13-26c-0.3-0.7-1.4-0.7-1.8,0l-13,26c-0.2,0.4-0.1,0.8,0.2,1.1C2.5,30,3,30.1,3.4,29.9L16,25.1l12.6,4.9c0.1,0,0.2,0.1,0.4,0.1c0.3,0,0.5-0.1,0.7-0.3C30,29.4,30.1,28.9,29.9,28.6z';

export const ARROW_RASTER_SIZE = 96;

export function getArrowImageId(color, selected) {
  const base = (color || '#6C93DE').replace('#', '');
  return 'track-arrow-' + (selected ? 'selected-' : '') + base;
}

export function getTrackArrowDataURL(color, selected) {
  const fill = color || '#6C93DE';
  const circle =
    selected
      ? '<circle cx="16" cy="16" r="15" fill="white" stroke="#fff" stroke-width="1.4"/>' +
        '<circle cx="16" cy="16" r="15" fill="none" stroke="#000" stroke-width="1.0"/>'
      : '';
  const chevronBlackStroke = '#000';
  const chevronBlackStrokeWidth = '1.2';
  const chevronWhiteStroke = '#fff';
  const chevronWhiteStrokeWidth = selected ? '1.6' : '1.8';
  const pathTransform = ' transform="translate(16,2.6) scale(0.8) translate(-16,-2.6)"';
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="' +
    ARROW_RASTER_SIZE +
    '" height="' +
    ARROW_RASTER_SIZE +
    '" shape-rendering="geometricPrecision">' +
    circle +
    '<path' +
    pathTransform +
    ' fill="' +
    fill +
    '" stroke="' +
    chevronWhiteStroke +
    '" stroke-width="' +
    chevronWhiteStrokeWidth +
    '" stroke-linejoin="round" shape-rendering="geometricPrecision" d="' +
    ARROW_PATH_D +
    '"/>' +
    '<path' +
    pathTransform +
    ' fill="' +
    fill +
    '" stroke="' +
    chevronBlackStroke +
    '" stroke-width="' +
    chevronBlackStrokeWidth +
    '" stroke-linejoin="round" shape-rendering="geometricPrecision" d="' +
    ARROW_PATH_D +
    '"/>' +
    '</svg>';
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
}

/**
 * Ensure the arrow image for the given color/selected state exists on the map.
 * Idempotent: no-op if the image is already present.
 * @param {import('maplibre-gl').Map} mapInstance - MapLibre map instance
 * @param {string} color - Hex color (e.g. '#6C93DE')
 * @param {boolean} selected - Whether selected variant
 * @returns {Promise<void>}
 */
export function ensureArrowImage(mapInstance, color, selected = false) {
  if (!mapInstance?.getStyle?.() || !mapInstance.hasImage) return Promise.resolve();
  const id = getArrowImageId(color, selected);
  if (mapInstance.getStyle() && mapInstance.hasImage(id)) return Promise.resolve();
  return rasterizeArrowToImageData(color, selected).then((imageData) => {
    if (imageData && mapInstance && mapInstance.getStyle() && !mapInstance.hasImage(id)) {
      mapInstance.addImage(id, imageData, { pixelRatio: 1 });
    }
  });
}

export function rasterizeArrowToImageData(color, selected) {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = ARROW_RASTER_SIZE;
      canvas.height = ARROW_RASTER_SIZE;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        resolve(null);
        return;
      }
      ctx.imageSmoothingEnabled = true;
      if (ctx.imageSmoothingQuality) ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(img, 0, 0, ARROW_RASTER_SIZE, ARROW_RASTER_SIZE);
      const imageData = ctx.getImageData(0, 0, ARROW_RASTER_SIZE, ARROW_RASTER_SIZE);
      resolve({
        width: ARROW_RASTER_SIZE,
        height: ARROW_RASTER_SIZE,
        data: new Uint8Array(imageData.data)
      });
    };
    img.onerror = () => resolve(null);
    img.src = getTrackArrowDataURL(color, selected);
  });
}
