/**
 * Minimal ambient types for the untyped `simple-code-editor` package. Its shipped `.d.vue.ts`
 * declaration can't be resolved under `package.json` "exports", so `vue-tsc` sees it as untyped.
 * Only the default-exported `CodeEditor` component is used in this codebase.
 */
declare module 'simple-code-editor' {
    import type { DefineComponent } from 'vue';

    const CodeEditor: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
    export default CodeEditor;
}
