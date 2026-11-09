package com.signal.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.auth.entity.RefreshToken;
import com.signal.domain.user.entity.User;
import com.signal.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

/** 커스텀 @Query(update, exists 서브쿼리)가 실제 DB에서 유효하고 의도대로 동작하는지 확인한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:refresh_token_query_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class RefreshTokenQueryIntegrationTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long newUserId() {
        User user = User.builder().email("u@example.com").password("pw").name("사용자").age(20)
                .gender(User.Gender.MALE).build();
        return userRepository.saveAndFlush(user).getId();
    }

    @Test
    void markUsed는_한_번만_성공하고_쓴_시각을_기록한다() {
        Long userId = newUserId();
        refreshTokenRepository.saveAndFlush(new RefreshToken("jti-1", userId, "sess-1"));
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 12, 0, 0);

        assertThat(refreshTokenRepository.markUsed("jti-1", now)).isEqualTo(1);
        assertThat(refreshTokenRepository.markUsed("jti-1", now.plusSeconds(1))).isZero();

        entityManager.clear();
        RefreshToken stored = refreshTokenRepository.findById("jti-1").orElseThrow();
        assertThat(stored.isUsed()).isTrue();
        assertThat(stored.getUsedAt()).isEqualTo(now);
    }

    @Test
    void 쓰이지_않은_토큰이_있는_세션만_살아_있다고_본다() {
        Long userId = newUserId();
        refreshTokenRepository.saveAndFlush(new RefreshToken("jti-1", userId, "sess-1"));

        assertThat(userRepository.findTokenVersionByIdWithLiveSession(userId, "sess-1")).isPresent();
        assertThat(userRepository.findTokenVersionByIdWithLiveSession(userId, "other")).isEmpty();

        refreshTokenRepository.markUsed("jti-1", LocalDateTime.now());
        entityManager.clear();
        assertThat(userRepository.findTokenVersionByIdWithLiveSession(userId, "sess-1")).isEmpty();

        refreshTokenRepository.saveAndFlush(new RefreshToken("jti-2", userId, "sess-1"));
        assertThat(userRepository.findTokenVersionByIdWithLiveSession(userId, "sess-1")).isPresent();
        assertThat(userRepository.findTokenVersionByIdWithLiveSession(userId + 1, "sess-1")).isEmpty();
    }
}
