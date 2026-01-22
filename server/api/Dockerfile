# 构建阶段
FROM eclipse-temurin:17-jdk-jammy AS builder

# 安装必要的系统库和工具
RUN apt-get update && apt-get install -y \
    maven \
    libgomp1 \
    libgtk2.0-0 \
    libglib2.0-0 \
    libavcodec58 \
    libavformat58 \
    libswscale5 \
    && rm -rf /var/lib/apt/lists/*

# 安装 Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 1. 只复制 pom 文件（父 + 子模块）
COPY pom.xml .
COPY api/pom.xml api/
COPY component/pom.xml component/
# 如果有其他模块（如 printer），也要一并 COPY pom.xml
# COPY printer/pom.xml printer/

# 2. 提前下载依赖（利用缓存）
RUN mvn dependency:go-offline -B -P api

# 3. 再复制源码
COPY component/src component/src
COPY api/src api/src

# 4. 构建子模块（只打包 api）
RUN mvn clean package -DskipTests -P api

# 运行阶段
FROM eclipse-temurin:17-jdk-jammy

# 安装 bytedeco OpenCV 运行时必需库
RUN apt-get update && apt-get install -y \
    libgomp1 \
    libgtk2.0-0 \
    libglib2.0-0 \
    libavcodec58 \
    libavformat58 \
    libswscale5 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/api/target/api-*.jar app.jar

EXPOSE 8538

CMD ["java", "-jar", "app.jar"]
