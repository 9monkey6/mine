FROM eclipse-temurin:8-jre-jammy

# 安装 curl（用于检测 nacos 是否启动）
RUN apt-get update && apt-get install -y curl --no-install-recommends && rm -rf /var/lib/apt/lists/*

COPY target/*.jar app.jar

# 启动脚本：等待 nacos 就绪后，再启动 java 服务
ENTRYPOINT ["sh", "-c", "\
until curl -s http://nacos:8848/nacos; do\n\
  echo 'Waiting for Nacos...'\n\
  sleep 3\n\
done\n\
echo 'Nacos 已启动，开始运行网关！'\n\
java -jar /app.jar\
"]