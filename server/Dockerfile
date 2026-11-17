FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 ALBUM_DATA=/data ALBUM_LIBRARY=/library
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py ./
COPY web/ ./web/
RUN useradd --uid 10001 --create-home album && mkdir /data /library && chown album:album /data /library
USER album
EXPOSE 8765
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/api/status', timeout=2).close()" || exit 1
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8765", "--no-access-log"]
