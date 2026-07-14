/**
 * Types `this.$store` inside Options API components. Vuex 4 only ships an ambient `store?:
 * Store<any>` on `ComponentCustomOptions` (the `store:` component option), not on
 * `ComponentCustomProperties` (`this.$store`), so without this every `this.$store.*` access is
 * an unresolvable-type error under `vue-tsc`/`@typescript-eslint`'s "unsafe-*" rules.
 */
import type { Store } from 'vuex';
import type { RootState } from '@/assets/js/store';

declare module '@vue/runtime-core' {
    interface ComponentCustomProperties {
        $store: Store<RootState>;
    }
}

export {};
