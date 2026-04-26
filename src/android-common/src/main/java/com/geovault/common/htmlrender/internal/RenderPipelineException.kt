package com.geovault.common.htmlrender.internal

import com.geovault.common.htmlrender.RenderError

internal class RenderPipelineException(
    val error: RenderError,
) : Exception(error.message, error.cause)
