package com.signal.domain.deepfakedetection.detector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.deepfakedetection.entity.DetectionBand;
import com.signal.domain.deepfakedetection.entity.Evidence;
import com.signal.domain.deepfakedetection.entity.Region;
import com.signal.global.ai.LlmJsonExtractor;
import com.signal.global.ai.VisionCompletionClient;
import com.signal.global.i18n.LanguageContext;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 비전 LLM으로 이미 정해진 등급을 설명하는 {@link DeepfakeExplainer}. deepfake-detection.explain.enabled=true일 때만 켜진다.
 *
 * 이 클래스는 판정에 관여하지 않는다. 프롬프트에 등급을 알려 주고, 이미지에서 실제로 보이는 특징만 서술하게 하며,
 * 등급이 LOW나 UNDETERMINED이면 호출하지 않는다(설명할 근거가 없는데 근거를 만들어 내지 않게 한다).
 * 단정 표현이 들어간 문장은 버린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "deepfake-detection.explain.enabled", havingValue = "true")
public class LlmDeepfakeExplainer implements DeepfakeExplainer {

    private static final int MAX_OBSERVATIONS = 3;
    /** 합성 가능성 설명에 쓰면 안 되는 단정 표현. */
    private static final List<String> ASSERTIVE_PHRASES = List.of("확정", "확실히", "틀림없이", "100%", "가짜입니다", "진짜입니다");

    private static final String SYSTEM_PROMPT = """
            당신은 'Signal' 서비스에서 이미 내려진 이미지 합성 가능성 판정을 사용자에게 설명하는 보조자입니다.
            판정은 전용 탐지 모델이 이미 정했고, 당신은 그것을 바꾸거나 다시 평가할 수 없습니다.

            이번 이미지의 판정: %s

            규칙:
            - 판정 등급, 점수, 확률, 진짜/가짜 여부를 말하지 마세요. 설명만 합니다.
            - 이미지에서 실제로 보이는 특징만 한 문장씩 서술하세요. 뚜렷한 특징이 없거나 판정과 어긋나면 빈 배열로 답하세요.
            - '확정', '확실히', '틀림없이' 같은 단정 표현을 쓰지 말고 '~로 보일 수 있습니다'처럼 가능성으로 말하세요.
            - 아래 JSON 형식으로만, 코드블록 없이 순수 JSON만 출력하세요. 최대 3개까지.

            {
              "observations": [
                {"title": "근거의 핵심을 2~4단어 명사구로 요약 (예: 부자연스러운 피부 질감, 배경 경계 번짐)",
                 "description": "한국어 한 문장",
                 "region": {"x":0-1,"y":0-1,"width":0-1,"height":0-1} 형태의 정규화 좌표, 특정 부위를 지목할 수 없으면 null}
              ]
            }
            """;
    private static final String USER_PROMPT = "이미지에서 눈에 띄는 특징을 시스템 프롬프트의 JSON 형식으로만 답해 주세요.";

    private final VisionCompletionClient visionCompletionClient;
    private final ObjectMapper objectMapper;

    public LlmDeepfakeExplainer(VisionCompletionClient visionCompletionClient, ObjectMapper objectMapper) {
        this.visionCompletionClient = visionCompletionClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Evidence> explain(byte[] content, String mimeType, DetectionBand band, int width, int height) {
        if (band != DetectionBand.UNCERTAIN && band != DetectionBand.HIGH) {
            return List.of();
        }
        try {
            String system = SYSTEM_PROMPT.formatted(describe(band)) + LanguageContext.current().promptInstruction();
            String raw = visionCompletionClient.complete(content, mimeType, system, USER_PROMPT);
            Observations parsed = objectMapper.readValue(LlmJsonExtractor.stripCodeFence(raw), Observations.class);
            return toEvidences(parsed, width, height);
        } catch (Exception e) {
            log.warn("탐지 결과 설명을 만들지 못했습니다. 설명 없이 결과만 보여 줍니다.", e);
            return List.of();
        }
    }

    private static String describe(DetectionBand band) {
        return band == DetectionBand.HIGH ? "합성 가능성이 높음" : "합성 가능성이 불확실함(모델 점수가 중간)";
    }

    private List<Evidence> toEvidences(Observations parsed, int width, int height) {
        if (parsed == null || parsed.observations() == null) {
            return List.of();
        }
        List<Evidence> evidences = new ArrayList<>();
        for (Observation observation : parsed.observations()) {
            if (evidences.size() >= MAX_OBSERVATIONS) {
                break;
            }
            if (observation == null || observation.description() == null || observation.description().isBlank()) {
                continue;
            }
            String description = observation.description().trim();
            if (isAssertive(description)) {
                continue;
            }
            evidences.add(Evidence.builder()
                    .type(toTitle(observation.title()))
                    .description(description)
                    .region(toRegion(observation.region(), width, height))
                    .frame(null)
                    .build());
        }
        return evidences;
    }

    /** 탭 이름으로 쓰는 짧은 요약. 없거나 너무 길면 OTHER(프론트가 기본 이름을 붙인다). */
    static String toTitle(String title) {
        if (title == null || title.isBlank()) {
            return "OTHER";
        }
        String trimmed = title.trim();
        return trimmed.length() > 20 || isAssertive(trimmed) ? "OTHER" : trimmed;
    }

    static boolean isAssertive(String description) {
        return ASSERTIVE_PHRASES.stream().anyMatch(description::contains);
    }

    /** RegionResponse.from()이 null을 받지 못하므로 위치를 모르면 이미지 중심 영역으로 근사한다. */
    private static Region toRegion(NormalizedRegion region, int width, int height) {
        if (region != null && width > 0 && height > 0) {
            return Region.builder()
                    .x(toPixel(region.x(), width))
                    .y(toPixel(region.y(), height))
                    .width(Math.max(1, toPixel(region.width(), width)))
                    .height(Math.max(1, toPixel(region.height(), height)))
                    .build();
        }
        int w = Math.max(width, 200);
        int h = Math.max(height, 200);
        return Region.builder().x(w / 4).y(h / 4).width(w / 2).height(h / 2).build();
    }

    private static int toPixel(double normalized, int dimension) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, normalized)) * dimension);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Observations(List<Observation> observations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Observation(String title, String description, NormalizedRegion region) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NormalizedRegion(double x, double y, double width, double height) {
    }
}
