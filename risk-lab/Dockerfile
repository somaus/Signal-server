# 위험도 분석 측정 서비스. OpenCV(CPU)와 YuNet 모델(약 0.3MB, 이미지에 포함)만 쓰므로 PyTorch와 모델 다운로드가 필요 없다.
FROM python:3.11-slim
LABEL org.opencontainers.image.source="https://github.com/somaus/Signal-server"
WORKDIR /srv
ENV PYTHONUNBUFFERED=1
RUN apt-get update && apt-get install -y --no-install-recommends libglib2.0-0 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY analyzer.py app.py face_detection_yunet_2023mar.onnx LICENSE-yunet.txt ./
RUN useradd --system --uid 10001 --no-create-home app
USER app
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
