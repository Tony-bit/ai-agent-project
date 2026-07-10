# 基础镜像，项目按 Java 17 编译
FROM eclipse-temurin:17-jre

LABEL maintainer="denny.xiang"

# 配置
ENV PARAMS=""

# 时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 添加应用
COPY target/ai-agent-station-study-app.jar /ai-agent-study-app.jar

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /ai-agent-study-app.jar $PARAMS"]
