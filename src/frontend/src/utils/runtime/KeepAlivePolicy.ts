/** Core pages that opt into `<keep-alive>`. Extension names come from ExtensionRouteTable. */
export const CORE_KEEP_ALIVE_NAMES = ['Map', 'TagsPage'] as const;

export function buildKeepAliveInclude(extensionNames: readonly string[] = []): string[] {
    return [...CORE_KEEP_ALIVE_NAMES, ...extensionNames];
}
