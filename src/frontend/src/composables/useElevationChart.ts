import { ref, computed, watch, nextTick, onBeforeUnmount, type Ref } from 'vue';
import { useStore } from 'vuex';
import { Chart, registerables } from 'chart.js';
import type { Chart as ChartInstance, ChartEvent, Plugin, Scale } from 'chart.js';
import { getFeatureElevations } from '@/api/services/featuresApi';
import { getPublicFeatureElevations } from '@/api/services/sharingApi';
import { toastApiError } from '@/utils/apiError';
import type { RootState } from '@/assets/js/store';
import type { GeoJsonFeature } from '@/types/geospatial';
import {
  getDistanceUnitLabel,
  getElevationUnitLabel,
} from '@/utils/units';
import {
  extractCoordinates,
  extractTimestamps,
  processElevationData,
  mapDistanceToCoordinate,
  smoothElevationData,
  calculateSpeeds,
  calculateSpeedStats,
} from '@/utils/map/elevationProfileUtils';
import type { SpeedSegment } from '@/utils/map/elevationProfileUtils';

Chart.register(...registerables);

/** [lon, lat] pair mapped from a point on the elevation chart back onto the feature's line. */
export type ElevationCoordinate = [number, number];

export type ElevationChartEmits = {
  (e: 'hover-point', coordinate: ElevationCoordinate): void;
  (e: 'hover-clear'): void;
  (e: 'click-point', coordinate: ElevationCoordinate): void;
};

export interface ElevationStatItem {
  label: string;
  value: string;
}

export interface ElevationStats {
  totalDistance: string;
  totalElevationChange: string;
  elevationRange: string;
  grossAscent: string;
  grossDescent: string;
  minElevation: string;
  maxElevation: string;
  averageElevation: string;
  averageSpeed?: string | null;
  averageMovingSpeed?: string | null;
  totalMovingTime?: string;
  totalTrackTime?: string;
}

interface UserSettingsGetterShape {
  map?: {
    elevation_profile_source?: string;
  };
}

interface RootGetters {
  'userSettings/userSettings': UserSettingsGetterShape | null;
}

interface PointLike {
  x: number;
  y: number;
}

/** Narrow, structurally-typed view of the feature properties this composable actually reads. */
function getStringOrNumberProp(properties: Record<string, unknown>, key: string): string | number | undefined {
  const value = properties[key];
  return typeof value === 'string' || typeof value === 'number' ? value : undefined;
}

function getFeatureId(feature: GeoJsonFeature): string | number | undefined {
  const properties = feature.properties as Record<string, unknown>;
  return getStringOrNumberProp(properties, 'database_id') ?? getStringOrNumberProp(properties, 'geojson_hash');
}

/**
 * Chart.js instance lifecycle, elevation data fetching/processing, stats calculation, and
 * hover/click coordinate mapping for `ElevationProfileDialog.vue`. The component owns only
 * its dialog chrome (mobile dropdown, keyboard/click-outside handlers, route-close watcher).
 */
export function useElevationChart(
  feature: Ref<GeoJsonFeature | null | undefined>,
  shareId: Ref<string | null | undefined>,
  isPublicShare: Ref<boolean | undefined>,
  emit: ElevationChartEmits,
) {
  const store = useStore<RootState>();

  const chartCanvas = ref<HTMLCanvasElement | null>(null);
  let chart: ChartInstance | null = null;
  let coordinateMapping: ElevationCoordinate[] | null = null;
  let distances: number[] | null = null;

  const hasElevationData = ref(false);
  const isUpdatingChart = ref(false);
  const stats = ref<ElevationStats | null>(null);

  const elevationProfileSource = computed<string>(() => {
    const getters = store.getters as RootGetters;
    return getters['userSettings/userSettings']?.map?.elevation_profile_source ?? 'gps';
  });

  const allStatItems = computed<ElevationStatItem[]>(() => {
    const currentStats = stats.value;
    if (!currentStats) return [];
    const items: ElevationStatItem[] = [
      { label: 'Dist', value: currentStats.totalDistance },
      { label: 'Elev. Change', value: currentStats.totalElevationChange },
      { label: 'Asc', value: currentStats.grossAscent },
      { label: 'Des', value: currentStats.grossDescent },
      { label: 'Min Elv', value: currentStats.minElevation },
      { label: 'Max Elv', value: currentStats.maxElevation },
      { label: 'Avg. Elv', value: currentStats.averageElevation },
    ];
    if (currentStats.totalTrackTime) {
      items.push({ label: 'Total Time', value: currentStats.totalTrackTime });
    }
    if (currentStats.totalMovingTime) {
      items.push({ label: 'Moving Time', value: currentStats.totalMovingTime });
    }
    if (currentStats.averageMovingSpeed) {
      items.push({ label: 'Avg. Moving Speed', value: currentStats.averageMovingSpeed });
    }
    return items;
  });

  const firstRowStats = computed<ElevationStatItem[]>(() => allStatItems.value.slice(0, 2));
  const remainingStats = computed<ElevationStatItem[]>(() => allStatItems.value.slice(2));
  const hasRemainingStats = computed<boolean>(() => remainingStats.value.length > 0);

  function formatElevationNumber(value: number): string {
    return Math.round(value).toLocaleString('en-US');
  }

  /**
   * Get feature stroke color and adjust if too light or dark. Returns an RGB color string
   * suitable for the chart's border/fill.
   */
  function getFeatureColor(currentFeature: GeoJsonFeature | null | undefined): string {
    const defaultColor = 'rgb(20, 184, 166)';
    if (!currentFeature) {
      return defaultColor;
    }

    const properties = currentFeature.properties as Record<string, unknown>;
    const strokeColor = typeof properties.stroke === 'string' ? properties.stroke : '#ff0000';

    let r: number;
    let g: number;
    let b: number;
    if (strokeColor.startsWith('#')) {
      const hex = strokeColor.slice(1);
      r = parseInt(hex.substring(0, 2), 16);
      g = parseInt(hex.substring(2, 4), 16);
      b = parseInt(hex.substring(4, 6), 16);
    } else if (strokeColor.startsWith('rgb')) {
      const matches = strokeColor.match(/\d+/g);
      if (matches && matches.length >= 3) {
        r = parseInt(matches[0]);
        g = parseInt(matches[1]);
        b = parseInt(matches[2]);
      } else {
        return defaultColor;
      }
    } else {
      return defaultColor;
    }

    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    if (luminance > 0.8) {
      const factor = 0.6;
      r = Math.max(0, Math.min(255, Math.round(r * factor)));
      g = Math.max(0, Math.min(255, Math.round(g * factor)));
      b = Math.max(0, Math.min(255, Math.round(b * factor)));
    } else if (luminance < 0.2) {
      const factor = 1.8;
      r = Math.max(0, Math.min(255, Math.round(r * factor)));
      g = Math.max(0, Math.min(255, Math.round(g * factor)));
      b = Math.max(0, Math.min(255, Math.round(b * factor)));
    }

    return `rgb(${r}, ${g}, ${b})`;
  }

  /**
   * Calculate statistics from elevation data.
   * @param distancesUserUnit Cumulative distances in user units
   * @param elevationsUserUnit Elevations in user units
   * @param distancesMeters Cumulative distances in meters (for speed calculations)
   * @param timestamps ISO timestamp strings (optional, for speed calculations)
   */
  function calculateStats(
    distancesUserUnit: number[],
    elevationsUserUnit: number[],
    distancesMeters: number[] | null = null,
    timestamps: string[] | null = null,
  ): ElevationStats | null {
    if (distancesUserUnit.length === 0 || elevationsUserUnit.length === 0) {
      return null;
    }

    const distUnit = getDistanceUnitLabel();
    const elevUnit = getElevationUnitLabel();

    const totalDistanceVal = distancesUserUnit[distancesUserUnit.length - 1];
    const totalDistance = `${totalDistanceVal.toFixed(2)} ${distUnit}`;

    const totalElevationChange = elevationsUserUnit[elevationsUserUnit.length - 1] - elevationsUserUnit[0];
    const totalElevationChangeFormatted = totalElevationChange >= 0
      ? `+${formatElevationNumber(totalElevationChange)} ${elevUnit}`
      : `${formatElevationNumber(totalElevationChange)} ${elevUnit}`;

    const minElevation = Math.min(...elevationsUserUnit);
    const maxElevation = Math.max(...elevationsUserUnit);
    const elevationRange = `${formatElevationNumber(maxElevation - minElevation)} ${elevUnit}`;

    const averageElevation = elevationsUserUnit.reduce((sum, elev) => sum + elev, 0) / elevationsUserUnit.length;

    // Use smoothed elevation data to filter out GPS noise when computing gross ascent/descent.
    const smoothedElevations = smoothElevationData(elevationsUserUnit);
    let grossAscent = 0;
    let grossDescent = 0;

    // Threshold for noise filtering (approx 0.1 ft or 0.03 m)
    const noiseThreshold = 0.1 * (elevUnit === 'ft' ? 1 : 0.3048);

    for (let i = 1; i < smoothedElevations.length; i++) {
      const change = smoothedElevations[i] - smoothedElevations[i - 1];
      if (Math.abs(change) >= noiseThreshold) {
        if (change > 0) {
          grossAscent += change;
        } else {
          grossDescent += Math.abs(change);
        }
      }
    }

    const result: ElevationStats = {
      totalDistance,
      totalElevationChange: totalElevationChangeFormatted,
      elevationRange,
      grossAscent: `+${formatElevationNumber(grossAscent)} ${elevUnit}`,
      grossDescent: `-${formatElevationNumber(grossDescent)} ${elevUnit}`,
      minElevation: `${formatElevationNumber(minElevation)} ${elevUnit}`,
      maxElevation: `${formatElevationNumber(maxElevation)} ${elevUnit}`,
      averageElevation: `${formatElevationNumber(averageElevation)} ${elevUnit}`,
    };

    // Speed stats are only available for GPX tracks/routes with time data.
    if (timestamps && timestamps.length >= 2 && distancesMeters && distancesMeters.length >= 2) {
      const speeds: SpeedSegment[] = calculateSpeeds(distancesMeters, timestamps);
      const speedStats = calculateSpeedStats(speeds, distancesMeters, timestamps);
      if (speedStats) {
        result.averageSpeed = speedStats.averageSpeed;
        result.averageMovingSpeed = speedStats.averageMovingSpeed;
        result.totalMovingTime = speedStats.totalMovingTime;
        result.totalTrackTime = speedStats.totalTrackTime;
      }
    }

    return result;
  }

  /**
   * Fetch elevations for a feature from the API.
   * @param featureId The feature database ID or hash
   * @param source Either 'external' for the external elevation API or 'internal' for GPS elevations
   */
  async function fetchElevationsFromAPI(featureId: string | number, source: 'external' | 'internal'): Promise<number[][] | null> {
    try {
      if (isPublicShare.value && shareId.value) {
        // Public shares can only use internal elevations (GPS data stored on the feature);
        // the external elevation API requires authentication.
        if (source === 'external') {
          return null;
        }
        const data = await getPublicFeatureElevations(shareId.value) as { coordinates?: number[][] } | null;
        return data?.coordinates ?? null;
      }

      const data = await getFeatureElevations(featureId, source) as { coordinates?: number[][] } | null;
      return data?.coordinates ?? null;
    } catch (error) {
      toastApiError(error, `Failed to fetch ${source} elevation data`);
      return null;
    }
  }

  function destroyChartInstance(): void {
    if (chart) {
      try {
        chart.destroy();
      } catch (e) {
        console.warn('Error destroying chart:', e);
      }
      chart = null;
    }

    // Also check if Chart.js has a chart on the canvas (can happen after HMR/fast remounts).
    if (chartCanvas.value) {
      const existingChart = Chart.getChart(chartCanvas.value);
      if (existingChart) {
        try {
          existingChart.destroy();
        } catch (e) {
          console.warn('Error destroying existing chart from canvas:', e);
        }
      }
    }
  }

  function buildMarkerPlugin(minMarkerDatasetIndex: number, maxMarkerDatasetIndex: number): Plugin<'line'> {
    return {
      id: 'markerPlugin',
      afterDatasetsDraw: (chartInstance) => {
        const ctx = chartInstance.ctx;

        const minPointMeta = chartInstance.getDatasetMeta(minMarkerDatasetIndex);
        const maxPointMeta = chartInstance.getDatasetMeta(maxMarkerDatasetIndex);

        const minPoint = (minPointMeta.data as unknown as PointLike[])[0] as PointLike | undefined;
        if (minPoint && typeof minPoint.x === 'number' && typeof minPoint.y === 'number') {
          ctx.save();
          ctx.beginPath();
          ctx.arc(minPoint.x, minPoint.y, 6, 0, 2 * Math.PI);
          ctx.fillStyle = '#ef4444';
          ctx.fill();
          ctx.strokeStyle = '#000000';
          ctx.lineWidth = 2;
          ctx.stroke();
          ctx.restore();
        }

        const maxPoint = (maxPointMeta.data as unknown as PointLike[])[0] as PointLike | undefined;
        if (maxPoint && typeof maxPoint.x === 'number' && typeof maxPoint.y === 'number') {
          ctx.save();
          ctx.beginPath();
          ctx.arc(maxPoint.x, maxPoint.y, 6, 0, 2 * Math.PI);
          ctx.fillStyle = '#10b981';
          ctx.fill();
          ctx.strokeStyle = '#000000';
          ctx.lineWidth = 2;
          ctx.stroke();
          ctx.restore();
        }
      },
    };
  }

  function buildRenderCompletePlugin(renderStartTime: number): Plugin<'line'> {
    let spinnerHidden = false;
    return {
      id: 'renderCompletePlugin',
      afterDraw: (chartInstance) => {
        if (spinnerHidden) return;
        spinnerHidden = true;
        const elapsed = Date.now() - renderStartTime;
        const minDisplayTime = 300;
        const remainingTime = Math.max(0, minDisplayTime - elapsed);

        // Wait for the browser to paint the chart (multiple frames for reliability).
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            requestAnimationFrame(() => {
              setTimeout(() => {
                const canvas = chartInstance.canvas;
                if (canvas.width > 0 && canvas.height > 0) {
                  isUpdatingChart.value = false;
                } else {
                  setTimeout(() => {
                    isUpdatingChart.value = false;
                  }, 100);
                }
              }, remainingTime);
            });
          });
        });
      },
    };
  }

  function buildHoverPlugin(): Plugin<'line'> {
    let lastHoverCoordinate: ElevationCoordinate | null = null;

    function clearHover(): void {
      if (lastHoverCoordinate) {
        emit('hover-clear');
        lastHoverCoordinate = null;
      }
    }

    return {
      id: 'hoverPlugin',
      afterEvent: (chartInstance, args) => {
        const chartEvent: ChartEvent = args.event;
        const nativeEvent = chartEvent.native;
        if (!(nativeEvent instanceof MouseEvent)) return;
        if (nativeEvent.type !== 'mousemove' && nativeEvent.type !== 'click' && nativeEvent.type !== 'mouseout') return;

        const chartArea = chartInstance.chartArea;
        const rect = chartInstance.canvas.getBoundingClientRect();
        const x = nativeEvent.clientX - rect.left;
        const y = nativeEvent.clientY - rect.top;

        if (x < chartArea.left || x > chartArea.right || y < chartArea.top || y > chartArea.bottom) {
          clearHover();
          return;
        }

        const xScale = chartInstance.scales.x as Scale | undefined;
        if (!xScale) {
          clearHover();
          return;
        }

        const distanceMiles = xScale.getValueForPixel(x);
        if (distanceMiles === undefined || Number.isNaN(distanceMiles)) {
          clearHover();
          return;
        }

        if (!distances || !coordinateMapping) {
          clearHover();
          return;
        }

        const coordinate = mapDistanceToCoordinate(distanceMiles, distances, coordinateMapping);
        if (coordinate) {
          const coordKey = `${coordinate[0].toFixed(6)},${coordinate[1].toFixed(6)}`;
          const lastKey = lastHoverCoordinate ? `${lastHoverCoordinate[0].toFixed(6)},${lastHoverCoordinate[1].toFixed(6)}` : null;

          if (coordKey !== lastKey) {
            emit('hover-point', coordinate);
            lastHoverCoordinate = coordinate;
          }

          if (nativeEvent.type === 'click') {
            emit('click-point', coordinate);
          }
        } else {
          clearHover();
        }
      },
    };
  }

  function createChart(elevations: number[], chartDistances: number[], currentFeature: GeoJsonFeature | null | undefined): void {
    const canvas = chartCanvas.value;
    if (!canvas) {
      isUpdatingChart.value = false;
      return;
    }

    const ctx = canvas.getContext('2d');
    if (!ctx) {
      isUpdatingChart.value = false;
      return;
    }

    const existingChart = Chart.getChart(canvas);
    if (existingChart) {
      try {
        existingChart.destroy();
      } catch (e) {
        console.warn('Error destroying existing chart from canvas:', e);
      }
    }

    const featureColor = getFeatureColor(currentFeature);
    const rgbMatch = featureColor.match(/\d+/g);
    const bgColor = rgbMatch && rgbMatch.length >= 3
      ? `rgba(${rgbMatch[0]}, ${rgbMatch[1]}, ${rgbMatch[2]}, 0.2)`
      : 'rgba(20, 184, 166, 0.2)';

    let minIndex = 0;
    let maxIndex = 0;
    let minElevation = elevations[0];
    let maxElevation = elevations[0];

    for (let i = 1; i < elevations.length; i++) {
      if (elevations[i] < minElevation) {
        minElevation = elevations[i];
        minIndex = i;
      }
      if (elevations[i] > maxElevation) {
        maxElevation = elevations[i];
        maxIndex = i;
      }
    }

    const minMarkerChartData = elevations
      .map((elev, idx) => (idx === minIndex ? { x: chartDistances[idx], y: elev } : null))
      .filter((d): d is { x: number; y: number } => d !== null);
    const maxMarkerChartData = elevations
      .map((elev, idx) => (idx === maxIndex ? { x: chartDistances[idx], y: elev } : null))
      .filter((d): d is { x: number; y: number } => d !== null);

    const renderStartTime = Date.now();
    const markerPlugin = buildMarkerPlugin(1, 2);
    const hoverPlugin = buildHoverPlugin();
    const renderCompletePlugin = buildRenderCompletePlugin(renderStartTime);

    const chartData = elevations.map((elev, idx) => ({ x: chartDistances[idx], y: elev }));
    const shouldFill = chartData.length < 10000;

    try {
      chart = new Chart(ctx, {
        type: 'line',
        plugins: [markerPlugin, hoverPlugin, renderCompletePlugin],
        data: {
          datasets: [
            {
              label: 'Elevation (ft)',
              data: chartData,
              borderColor: featureColor,
              backgroundColor: bgColor,
              fill: shouldFill ? 'origin' : false,
              tension: 0,
              pointRadius: 0,
              pointHoverRadius: 4,
            },
            {
              label: 'Min Elevation',
              data: minMarkerChartData,
              borderColor: 'transparent',
              backgroundColor: 'transparent',
              pointRadius: 0,
              showLine: false,
            },
            {
              label: 'Max Elevation',
              data: maxMarkerChartData,
              borderColor: 'transparent',
              backgroundColor: 'transparent',
              pointRadius: 0,
              showLine: false,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          resizeDelay: 0,
          devicePixelRatio: Math.min(window.devicePixelRatio || 1, 2),
          parsing: false,
          layout: {
            padding: { top: 15, right: 15, bottom: 15, left: 15 },
          },
          elements: {
            line: { borderJoinStyle: 'round' },
          },
          plugins: {
            legend: { display: false },
            tooltip: {
              mode: 'index',
              intersect: false,
              position: 'nearest',
              animation: false,
              filter: (tooltipItem) => tooltipItem.datasetIndex === 0,
              callbacks: {
                title: (items) => `Distance: ${(items[0].parsed.x ?? 0).toFixed(2)} mi`,
                label: (context) => {
                  const elevUnit = getElevationUnitLabel();
                  const formattedElevation = Math.round(context.parsed.y ?? 0).toLocaleString('en-US');
                  return `Elevation: ${formattedElevation} ${elevUnit}`;
                },
              },
            },
          },
          animation: { duration: 0 },
          scales: {
            x: {
              type: 'linear',
              min: chartDistances[0],
              max: chartDistances[chartDistances.length - 1],
              title: { display: false },
              ticks: { display: false },
              grid: { display: true },
            },
            y: {
              title: { display: false },
              ticks: { display: false },
              grid: { display: true },
            },
          },
          interaction: {
            mode: 'index',
            intersect: false,
          },
        },
      });
    } catch (e) {
      console.error('Error creating chart:', e);
      hasElevationData.value = false;
      isUpdatingChart.value = false;
    }
  }

  /** Update the chart with the current feature data (fetch -> process -> render). */
  async function updateChart(): Promise<void> {
    if (isUpdatingChart.value) {
      return;
    }

    isUpdatingChart.value = true;
    destroyChartInstance();

    const currentFeature = feature.value;
    if (!currentFeature?.geometry) {
      hasElevationData.value = false;
      stats.value = null;
      isUpdatingChart.value = false;
      return;
    }

    const geometry = currentFeature.geometry;

    // MapLibre simplifies LineString/MultiLineString geometry for rendering performance,
    // which loses most coordinates. Always fetch the full coordinates from the API for
    // accurate elevation profiles and track statistics when we have a feature ID.
    const featureId = getFeatureId(currentFeature);
    let coordinates: number[][];

    if (elevationProfileSource.value === 'api') {
      // === EXTERNAL ELEVATION SOURCE ===
      if (!featureId) {
        console.warn('Cannot fetch external elevations: no feature ID available');
        hasElevationData.value = false;
        stats.value = null;
        isUpdatingChart.value = false;
        return;
      }

      const apiCoordinates = await fetchElevationsFromAPI(featureId, 'external');
      if (apiCoordinates && apiCoordinates.length > 0) {
        coordinates = apiCoordinates;
      } else {
        console.warn('Failed to fetch elevations from external API');
        hasElevationData.value = false;
        stats.value = null;
        isUpdatingChart.value = false;
        return;
      }
    } else {
      // === GPS ELEVATION SOURCE ===
      if (featureId && (geometry.type === 'LineString' || geometry.type === 'MultiLineString')) {
        const apiCoordinates = await fetchElevationsFromAPI(featureId, 'internal');
        if (apiCoordinates && apiCoordinates.length > 0) {
          coordinates = apiCoordinates;
        } else {
          console.warn('GPS elevations unavailable from API');
          hasElevationData.value = false;
          stats.value = null;
          isUpdatingChart.value = false;
          return;
        }
      } else {
        coordinates = extractCoordinates(geometry);

        if (coordinates.length === 0) {
          hasElevationData.value = false;
          stats.value = null;
          isUpdatingChart.value = false;
          return;
        }

        const hasElevationInCoords = coordinates[0].length >= 3;
        if (!hasElevationInCoords) {
          console.warn('No elevation data in geometry coordinates');
          hasElevationData.value = false;
          stats.value = null;
          isUpdatingChart.value = false;
          return;
        }
      }
    }

    const timestamps = extractTimestamps(currentFeature);
    const processed = processElevationData(coordinates, timestamps);
    const { distances: distancesUserUnit, distancesMeters, elevations, coordinateMapping: mapping, timestamps: validTimestamps } = processed;

    if (distancesUserUnit.length === 0 || elevations.length === 0) {
      hasElevationData.value = false;
      stats.value = null;
      coordinateMapping = null;
      distances = null;
      isUpdatingChart.value = false;
      return;
    }

    coordinateMapping = mapping;
    distances = distancesUserUnit;
    stats.value = calculateStats(distancesUserUnit, elevations, distancesMeters, validTimestamps);
    hasElevationData.value = true;

    await nextTick();
    if (!chartCanvas.value) {
      isUpdatingChart.value = false;
      return;
    }
    createChart(elevations, distancesUserUnit, currentFeature);
  }

  watch(feature, () => {
    void nextTick(() => {
      void updateChart();
    });
  }, { immediate: true });

  onBeforeUnmount(() => {
    destroyChartInstance();
  });

  return {
    chartCanvas,
    hasElevationData,
    isUpdatingChart,
    stats,
    allStatItems,
    firstRowStats,
    remainingStats,
    hasRemainingStats,
    formatElevationNumber,
    updateChart,
  };
}
