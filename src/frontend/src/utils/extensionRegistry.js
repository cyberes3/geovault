import { reactive, markRaw } from 'vue';

/**
 * Registry for the dynamic UI extensions register with: nav links, tools-menu entries, and
 * settings tabs. Extension routes go straight through the scoped `router.addRoute()` an
 * extension's `setup()` receives instead (see `@/extensions/extensionLoader`), not through here.
 */
export const extensionRegistry = reactive({
  navLinks: [], // Array of { label, path, fullPath, component }
  tools: [], // Array of { label, fullPath, icon }
  settingsTabs: [], // Array of { label, id, component, icon }

  /**
   * Register a link for the top navigation bar.
   */
  registerNavLink(link) {
    if (link.component) link.component = markRaw(link.component);
    this.navLinks.push(link);
  },

  /**
   * Register a tool for the "Tools" dropdown.
   */
  registerTool(tool) {
    if (tool.icon) tool.icon = markRaw(tool.icon);
    this.tools.push(tool);
  },

  /**
   * Register a new section in the User Settings page. Wrap `tab.component` with
   * `gv_core.createRouteWrapper(component, { api, platformState })` first so it can inject
   * `extensionApi`/`platformState` the same way routed views do.
   */
  registerSettingsTab(tab) {
    if (tab.component) tab.component = markRaw(tab.component);
    if (tab.icon) tab.icon = markRaw(tab.icon);
    this.settingsTabs.push(tab);
  }
});
