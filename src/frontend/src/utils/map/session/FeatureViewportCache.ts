import type { VaultFeature } from '@/contracts/feature';

const DEFAULT_CAP = 200;

export class FeatureViewportCache {
    private readonly entries = new Map<string, VaultFeature[]>();
    private readonly cap: number;

    constructor(cap = DEFAULT_CAP) {
        this.cap = cap;
    }

    get(key: string): VaultFeature[] | undefined {
        const value = this.entries.get(key);
        if (!value) return undefined;
        this.entries.delete(key);
        this.entries.set(key, value);
        return value;
    }

    set(key: string, features: VaultFeature[]): void {
        if (this.entries.has(key)) {
            this.entries.delete(key);
        }
        this.entries.set(key, features);
        while (this.entries.size > this.cap) {
            const oldest = this.entries.keys().next().value;
            if (oldest == undefined) break;
            this.entries.delete(oldest);
        }
    }

    has(key: string): boolean {
        return this.entries.has(key);
    }

    clear(): void {
        this.entries.clear();
    }

    get size(): number {
        return this.entries.size;
    }
}
