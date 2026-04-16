FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir aiohttp

COPY relay_server.py .

# HTTP management API port (internal — backend calls this)
EXPOSE 7776
# UDP game relay port range (public — clients connect here)
EXPOSE 7777-7900/udp

ENTRYPOINT ["python", "relay_server.py"]
CMD ["--http-port", "7776", "--port-start", "7777", "--port-end", "7900"]
