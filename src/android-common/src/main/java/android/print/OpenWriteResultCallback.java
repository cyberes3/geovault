package android.print;

/**
 * Documented platform bridge to package-private
 * {@link PrintDocumentAdapter.WriteResultCallback} so library code outside
 * {@code android.print} can implement WebView print write callbacks.
 */
public abstract class OpenWriteResultCallback extends PrintDocumentAdapter.WriteResultCallback {
    protected OpenWriteResultCallback() {
        super();
    }
}
