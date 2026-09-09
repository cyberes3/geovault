export interface ExtensionRouteRecord {
    path: string;
    name: string;
    keepAliveKey: string;
    publicShare: boolean;
    mapLayout: boolean;
    titleMode: 'document' | 'none';
}

export class ExtensionRouteTable {
    private readonly records: ExtensionRouteRecord[] = [];

    record(entry: ExtensionRouteRecord): void {
        this.records.push(entry);
    }

    all(): ExtensionRouteRecord[] {
        return [...this.records];
    }

    keepAliveNames(): string[] {
        return this.records.map((entry) => entry.keepAliveKey);
    }
}

export const extensionRouteTable = new ExtensionRouteTable();

export function wrapperName(kebabName: string, routeName: string): string {
    return `ExtensionBoundary_${kebabName}_${routeName}`;
}
