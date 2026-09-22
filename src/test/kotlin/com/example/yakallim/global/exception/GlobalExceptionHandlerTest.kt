package com.example.yakallim.global.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.resource.NoResourceFoundException

/** 매핑되지 않은 경로(예: "/" 직접 접속)가 500이 아니라 404로 정확히 응답하는지 검증한다. */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    @DisplayName("NoResourceFoundException은 500이 아닌 404로 응답한다")
    fun respondsWith404_forNoResourceFoundException() {
        val exception = NoResourceFoundException(org.springframework.http.HttpMethod.GET, "")

        val response = handler.handleNoResourceFoundException(exception)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals(HttpStatus.NOT_FOUND.value(), response.body?.status)
    }

    @Test
    @DisplayName("그 외 예상치 못한 예외는 여전히 500으로 응답한다")
    fun respondsWith500_forOtherExceptions() {
        val response = handler.handleGeneralException(RuntimeException("boom"))

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("서버 내부 오류가 발생했습니다.", response.body?.message)
    }
}
