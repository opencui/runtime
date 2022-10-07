FROM registry-vpc.cn-beijing.aliyuncs.com/ni/tools:gradle-6.7.1-jdk11-cache-v1 as dev
WORKDIR /data/
COPY . .
RUN bash -x .ci.sh

#FROM registry.cn-beijing.aliyuncs.com/ni/tools:jdk-11.0.5
#ENV LANG=zh_CN.UTF-8 LANGUAGE=zh_CN.UTF-8
#RUN echo "deb http://mirrors.aliyun.com/ubuntu/ bionic           main restricted universe multiverse" >  /etc/apt/sources.list && \
#    echo "deb http://mirrors.aliyun.com/ubuntu/ bionic-security  main restricted universe multiverse" >> /etc/apt/sources.list && \
#    echo "deb http://mirrors.aliyun.com/ubuntu/ bionic-updates   main restricted universe multiverse" >> /etc/apt/sources.list && \
#    echo "deb http://mirrors.aliyun.com/ubuntu/ bionic-proposed  main restricted universe multiverse" >> /etc/apt/sources.list && \
#    echo "deb http://mirrors.aliyun.com/ubuntu/ bionic-backports main restricted universe multiverse" >> /etc/apt/sources.list && \
#    apt update && apt-get install -y wget language-pack-zh-hans && apt-get clean -s && locale-gen && \
#    wget -O /healthz_client http://nipodtools.oss-cn-shanghai.aliyuncs.com/tools/Healthz/healthz_client && \
#    chmod +x /healthz_client

FROM registry.cn-beijing.aliyuncs.com/ni/tools:oracle_graalvm-ce-20.3.0-java11
WORKDIR /data/
RUN curl -s http://nipodtools.oss-cn-shanghai.aliyuncs.com/tools/Healthz/healthz_client -o /healthz_client && chmod +x /healthz_client
COPY --from=dev /data/spring/build/ /data/build/
