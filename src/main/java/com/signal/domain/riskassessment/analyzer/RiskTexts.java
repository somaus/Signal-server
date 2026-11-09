package com.signal.domain.riskassessment.analyzer;

import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.i18n.ResponseLanguage;
import java.util.List;

/**
 * 위험도 진단 결과 중 AI가 쓰지 않는 정형 문구(위험 요인 이름, 권고 문구 등)의 다국어 표.
 * 위험 요인 type 값(IMAGE_QUALITY, FACE_EXPOSURE, METADATA_LEAK, REVERSE_SEARCH_RISK)은 언어와 무관하게 고정이다.
 */
public final class RiskTexts {

    public static final String IMAGE_QUALITY = "IMAGE_QUALITY";
    public static final String FACE_EXPOSURE = "FACE_EXPOSURE";
    public static final String METADATA_LEAK = "METADATA_LEAK";
    public static final String REVERSE_SEARCH_RISK = "REVERSE_SEARCH_RISK";

    private RiskTexts() {
    }

    /** 위험 요인 이름. 알 수 없는 type이면 fallback(저장된 이름 등)을 그대로 돌려준다. */
    public static String label(String type, ResponseLanguage language, String fallback) {
        if (type == null) {
            return fallback;
        }
        return switch (type) {
            case IMAGE_QUALITY -> pick(language, "이미지 품질", "Image quality", "画像品質", "图像质量");
            case FACE_EXPOSURE -> pick(language, "얼굴 노출도", "Face exposure", "顔の露出度", "面部暴露程度");
            case METADATA_LEAK -> pick(language, "메타데이터 노출", "Metadata exposure", "メタデータの露出", "元数据泄露");
            case REVERSE_SEARCH_RISK -> pick(language, "역검색 노출 위험", "Reverse-search exposure risk",
                    "逆画像検索による特定リスク", "反向图片搜索暴露风险");
            default -> fallback;
        };
    }

    public static String metadataDescription(boolean hasGps, ResponseLanguage language) {
        if (hasGps) {
            return pick(language,
                    "이미지 파일에 촬영 위치(GPS) 정보가 포함되어 있어 노출 위험이 높습니다.",
                    "The image file contains shooting location (GPS) data, so the exposure risk is high.",
                    "画像ファイルに撮影場所（GPS）情報が含まれているため、露出リスクが高いです。",
                    "图片文件包含拍摄位置（GPS）信息，暴露风险较高。");
        }
        return pick(language,
                "이미지 파일에서 위치(GPS) 메타데이터가 발견되지 않았습니다.",
                "No location (GPS) metadata was found in the image file.",
                "画像ファイルから位置情報（GPS）のメタデータは見つかりませんでした。",
                "在图片文件中未发现位置（GPS）元数据。");
    }


    /** 이미지 품질 항목의 설명. 점수의 근거가 된 측정값(얼굴 한 변 픽셀, 선명도)을 그대로 적는다. */
    public static String measuredQuality(int facePx, double sharpness, ResponseLanguage language) {
        String sharp = String.format(java.util.Locale.ROOT, "%.0f", sharpness);
        return pick(language,
                "얼굴 한 변이 " + facePx + "px이고 선명도 측정값은 " + sharp + "입니다. 얼굴이 클수록, 또렷할수록 합성 재료로 쓰기 좋습니다.",
                "The face is " + facePx + "px on its short side with a measured sharpness of " + sharp
                        + ". Larger, sharper faces are easier to use as source material.",
                "顔の短辺は" + facePx + "pxで、鮮明度の測定値は" + sharp + "です。顔が大きく鮮明なほど、合成の素材として使われやすくなります。",
                "面部短边为" + facePx + "px，清晰度测量值为" + sharp + "。面部越大、越清晰，越容易被用作合成素材。");
    }

    /** 얼굴 노출도 항목의 설명. faceCount는 검출한 얼굴 수, 비율은 퍼센트 값이다. */
    public static String measuredExposure(int faceCount, double areaPercent, double frontalPercent,
                                          ResponseLanguage language) {
        String area = String.format(java.util.Locale.ROOT, "%.1f", areaPercent);
        String frontal = String.format(java.util.Locale.ROOT, "%.0f", frontalPercent);
        return pick(language,
                "얼굴 " + faceCount + "개를 검출했고, 가장 노출이 큰 얼굴이 화면의 " + area + "%를 차지하며 정면도는 " + frontal + "%입니다.",
                faceCount + " face(s) detected; the most exposed one covers " + area
                        + "% of the image and is " + frontal + "% frontal.",
                "顔を" + faceCount + "件検出しました。最も露出している顔は画面の" + area + "%を占め、正面度は" + frontal + "%です。",
                "检测到" + faceCount + "张面部，暴露最多的面部占画面的" + area + "%，正面度为" + frontal + "%。");
    }

    public static String defaultNote(ResponseLanguage language) {
        return pick(language, "AI 분석 결과", "AI analysis result", "AI分析結果", "AI分析结果");
    }

    public static String withScore(String note, int score, ResponseLanguage language) {
        String scoreLabel = pick(language, "점수", "score", "スコア", "得分");
        return note + " (" + scoreLabel + ": " + score + ")";
    }

    public static List<String> recommendations(RiskLevel riskLevel, ResponseLanguage language) {
        return switch (riskLevel) {
            case HIGH -> switch (language) {
                case KO -> List.of(
                        "해당 이미지의 공개 게시를 자제하세요.",
                        "이미지 보호 처리(노이즈 삽입) 기능을 이용해 도용 위험을 낮추세요.",
                        "정기적으로 얼굴 모니터링을 신청해 무단 사용 여부를 추적하세요.");
                case EN -> List.of(
                        "Avoid posting this image publicly.",
                        "Use the image protection (noise insertion) feature to lower the risk of misuse.",
                        "Sign up for face monitoring regularly to track unauthorized use.");
                case JA -> List.of(
                        "この画像を公開で投稿するのは控えてください。",
                        "画像保護処理（ノイズ挿入）機能を利用して、悪用のリスクを下げましょう。",
                        "定期的に顔モニタリングに申し込み、無断使用がないか確認しましょう。");
                case ZH_CN -> List.of(
                        "请避免公开发布这张图片。",
                        "使用图像保护处理（噪声插入）功能，降低被盗用的风险。",
                        "定期申请面部监测，以追踪是否存在未经授权的使用。");
            };
            case MEDIUM -> switch (language) {
                case KO -> List.of(
                        "이미지 공개 범위를 지인으로 한정하는 것을 권장합니다.",
                        "이미지 보호 처리 기능을 검토해보세요.");
                case EN -> List.of(
                        "We recommend limiting the audience of this image to people you know.",
                        "Consider using the image protection feature.");
                case JA -> List.of(
                        "画像の公開範囲を知人に限定することをおすすめします。",
                        "画像保護処理機能の利用を検討してみてください。");
                case ZH_CN -> List.of(
                        "建议将图片的公开范围限定为熟人。",
                        "请考虑使用图像保护处理功能。");
            };
            case LOW -> switch (language) {
                case KO -> List.of("특별한 조치 없이도 안전한 수준이지만, 정기적인 점검을 권장합니다.");
                case EN -> List.of("The risk is at a safe level even without special measures, "
                        + "but regular checks are recommended.");
                case JA -> List.of("特別な対策をしなくても安全なレベルですが、定期的な確認をおすすめします。");
                case ZH_CN -> List.of("即使不采取特别措施也处于安全水平，但建议定期检查。");
            };
        };
    }

    private static String pick(ResponseLanguage language, String ko, String en, String ja, String zhCn) {
        return switch (language) {
            case KO -> ko;
            case EN -> en;
            case JA -> ja;
            case ZH_CN -> zhCn;
        };
    }
}
