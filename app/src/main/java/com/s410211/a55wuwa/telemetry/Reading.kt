package com.s410211.a55wuwa.telemetry

sealed interface Reading<out T> {
    val source: String
    val sampledAtMillis: Long

    data class Value<T>(
        val value: T,
        override val source: String,
        val unit: String,
        override val sampledAtMillis: Long
    ) : Reading<T>

    data class AwaitingSample(
        override val source: String,
        override val sampledAtMillis: Long,
        val reason: String
    ) : Reading<Nothing>

    data class Unsupported(
        override val source: String,
        override val sampledAtMillis: Long,
        val reason: String
    ) : Reading<Nothing>

    data class PermissionDenied(
        override val source: String,
        override val sampledAtMillis: Long,
        val reason: String
    ) : Reading<Nothing>

    data class NotFound(
        override val source: String,
        override val sampledAtMillis: Long,
        val reason: String
    ) : Reading<Nothing>

    data class InvalidData(
        override val source: String,
        override val sampledAtMillis: Long,
        val reason: String
    ) : Reading<Nothing>

    data class TemporarilyUnavailable(
        override val source: String,
        override val sampledAtMillis: Long,
        val reason: String
    ) : Reading<Nothing>

    data class Error(
        override val source: String,
        override val sampledAtMillis: Long,
        val reason: String
    ) : Reading<Nothing>
}
