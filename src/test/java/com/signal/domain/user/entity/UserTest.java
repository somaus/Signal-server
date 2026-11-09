package com.signal.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserTest {

    private User user() {
        return User.builder().email("a@b.c").password("pw").name("홍길동").age(20).gender(User.Gender.MALE).build();
    }

    @Test
    void 새로_만들면_토큰_버전이_0이다() {
        assertThat(user().getTokenVersion()).isZero();
    }

    @Test
    void invalidateTokens할_때마다_토큰_버전이_오른다() {
        User u = user();

        u.invalidateTokens();
        u.invalidateTokens();

        assertThat(u.getTokenVersion()).isEqualTo(2);
    }

    @Test
    void updateProfile에서_null인_항목은_바꾸지_않는다() {
        User u = user();

        u.updateProfile(null, 30, null);

        assertThat(u.getName()).isEqualTo("홍길동");
        assertThat(u.getAge()).isEqualTo(30);
        assertThat(u.getGender()).isEqualTo(User.Gender.MALE);
    }

    @Test
    void updateProfile에_모든_값을_주면_모두_바뀐다() {
        User u = user();

        u.updateProfile("김철수", 40, User.Gender.FEMALE);

        assertThat(u.getName()).isEqualTo("김철수");
        assertThat(u.getAge()).isEqualTo(40);
        assertThat(u.getGender()).isEqualTo(User.Gender.FEMALE);
    }
}
