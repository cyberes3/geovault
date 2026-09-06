package android.print;

/**
 * Documented platform bridge to package-private
 * {@link PrintDocumentAdapter.LayoutResultCallback} so library code outside
 * {@code android.print} can implement WebView print layout callbacks, including
 * {@link PrintDocumentAdapter.LayoutResultCallback#onLayoutCancelled()}.
 */
public abstract class OpenLayoutResultCallback extends PrintDocumentAdapter.LayoutResultCallback {
    protected OpenLayoutResultCallback() {
        super();
    }
}
