package com.geovault.common.htmlrender

/**
 * Describes why a render operation failed. Stable for logging and UI messages.
 */
sealed class RenderError {
    abstract val message: String
    abstract val cause: Throwable?

    /** A phase exceeded its configured timeout ([phase] names the step, e.g. `load`, `pdf`, `measure`). */
    data class Timeout(
        val phase: String,
        override val message: String,
        override val cause: Throwable? = null,
    ) : RenderError()

    /** The WebView reported an error while loading or executing content. */
    data class WebViewError(
        override val message: String,
        override val cause: Throwable? = null,
    ) : RenderError()

    /** Requested or measured layout would exceed memory or configured pixel limits. */
    data class LayoutOverflow(
        override val message: String,
        override val cause: Throwable? = null,
    ) : RenderError()

    /** Reading or writing binary output failed. */
    data class IoFailure(
        override val message: String,
        override val cause: Throwable? = null,
    ) : RenderError()

    /** The request violated library constraints (size, URL, settings). */
    data class InvalidRequest(
        override val message: String,
        override val cause: Throwable? = null,
    ) : RenderError()
}
