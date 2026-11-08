package com.signal.domain.user.service;

import com.signal.domain.user.dto.request.UpdateUserRequest;
import com.signal.domain.user.entity.User;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import com.signal.global.util.TransactionUtils;
import com.signal.domain.websearch.service.WebSearchService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private static final long MAX_PROFILE_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final String PROFILE_IMAGE_DIRECTORY = "profile";

    private final UserRepository userRepository;
    private final FileStorage fileStorage;
    private final WebSearchService webSearchService;
    private final UserDataEraser userDataEraser;

    public User getMyProfile(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public User updateProfile(Long userId, UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));

        user.updateProfile(request.name(), request.age(), request.gender());
        return user;
    }

    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));
        webSearchService.deleteByOwner(userId);
        userDataEraser.eraseAll(userId, user.getProfileImageUrl());
        userRepository.delete(user);
    }

    @Transactional
    public String updateProfileImage(Long userId, MultipartFile image) {
        validateImage(image);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));

        String previousImageUrl = user.getProfileImageUrl();
        String profileImageUrl = fileStorage.store(image, PROFILE_IMAGE_DIRECTORY);
        user.updateProfileImage(profileImageUrl);
        if (StringUtils.hasText(previousImageUrl)) {
            // 바꾸기 전 얼굴 사진이 디스크에 남지 않게 한다.
            TransactionUtils.runAfterCommit(() -> fileStorage.delete(previousImageUrl));
        }

        return profileImageUrl;
    }

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new SignalException(ErrorCode.INVALID_INPUT);
        }
        if (image.getSize() > MAX_PROFILE_IMAGE_SIZE) {
            throw new SignalException(ErrorCode.FILE_TOO_LARGE);
        }
        if (!ALLOWED_CONTENT_TYPES.contains(image.getContentType())) {
            throw new SignalException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }
}
