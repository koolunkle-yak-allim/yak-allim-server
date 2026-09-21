package com.example.yakallim.medicine.service

import com.example.yakallim.global.utils.HangulUtils
import com.example.yakallim.medicine.model.Medicine
import com.example.yakallim.medicine.repository.MedicineRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
class MedicineServiceTest {

    @Autowired
    private lateinit var medicineService: MedicineService

    @Autowired
    private lateinit var medicineRepository: MedicineRepository

    @Test
    @DisplayName("약품명 오탈자를 정식 약품명으로 교정한다")
    fun shouldCorrectMedicineNameSpelling() {
        Assertions.assertEquals("아르레온정", medicineService.findStandardName("야르레온정"))
        Assertions.assertEquals("이모튼캡슐", medicineService.findStandardName("모튼슐"))
        Assertions.assertEquals("비드레바서방정150", medicineService.findStandardName("비드레바서방정"))
    }

    @Test
    @DisplayName("사전에 없는 약품명은 원문을 유지한다")
    fun shouldKeepRawNameWhenNotInDictionary() {
        listOf("타이레놀정", "아스피린정", "게보린정", "훼스탈정").forEach {
            Assertions.assertEquals(it, medicineService.findStandardName(it))
        }
    }

    @Test
    @DisplayName("새로운 약품 저장 시 normalizedName이 자동으로 갱신된다")
    @Transactional
    fun shouldAutomaticallyUpdateNormalizedNameOnSave() {
        val medicine = Medicine()
        medicine.name = "테스트약품"

        val saved = medicineRepository.saveAndFlush(medicine)

        Assertions.assertEquals(HangulUtils.normalizeToJamo("테스트약품"), saved.normalizedName)
    }
}
