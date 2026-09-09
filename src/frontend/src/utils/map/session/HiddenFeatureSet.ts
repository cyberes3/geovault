export class HiddenFeatureSet {
    private readonly ids = new Set<string>();

    replace(ids: Iterable<string | number>): void {
        this.ids.clear();
        for (const id of ids) {
            if (id == null || id === '') continue;
            this.ids.add(String(id));
        }
    }

    add(id: string | number): void {
        this.ids.add(String(id));
    }

    delete(id: string | number): void {
        this.ids.delete(String(id));
    }

    has(id: string | number): boolean {
        return this.ids.has(String(id));
    }

    clear(): void {
        this.ids.clear();
    }

    values(): string[] {
        return Array.from(this.ids);
    }

    equals(ids: Iterable<string | number>): boolean {
        const next = new Set(Array.from(ids, (id) => String(id)));
        if (next.size !== this.ids.size) return false;
        for (const id of next) {
            if (!this.ids.has(id)) return false;
        }
        return true;
    }
}
