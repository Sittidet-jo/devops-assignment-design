FROM python:3.11-alpine

WORKDIR /app

COPY requirements.txt .

RUN pip3 install -r requirements.txt

ENV TZ=Asia/Bangkok

COPY . .

# Expose Port
EXPOSE 10104

CMD ["python", "main.py"]
