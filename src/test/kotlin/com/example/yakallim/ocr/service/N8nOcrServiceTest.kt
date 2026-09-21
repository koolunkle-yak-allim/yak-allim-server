package com.example.yakallim.ocr.service

import com.example.yakallim.medicine.service.MedicineService
import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.engine.N8nOcrClient
import com.example.yakallim.ocr.model.PrescribedMedicine
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** S8: n8n 콜백으로 받은 약품명도 로컬 파이프라인과 동일하게 서버 사전으로 표준화되는지 검증한다. */
@SpringBootTest
class N8nOcrServiceTest {

    @Autowired
    private lateinit var medicineService: MedicineService

    private fun handleCallbackAndCaptureResponse(
        jobId: String,
        medicines: List<PrescribedMedicine>
    ): OcrResponse {
        val ocrJobRepository: OcrJobRepository = mock()
        whenever(ocrJobRepository.updateToCompleted(any(), any())).thenReturn(true)

        val service = N8nOcrService(
            ocrJobRepository = ocrJobRepository,
            ocrProgressManager = mock(),
            n8nOcrClient = mock<N8nOcrClient>(),
            medicineService = medicineService,
            notifier = mock<PushNotificationClient>(),
            uploadDirStr = "outputs/api-images"
        )

        service.handleCallback(jobId, medicines)

        val captor = argumentCaptor<OcrResponse>()
        verify(ocrJobRepository).updateToCompleted(eq(jobId), captor.capture())
        return captor.firstValue
    }

    @Test
    @DisplayName("사전 항목과 가까운 약품명은 표준명으로 교정되고 원문·교정여부가 채워진다")
    fun normalizesCallbackMedicineNameUsingServerDictionary() {
        val medicines = listOf(
            PrescribedMedicine(
                medicineName = "이모튼캡",
                dosagePerTake = "1정",
                dailyFrequency = 3,
                durationDays = 3
            )
        )

        val normalized = handleCallbackAndCaptureResponse("job-1", medicines).medicines.single()

        assertEquals("이모튼캡슐", normalized.medicineName)
        assertEquals("이모튼캡", normalized.rawName)
        assertEquals(true, normalized.autoCorrected)
    }

    @Test
    @DisplayName("사전에 없는 약품명은 원문을 유지하고 autoCorrected가 false다")
    fun keepsRawNameWhenNotInDictionary() {
        val medicines = listOf(
            PrescribedMedicine(
                medicineName = "테스트약품",
                dosagePerTake = "1정",
                dailyFrequency = 3,
                durationDays = 3
            )
        )

        val normalized = handleCallbackAndCaptureResponse("job-2", medicines).medicines.single()

        assertEquals("테스트약품", normalized.medicineName)
        assertEquals("테스트약품", normalized.rawName)
        assertEquals(false, normalized.autoCorrected)
    }
}
