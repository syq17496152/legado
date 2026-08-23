package io.legado.app.lib.cloud

import io.legado.app.exception.NoStackTraceException

class S3CapacityFullException(
    message: String = "All S3 containers are full",
    val scope: S3ContainerScope = S3ContainerScope.DEFAULT
) : NoStackTraceException(message)

