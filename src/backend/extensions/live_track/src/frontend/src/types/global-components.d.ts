/**
 * Augments Vue's `GlobalComponents` so the template type-checker recognizes the platform UI
 * parts the core app registers globally on the extension's app instance (see `createRouteWrapper`
 * in the core frontend) without a local import. Declared loosely, matching how these components
 * are treated elsewhere in extension code.
 */
import type { DefineComponent } from 'vue';

type LooseComponent = DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;

declare module '@vue/runtime-core' {
    interface GlobalComponents {
        BaseButton: LooseComponent;
        Loader: LooseComponent;
        ToggleButton: LooseComponent;
        SettingsInput: LooseComponent;
        ColorPickerElement: LooseComponent;
    }
}

export {};
