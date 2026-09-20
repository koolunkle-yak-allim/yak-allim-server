package com.example.yakallim.ocr.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 테스트에서 시간 경과를 직접 제어하기 위한 [Clock] 구현체. */
class MutableClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneId.systemDefault()
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    override fun instant(): Instant = current

    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}
