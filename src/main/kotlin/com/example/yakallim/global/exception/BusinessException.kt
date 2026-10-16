package com.example.yakallim.global.exception

import org.springframework.http.HttpStatus

/** 클라이언트에 그대로 돌려줄 HTTP 상태와 메시지를 가진 예외. 도메인별 예외는 이 클래스를 상속한다. */
abstract class BusinessException(
    val status: HttpStatus,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
