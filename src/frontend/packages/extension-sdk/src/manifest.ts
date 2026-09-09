export type MapEngine = 'maplibre' | 'none';

export type DashboardWidgetId = 'pwa_apk';

export interface ExtensionManifest {
    name: string;
    version: string;
    enabled_by_default?: boolean;
    icon?: string | null;
    map_route: boolean;
    public_share_route: boolean;
    description?: string | null;
    umd_global?: string | null;
    requires_map_engine: MapEngine;
    dashboard_widget?: DashboardWidgetId | string | null;
}

export interface ExtensionListItem extends ExtensionManifest {
    frontend_entry?: string;
    frontend_css?: string;
}
