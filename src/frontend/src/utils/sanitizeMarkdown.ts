import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Render user-supplied markdown/HTML to a string safe for `v-html`.
 * Scripts, event handlers, and javascript: URLs are stripped; images, tables,
 * and other normal HTML from imported KML balloons are kept.
 */
export function sanitizeMarkdown(markdown: string | null | undefined): string {
    if (!markdown) return ''
    const parsed = marked.parse(markdown, { async: false })
    const html = typeof parsed === 'string' ? parsed : String(parsed)
    return DOMPurify.sanitize(html)
}
