import { computed, reactive, ref, watch, type Ref } from 'vue';
import type { GeoJsonFeature } from '@/types/geospatial';
import { updateFeatureMetadata, deleteFeature, getFeature } from '@/api/services/featuresApi';
import { ApiError } from '@/utils/apiError';
import { sortTagsByPriority } from '@/utils/tagUtils.js';
import { restoreElevationInGeometry } from '@/utils/elevationUtils.js';
import { validateCoordinates } from '@/utils/geo/coordinates';
import { ICON_PROPERTY_NAMES, VALID_ICON_EXTENSIONS, isSystemIcon as isSystemIconUrl, isUserIcon as isUserIconUrl } from '@/utils/map/iconUtils';

/** Checks the usual GeoJSON icon property names and returns the first one that looks like an icon reference. */
function findIconUrlInProperties(properties: Record<string, unknown>): string | null {
    for (const propName of ICON_PROPERTY_NAMES) {
        const value = properties[propName];
        if (typeof value !== 'string') continue;
        const iconUrl = value.trim();
        if (!iconUrl) continue;
        if (isSystemIconUrl(iconUrl) || isUserIconUrl(iconUrl)) {
            return iconUrl;
        }
        const lowerUrl = iconUrl.toLowerCase();
        if (VALID_ICON_EXTENSIONS.some((ext) => lowerUrl.endsWith(ext))) {
            return iconUrl;
        }
    }
    return null;
}

/**
 * NOTE: this intentionally re-serializes through `Date` rather than doing a real UTC->local
 * conversion (pre-existing behavior). `createdDateForInput` below applies it a second time to
 * match the original template's double-formatting, so changing this would shift displayed times.
 */
function formatDateForInput(dateString: string | null | undefined): string {
    if (!dateString) return '';
    const date = new Date(dateString);
    if (isNaN(date.getTime())) return '';
    return date.toISOString().slice(0, 16);
}

function extractHexFromColor(colorString: string | null | undefined): string | null {
    if (!colorString) return null;
    if (colorString.startsWith('#')) return colorString;
    const rgbaMatch = colorString.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    if (!rgbaMatch) return null;
    const [r, g, b] = [rgbaMatch[1], rgbaMatch[2], rgbaMatch[3]].map((value) => parseInt(value, 10));
    return `#${[r, g, b].map((value) => value.toString(16).padStart(2, '0')).join('')}`;
}

interface FeatureEditFormData {
    name: string;
    description: string;
    tags: string[];
    created: string;
    markerColor: string;
    strokeColor: string;
    strokeWidth: number;
    fillColor: string;
}

interface RestoredFeatureGeometry {
    type?: string;
    coordinates?: unknown;
    geometries?: unknown;
}

/** Shape of the `GET /api/feature/:id/` response, as consumed here. */
interface FeatureDetailResponse {
    feature?: {
        geojson: {
            type: 'Feature';
            geometry: GeoJsonFeature['geometry'];
            properties?: Record<string, unknown>;
            geojson_hash?: string;
        };
        geojson_hash?: string;
    };
}

/** Matches (a subset of) the parent's `defineEmits` return - only the events this composable raises. */
interface FeatureEditFormEmit {
    (event: 'saved', feature?: GeoJsonFeature): void;
    (event: 'deleted', feature: GeoJsonFeature): void;
    (event: 'visibility-change', payload: { featureId: string | number; hidden: boolean }): void;
}

export interface UseFeatureEditFormOptions {
    feature: Ref<GeoJsonFeature | null>;
    initialHidden: Ref<boolean>;
    emit: FeatureEditFormEmit;
}

export function useFeatureEditForm(options: UseFeatureEditFormOptions) {
    const { feature, initialHidden, emit } = options;

    const formData = reactive<FeatureEditFormData>({
        name: '',
        description: '',
        tags: [],
        created: '',
        markerColor: '#ff0000',
        strokeColor: '#ff0000',
        strokeWidth: 2,
        fillColor: '#ff0000',
    });

    const rawJsonInput = ref('');
    const hasPngIcon = ref(false);
    const isSaving = ref(false);
    const errorMessage = ref('');
    const iconUploadError = ref('');
    const currentIconUrl = ref<string | null>(null);
    const iconRemoved = ref(false);
    const hideOnMainMap = ref(false);

    const featureId = computed<string | number | null>(() => {
        const properties = feature.value?.properties;
        return (properties?.database_id as string | number | undefined) ?? null;
    });

    const geometryType = computed<string | null>(() => feature.value?.geometry.type ?? null);

    const isPoint = computed(() => geometryType.value === 'Point' || geometryType.value === 'MultiPoint');
    const isLine = computed(() => geometryType.value === 'LineString' || geometryType.value === 'MultiLineString');
    const isPolygon = computed(() => geometryType.value === 'Polygon' || geometryType.value === 'MultiPolygon');

    const isCustomIcon = computed(() => {
        if (!currentIconUrl.value || !hasPngIcon.value) return false;
        return isUserIconUrl(currentIconUrl.value) || !isSystemIconUrl(currentIconUrl.value);
    });

    const systemTags = computed<string[]>(() => {
        const properties = feature.value?.properties ?? {};
        const rawTags: unknown = properties.system_tags;
        const tags = Array.isArray(rawTags)
            ? rawTags.filter((tag): tag is string => typeof tag === 'string' && tag.trim() !== '')
            : [];
        return sortTagsByPriority(tags);
    });

    const createdDateForInput = computed(() => formatDateForInput(formData.created));

    function updateRawJson() {
        const currentFeature = feature.value;
        if (!currentFeature) return;

        // Don't overwrite rawJsonInput if the user already edited it via the coordinates dialog.
        if (rawJsonInput.value.trim()) {
            try {
                const parsed: unknown = JSON.parse(rawJsonInput.value);
                if (Array.isArray(parsed)) {
                    return;
                }
            } catch {
                // Invalid JSON currently in the box; fall through and regenerate it below.
            }
        }

        const featureWithElevation = restoreElevationInGeometry({
            type: 'Feature',
            geometry: currentFeature.geometry,
            properties: currentFeature.properties,
        }) as { geometry?: RestoredFeatureGeometry };

        const geometry = featureWithElevation.geometry;
        if (!geometry) return;

        if (geometry.type === 'GeometryCollection') {
            rawJsonInput.value = JSON.stringify(geometry.geometries ?? [], null, 2);
        } else {
            rawJsonInput.value = JSON.stringify(geometry.coordinates ?? [], null, 2);
        }
    }

    function initializeForm() {
        const currentFeature = feature.value;
        if (!currentFeature) return;

        const properties = currentFeature.properties;

        formData.name = (properties.name as string | undefined) ?? '';
        formData.description = (properties.description as string | undefined) ?? '';
        formData.tags = Array.isArray(properties.tags) ? (properties.tags as string[]) : [];
        formData.created = formatDateForInput(properties.created as string | undefined);
        formData.markerColor = (properties['marker-color'] as string | undefined) || '#ff0000';
        formData.strokeColor = (properties.stroke as string | undefined) || '#ff0000';
        formData.strokeWidth = (properties['stroke-width'] as number | undefined) ?? 2;

        if (isPolygon.value) {
            const fill = properties.fill as string | undefined;
            formData.fillColor = fill ? (extractHexFromColor(fill) ?? fill) : formData.strokeColor;
        }

        const iconUrl = findIconUrlInProperties(properties);
        hasPngIcon.value = iconUrl !== null;
        currentIconUrl.value = iconUrl;

        updateRawJson();

        iconUploadError.value = '';
        iconRemoved.value = false;

        hideOnMainMap.value = !!initialHidden.value;
    }

    watch(feature, initializeForm, { immediate: true });

    function onStrokeColorChange(value: string) {
        formData.strokeColor = value;
        if (isPolygon.value) {
            formData.fillColor = value;
        }
    }

    function handleIconSelected(iconUrl: string) {
        iconUploadError.value = '';
        if (isSystemIconUrl(iconUrl) || isUserIconUrl(iconUrl)) {
            currentIconUrl.value = iconUrl;
            hasPngIcon.value = true;
            iconRemoved.value = false;
        }
    }

    function handleIconSelectedFromSelector(event: { iconUrl: string }) {
        handleIconSelected(event.iconUrl);
    }

    function handleRemoveIcon() {
        iconUploadError.value = '';
        currentIconUrl.value = null;
        hasPngIcon.value = false;
        iconRemoved.value = true;
    }

    /** Returns null (and sets errorMessage) when the raw coordinates JSON fails validation. */
    function buildMetadataUpdates(): Record<string, unknown> | null {
        const metadataUpdates: Record<string, unknown> = {
            name: formData.name,
            description: formData.description || '',
            tags: formData.tags,
        };

        if (formData.created) {
            const date = new Date(formData.created);
            if (!isNaN(date.getTime())) {
                metadataUpdates.created = date.toISOString();
            }
        }

        if (isPoint.value) {
            if (currentIconUrl.value && !iconRemoved.value) {
                if (isSystemIconUrl(currentIconUrl.value) || isUserIconUrl(currentIconUrl.value)) {
                    metadataUpdates.icon = currentIconUrl.value;
                    if (isSystemIconUrl(currentIconUrl.value)) {
                        metadataUpdates['marker-color'] = formData.markerColor;
                    }
                }
            } else if (iconRemoved.value) {
                metadataUpdates.icon = '';
                metadataUpdates['marker-color'] = formData.markerColor;
            } else if (!hasPngIcon.value) {
                metadataUpdates['marker-color'] = formData.markerColor;
            }
        }

        if (isLine.value || isPolygon.value) {
            metadataUpdates.stroke = formData.strokeColor;
        }

        if (rawJsonInput.value.trim()) {
            let coordinatesData: unknown;
            try {
                coordinatesData = JSON.parse(rawJsonInput.value);
            } catch (e) {
                errorMessage.value = `Invalid JSON: ${e instanceof Error ? e.message : String(e)}`;
                return null;
            }

            if (!Array.isArray(coordinatesData)) {
                errorMessage.value = 'Coordinates must be a valid JSON array';
                return null;
            }
            if (coordinatesData.length === 0) {
                errorMessage.value = 'Coordinates cannot be empty';
                return null;
            }
            if (geometryType.value) {
                const validation = validateCoordinates(coordinatesData, geometryType.value);
                if (!validation.valid) {
                    errorMessage.value = validation.error || 'Invalid coordinates';
                    return null;
                }
            }

            metadataUpdates.coordinates = coordinatesData;
        }

        return metadataUpdates;
    }

    async function handleSubmit() {
        const currentFeature = feature.value;
        if (!currentFeature) return;

        errorMessage.value = '';
        iconUploadError.value = '';
        isSaving.value = true;

        const originalProperties = currentFeature.properties;
        const currentFeatureId = originalProperties.database_id as string | number | undefined;
        if (!currentFeatureId) {
            errorMessage.value = 'Feature ID not found. Cannot update feature.';
            isSaving.value = false;
            return;
        }

        const metadataUpdates = buildMetadataUpdates();
        if (!metadataUpdates) {
            isSaving.value = false;
            return;
        }

        try {
            await updateFeatureMetadata(currentFeatureId, metadataUpdates);

            try {
                const fetchData = (await getFeature(currentFeatureId)) as FeatureDetailResponse;
                if (fetchData.feature) {
                    const updatedFeature = fetchData.feature.geojson;
                    updatedFeature.properties ??= {};
                    updatedFeature.properties.database_id = currentFeatureId;
                    if (fetchData.feature.geojson_hash) {
                        updatedFeature.geojson_hash = fetchData.feature.geojson_hash;
                    }

                    currentFeature.geometry = updatedFeature.geometry;
                    currentFeature.properties = updatedFeature.properties;

                    if (isPoint.value) {
                        if (iconRemoved.value) {
                            currentIconUrl.value = null;
                            hasPngIcon.value = false;
                        } else if (currentIconUrl.value && (isSystemIconUrl(currentIconUrl.value) || isUserIconUrl(currentIconUrl.value))) {
                            hasPngIcon.value = true;
                            iconRemoved.value = false;
                        }
                    }

                    isSaving.value = false;
                    emit('saved', updatedFeature as GeoJsonFeature);
                    return;
                }
            } catch (fetchError) {
                console.error('Error fetching updated feature:', fetchError);
                const properties = currentFeature.properties;
                Object.assign(properties, metadataUpdates);
                properties.database_id = currentFeatureId;
                currentFeature.properties = properties;

                const updatedFeature: GeoJsonFeature = {
                    type: 'Feature',
                    geometry: currentFeature.geometry,
                    properties: currentFeature.properties,
                };

                isSaving.value = false;
                emit('saved', updatedFeature);
                return;
            }

            isSaving.value = false;
            emit('saved');
        } catch (error) {
            console.error('Error updating feature:', error);
            errorMessage.value = ApiError.from(error, 'Failed to update feature').message;
            isSaving.value = false;
        }
    }

    async function handleDelete() {
        const currentFeature = feature.value;
        if (!currentFeature) return;

        const originalProperties = currentFeature.properties;
        const currentFeatureId = originalProperties.database_id as string | number | undefined;
        if (!currentFeatureId) {
            errorMessage.value = 'Feature ID not found. Cannot delete feature.';
            return;
        }

        const featureName = (originalProperties.name as string | undefined) || 'this feature';
        const confirmed = window.confirm(`Are you sure you want to delete "${featureName}"? This feature will be permanently removed from your library.`);
        if (!confirmed) return;

        errorMessage.value = '';
        isSaving.value = true;

        try {
            await deleteFeature(currentFeatureId);
            emit('deleted', currentFeature);
        } catch (error) {
            console.error('Error deleting feature:', error);
            errorMessage.value = ApiError.from(error, 'Failed to delete feature').message;
            isSaving.value = false;
        }
    }

    function handleHideToggle() {
        if (!featureId.value) return;
        emit('visibility-change', {
            featureId: featureId.value,
            hidden: hideOnMainMap.value,
        });
    }

    function updateDate(value: string) {
        formData.created = value;
    }

    return {
        formData,
        rawJsonInput,
        isSaving,
        errorMessage,
        iconUploadError,
        currentIconUrl,
        isCustomIcon,
        hideOnMainMap,
        featureId,
        geometryType,
        isPoint,
        isLine,
        isPolygon,
        systemTags,
        createdDateForInput,
        onStrokeColorChange,
        handleIconSelectedFromSelector,
        handleRemoveIcon,
        handleSubmit,
        handleDelete,
        handleHideToggle,
        updateDate,
    };
}
