package com.example.yakallim.ocr.service

import com.example.yakallim.medicine.service.MedicineService
import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.engine.N8nOcrClient
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.model.PrescribedMedicine
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Service
@ConditionalOnProperty(name = ["ocr.type"], havingValue = "n8n")
class N8nOcrService(
    ocrJobRepository: OcrJobRepository,
    ocrProgressManager: OcrProgressManager,
    private val n8nOcrClient: N8nOcrClient,
    private val medicineService: MedicineService,
    @param:Qualifier("FCM_CLIENT") private val notifier: PushNotificationClient,
    @Value("\${ocr.upload-dir:outputs/api-images}") uploadDirStr: String
) : OcrService(ocrJobRepository, ocrProgressManager, uploadDirStr) {

    private val fcmTokenMap = ConcurrentHashMap<String, String>()

    override fun processJob(
        jobId: String,
        targetPath: Path,
        uniqueFileName: String,
        fcmToken: String?,
        delay: Long?
    ) {
        if (fcmToken != null) {
            fcmTokenMap[jobId] = fcmToken
        }
        n8nOcrClient.sendToN8nAsync(jobId, targetPath.toFile(), fcmToken) { failedJobId ->
            fcmTokenMap.remove(failedJobId)
        }
    }

    /** PROCESSING 타임아웃으로 강제 종료될 때 호출된다. 남아있는 FCM 토큰을 정리하고, 있으면 실패 알림을 보낸다. */
    fun handleTimeout(jobId: String, userFacingMessage: String) {
        val token = fcmTokenMap.remove(jobId) ?: return
        notifier.notify(
            token = token,
            title = "복약 안내서 분석 실패",
            body = userFacingMessage,
            data = mapOf(
                "jobId" to jobId,
                "status" to "FAILED",
                "errorCode" to "OCR_PROCESSING_TIMEOUT",
                "message" to userFacingMessage
            )
        )
    }

    /** 로컬 파이프라인(PrescriptionParser)과 동일하게, n8n이 넘긴 약품명도 서버 사전으로 표준화한다. */
    private fun normalizeMedicineNames(medicines: List<PrescribedMedicine>): List<PrescribedMedicine> =
        medicines.map { medicine ->
            val standardName = medicineService.findStandardName(medicine.medicineName)
            medicine.copy(
                medicineName = standardName,
                rawName = medicine.medicineName,
                autoCorrected = standardName != medicine.medicineName
            )
        }

    fun handleCallback(jobId: String, medicines: List<PrescribedMedicine>) {
        val response = OcrResponse(
            fileName = "n8n_ocr_$jobId",
            message = "복약 안내서 분석이 완료되었습니다.\n복약 지침을 확인해 보세요.",
            textBlocks = emptyList(),
            medicines = normalizeMedicineNames(medicines)
        )

        val transitionApplied = ocrJobRepository.updateToCompleted(jobId, response)

        val token = fcmTokenMap.remove(jobId)

        if (transitionApplied) {
            ocrProgressManager.publishProgress(jobId, PipelineStep.COMPLETED, response.message)

            if (!token.isNullOrEmpty()) {
                notifier.notify(
                    token = token,
                    title = "복약 안내서 분석 완료",
                    body = response.message,
                    data = mapOf("jobId" to jobId, "status" to "COMPLETED", "message" to response.message)
                )
            }
        }
    }
}
