package android.print;

/**
 * Bridges package-private {@link PrintDocumentAdapter.LayoutResultCallback} for library code outside
 * {@code android.print}.
 */
public abstract class OpenLayoutResultCallback extends PrintDocumentAdapter.LayoutResultCallback {
    protected OpenLayoutResultCallback() {
        super();
    }
}
