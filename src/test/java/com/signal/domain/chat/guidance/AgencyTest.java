package com.signal.domain.chat.guidance;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.global.i18n.ResponseLanguage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgencyTest {

    private final Agency agency = new Agency("id", "한국어", "English", "日本語", "中文", "02-000-0000", List.of());

    @Test
    void 언어별_이름에_공통_연락처를_붙인다() {
        assertThat(agency.format(ResponseLanguage.KO)).isEqualTo("한국어 (02-000-0000)");
        assertThat(agency.format(ResponseLanguage.EN)).isEqualTo("English (02-000-0000)");
        assertThat(agency.format(ResponseLanguage.JA)).isEqualTo("日本語 (02-000-0000)");
        assertThat(agency.format(ResponseLanguage.ZH_CN)).isEqualTo("中文 (02-000-0000)");
    }
}
