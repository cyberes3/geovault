import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const { window } = new JSDOM('<!DOCTYPE html>');
Object.defineProperty(globalThis, 'window', { value: window, configurable: true });
Object.defineProperty(globalThis, 'document', { value: window.document, configurable: true });

const { sanitizeMarkdown } = await import('./sanitizeMarkdown.ts');

test('empty input is an empty string', () => {
    assert.equal(sanitizeMarkdown(null), '');
    assert.equal(sanitizeMarkdown(undefined), '');
    assert.equal(sanitizeMarkdown(''), '');
});

test('markdown headings still render', () => {
    const html = sanitizeMarkdown('# Survey mark');
    assert.match(html, /<h1[^>]*>Survey mark<\/h1>/);
});

test('https images are kept', () => {
    const html = sanitizeMarkdown('<img src="https://example.com/icon.png" alt="icon">');
    assert.match(html, /src="https:\/\/example.com\/icon.png"/);
    assert.doesNotMatch(html, /onerror/i);
});

test('img onerror handlers cannot execute', () => {
    const html = sanitizeMarkdown('<img src=x onerror=alert(document.domain)>');
    assert.doesNotMatch(html, /onerror/i);
    assert.doesNotMatch(html, /alert/i);
});

test('script tags are stripped', () => {
    const html = sanitizeMarkdown('<script>alert(1)</script>safe');
    assert.doesNotMatch(html, /<script/i);
    assert.doesNotMatch(html, /alert/i);
    assert.match(html, /safe/);
});

test('svg onload handlers cannot execute', () => {
    const html = sanitizeMarkdown('<svg onload=alert(1)>');
    assert.doesNotMatch(html, /onload/i);
    assert.doesNotMatch(html, /alert/i);
});
