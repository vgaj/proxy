FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

COPY LICENSE .
COPY server/src/main/java/com/github/vgaj/proxy/ProxyServer.java ProxyServer.java

EXPOSE 8888 9999

ENTRYPOINT ["java", "ProxyServer.java"]
