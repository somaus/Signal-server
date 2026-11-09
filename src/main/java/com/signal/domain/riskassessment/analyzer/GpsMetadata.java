package com.signal.domain.riskassessment.analyzer;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;
import java.io.ByteArrayInputStream;

/** 이미지 파일의 EXIF에 촬영 위치(GPS)가 들어 있는지 직접 확인한다. 픽셀이 아니라 파일 바이트를 읽는 항목이라 AI에게 묻지 않는다. */
final class GpsMetadata {

    private GpsMetadata() {
    }

    static boolean present(byte[] content) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(content));
            GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            return gpsDirectory != null
                    && gpsDirectory.getGeoLocation() != null
                    && !gpsDirectory.getGeoLocation().isZero();
        } catch (Exception e) {
            // EXIF가 없거나 판독 불가한 형식이면 GPS 정보 없음으로 간주한다.
            return false;
        }
    }
}
