/**
 * DOM helpers for the tags page. Kept separate from business logic so the
 * composables don't need to know about tag card markup structure.
 */

/** Scrolls a tag's card into view by matching its rendered label text within `containerElement`. */
export function scrollToTag(containerElement: HTMLElement | null, tagName: string): void {
    if (!containerElement) return;

    const tagContainers = containerElement.querySelectorAll('.bg-white.rounded-lg.shadow-sm');

    for (const container of tagContainers) {
        const tagHeader = container.querySelector('.bg-gray-50');
        if (!tagHeader) continue;

        const tagSpan = tagHeader.querySelector('span.inline-flex');
        if (tagSpan?.textContent.trim() === tagName) {
            container.scrollIntoView({ behavior: 'smooth', block: 'center' });
            break;
        }
    }
}
