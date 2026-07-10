export async function copyToClipboard(text) {
  if (navigator.clipboard?.writeText && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      /* fall through */
    }
  }

  try {
    const textArea = document.createElement('textarea');
    textArea.value = text;
    textArea.readOnly = false;
    textArea.contentEditable = 'true';
    textArea.style.position = 'absolute';
    textArea.style.left = '-9999px';
    textArea.style.top = `${window.pageYOffset || document.documentElement.scrollTop}px`;
    textArea.style.opacity = '0';
    textArea.style.height = '1px';
    textArea.style.width = '1px';
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    textArea.setSelectionRange(0, 99999);
    const successful = document.execCommand('copy');
    document.body.removeChild(textArea);
    return successful;
  } catch {
    return false;
  }
}
