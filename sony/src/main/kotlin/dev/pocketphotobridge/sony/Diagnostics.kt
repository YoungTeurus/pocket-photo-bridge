package dev.pocketphotobridge.sony

enum class Failure {
    WRONG_MODE, UNSUPPORTED_SERVICE, INVALID_XML, UNSAFE_URL, LIMIT_EXCEEDED,
    AMBIGUOUS_SELECTION, NO_JPEG, TRUNCATED_JPEG, HTTP_ERROR, CANCELLED
}
class ProtocolFailure(val failure: Failure) : RuntimeException(failure.name)
enum class Stage { DESCRIPTOR, ACTIONS, TRANSFER_START, PUSH_PAGE, SELECTION, JPEG_BYTES, TRANSFER_END, END_UNCONFIRMED }
data class Diagnostic(val stage: Stage, val count: Long? = null) {
    fun safeText() = stage.name + (count?.let { " count=$it" } ?: "")
}
