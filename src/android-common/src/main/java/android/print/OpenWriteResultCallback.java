package android.print;

/**
 * Bridges package-private {@link PrintDocumentAdapter.WriteResultCallback} for library code outside
 * {@code android.print}.
 */
public abstract class OpenWriteResultCallback extends PrintDocumentAdapter.WriteResultCallback {
    protected OpenWriteResultCallback() {
        super();
    }
}
