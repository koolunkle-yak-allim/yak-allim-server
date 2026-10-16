package com.example.yakallim.global.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(ex: BusinessException): ResponseEntity<ErrorResponse> {
        log.error("Business exception occurred: [{}], message: {}", ex.status, ex.message)
        return errorResponse(ex.status, ex.message)
    }

    // 매핑되는 컨트롤러가 없는 요청(예: "/" 루트 직접 접속, 잘못된 경로 변수로 조합된
    // URL)은 정적 리소스 조회로 취급되어 이 예외가 발생한다. 아래 Exception 핸들러가
    // 이를 덮어 500으로 응답하지 않도록 먼저 잡아 404로 정확히 응답한다.
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(ex: NoResourceFoundException): ResponseEntity<ErrorResponse> =
        errorResponse(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다.")

    // 아래 예외들은 Spring이 원래 4xx로 응답하지만, Exception 핸들러가 먼저 잡으면 500이 된다.
    // 클라이언트 요청이 잘못된 경우이므로 원인에 맞는 4xx로 응답한다.
    @ExceptionHandler(MissingServletRequestPartException::class, MissingServletRequestParameterException::class)
    fun handleMissingRequestValue(ex: Exception): ResponseEntity<ErrorResponse> {
        val name = when (ex) {
            is MissingServletRequestPartException -> ex.requestPartName
            is MissingServletRequestParameterException -> ex.parameterName
            else -> null
        }
        log.warn("Rejected request with missing value: {}", name)
        return errorResponse(HttpStatus.BAD_REQUEST, "필수 요청 값이 없습니다: $name")
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "요청 값이 올바르지 않습니다."
        log.warn("Rejected request with invalid body: {}", message)
        return errorResponse(HttpStatus.BAD_REQUEST, message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.warn("Rejected request with unreadable body: {}", ex.mostSpecificCause.message)
        return errorResponse(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다.")
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceededException(ex: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> {
        log.warn("Rejected upload exceeding size limit: {}", ex.message)
        return errorResponse(HttpStatus.PAYLOAD_TOO_LARGE, "업로드할 수 있는 파일 크기를 초과했습니다.")
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleHttpRequestMethodNotSupportedException(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> =
        errorResponse(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다: ${ex.method}")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleHttpMediaTypeNotSupportedException(ex: HttpMediaTypeNotSupportedException): ResponseEntity<ErrorResponse> =
        errorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다: ${ex.contentType}")

    @ExceptionHandler(Exception::class)
    fun handleGeneralException(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled internal server exception occurred", ex)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.")
    }

    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun handleAsyncRequestNotUsableException() {
        log.warn("SSE stream unavailable due to client disconnect")
    }

    private fun errorResponse(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(status = status.value(), error = status.reasonPhrase, message = message))
}
