package com.geovault.tracker

sealed class AppError {
    data object MissingServerUrl : AppError()
    data object Network : AppError()
    data object Unauthorized : AppError()
    data object NotFound : AppError()
    data class Server(val code: Int) : AppError()
    data class Validation(val message: String?) : AppError()
    data object Unknown : AppError()
}

sealed class RepositoryResult<out T> {
    data class Success<T>(val data: T) : RepositoryResult<T>()
    data class Failure(val error: AppError) : RepositoryResult<Nothing>()
}

