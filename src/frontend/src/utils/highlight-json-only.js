// Custom highlight.js build that only includes JSON language
// This dramatically reduces bundle size from ~970KB to ~30KB
// Import directly from highlight.js - our plugin allows this file to use the real package
import hljs from 'highlight.js/lib/core'
import json from 'highlight.js/lib/languages/json'

// Register only the JSON language
hljs.registerLanguage('json', json)

export default hljs

