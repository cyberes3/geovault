import { reactive, markRaw } from 'vue';

export interface ExtensionNavLink {
  label: string;
  path?: string;
  fullPath?: string;
  icon?: unknown;
  component?: unknown;
}

export interface ExtensionTool {
  label: string;
  path?: string;
  fullPath?: string;
  icon?: unknown;
}

export interface ExtensionSettingsTab {
  id: string;
  label: string;
  fullPath?: string;
  component?: unknown;
  icon?: unknown;
}

/**
 * Registry for the dynamic UI extensions register with: nav links, tools-menu entries, and
 * settings tabs. Extension routes go straight through the scoped `router.addRoute()` an
 * extension's `setup()` receives instead (see `@/extensions/extensionLoader`), not through here.
 */
export const extensionRegistry = reactive({
  navLinks: [] as ExtensionNavLink[],
  tools: [] as ExtensionTool[],
  settingsTabs: [] as ExtensionSettingsTab[],

  /**
   * Register a link for the top navigation bar.
   */
  registerNavLink(link: ExtensionNavLink): void {
    if (link.component) link.component = markRaw(link.component);
    this.navLinks.push(link);
  },

  /**
   * Register a tool for the "Tools" dropdown.
   */
  registerTool(tool: ExtensionTool): void {
    if (tool.icon) tool.icon = markRaw(tool.icon);
    this.tools.push(tool);
  },

  /**
   * Register a new section in the User Settings page. Wrap `tab.component` with
   * `gv_core.createRouteWrapper(component, { api, platformState })` first so it can inject
   * `extensionApi`/`platformState` the same way routed views do.
   */
  registerSettingsTab(tab: ExtensionSettingsTab): void {
    if (tab.component) tab.component = markRaw(tab.component);
    if (tab.icon) tab.icon = markRaw(tab.icon);
    this.settingsTabs.push(tab);
  }
});
