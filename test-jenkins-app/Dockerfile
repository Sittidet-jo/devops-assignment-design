# --- build stage ---
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- run stage ---
FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
# ติดตั้งเฉพาะ dependency ที่ต้องใช้รัน (express)
COPY package*.json ./
RUN npm ci --omit=dev
# นำไฟล์ build และ server.js เข้ามา
COPY --from=build /app/build ./build
COPY server.js .
EXPOSE 8080
CMD ["node", "server.js"]
