package com.example.yakallim.ocr.exception

import org.springframework.http.HttpStatus

sealed class OcrException(
    val status: HttpStatus,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    class EmptyFileException(message: String = "파일이 업로드되지 않았거나 비어 있습니다.") :
        OcrException(HttpStatus.BAD_REQUEST, message)

    class FileSaveException(message: String = "서버에 파일을 저장하는 동안 오류가 발생했습니다.", cause: Throwable? = null) :
        OcrException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause)

    class JobNotFoundException(message: String) :
        OcrException(HttpStatus.NOT_FOUND, message)

    class IllegalJobStateException(message: String) :
        OcrException(HttpStatus.BAD_REQUEST, message)

    class InvalidFileExtensionException(message: String = "허용되지 않는 파일 확장자입니다.") :
        OcrException(HttpStatus.BAD_REQUEST, message)

    class UnauthorizedWebhookException(message: String = "유효하지 않은 webhook 요청입니다.") :
        OcrException(HttpStatus.UNAUTHORIZED, message)
}