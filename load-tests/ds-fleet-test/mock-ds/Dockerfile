FROM python:3.11-slim

WORKDIR /app

RUN pip install --no-cache-dir requests

COPY mock_ds.py .

# Health check: backend uses heartbeat, this is for Docker's own health status
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:9090/health', timeout=4)" || exit 1

EXPOSE 9090

CMD ["python3", "mock_ds.py"]
