FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py ./
COPY templates/ templates/
COPY static/ static/
COPY agent/ agent/

# all runtime state (received/, shared/, certs/, .secret_key) lives here
ENV DROPLET_HOME=/data
VOLUME /data

EXPOSE 8000
CMD ["python", "app.py"]
