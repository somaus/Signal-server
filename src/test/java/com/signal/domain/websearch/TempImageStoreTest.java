package com.signal.domain.websearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.websearch.client.TempImageStore;
import org.junit.jupiter.api.Test;

class TempImageStoreTest {

    @Test
    void 넣은_이미지를_토큰으로_꺼내고_지우면_더_이상_받을_수_없다() {
        TempImageStore store = new TempImageStore(300);

        String token = store.put(new byte[]{1, 2}, "image/jpeg");

        assertThat(store.get(token).content()).containsExactly(1, 2);
        store.remove(token);
        assertThat(store.get(token)).isNull();
    }

    @Test
    void 만료된_이미지는_받을_수_없다() {
        TempImageStore store = new TempImageStore(-1);

        assertThat(store.get(store.put(new byte[]{1}, "image/jpeg"))).isNull();
    }

    @Test
    void 토큰은_추측하기_어렵고_매번_다르다() {
        TempImageStore store = new TempImageStore(300);

        String a = store.put(new byte[]{1}, "image/jpeg");
        String b = store.put(new byte[]{1}, "image/jpeg");

        assertThat(a).hasSize(64).isNotEqualTo(b);
        assertThat(store.get("nope")).isNull();
    }
}
