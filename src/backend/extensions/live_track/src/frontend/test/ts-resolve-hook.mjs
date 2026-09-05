export async function resolve(specifier, context, nextResolve) {
  try {
    return await nextResolve(specifier, context);
  } catch (error) {
    if (typeof specifier !== 'string') throw error;
    if (specifier.endsWith('.js')) {
      return nextResolve(`${specifier.slice(0, -3)}.ts`, context);
    }
    if (specifier.startsWith('.') || specifier.startsWith('/')) {
      return nextResolve(`${specifier}.ts`, context);
    }
    throw error;
  }
}
