package com.example.yakallim.ocr.exception

object OcrErrorMessageResolver {
    fun resolve(e: Throwable): String {
        return when (e) {
            is OcrException.InvalidFileExtensionException -> "지원하지 않는 이미지 형식입니다. JPG 또는 PNG 이미지 파일로 다시 올려주세요."
            is OcrException.EmptyFileException -> "업로드된 이미지 파일이 비어 있습니다. 이미지를 다시 확인해 주세요."
            else -> "복약 안내서 분석 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
        }
    }
}
