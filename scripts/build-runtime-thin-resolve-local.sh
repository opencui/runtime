#/usr/bin/env bash
# cd <repo dir> && bash scripts/build-runtime-thin-resolve-local.sh
set -ex
rm -rf $HOME/.gradle/daemon/*

if [ ! -d "$M2" ]; then
    PATH1=$HOME/.m2/repository/org/springframework/boot/experimental/spring-boot-thin-launcher/1.0.28.RELEASE/
    (mkdir -p $PATH1 && cd $PATH1 && wget -nc https://repo.spring.io/artifactory/release/org/springframework/boot/experimental/spring-boot-thin-launcher/1.0.28.RELEASE/spring-boot-thin-launcher-1.0.28.RELEASE-exec.jar{,.asc})

    (cd core       && timeout 120  ./gradlew publishToMavenLocal)
    (cd extensions && timeout 120  ./gradlew publishToMavenLocal)
    (cd private    && timeout 120  ./gradlew publishToMavenLocal)
    (cd private    && timeout 120  ./gradlew clean)
    (cd private    && timeout 120  ./gradlew thinJar)
    (cd private    && timeout 1200 ./gradlew thinResolve)
    cp -a $HOME/.m2/repository/services private/build/thin/root/repository/

    mkdir -p $M2
    cp -a private/build/thin/root/repository $M2
fi


(cd agents && chmod +x gradlew)
(cd agents && timeout 999 ./gradlew build -PprojVersion=01)

(cd agents && (cat build.gradle | grep "io.framely.dispatcher:native" 2>&1 > /dev/null))  && { (cd agents && timeout 20 java --add-modules=jdk.unsupported -Dbot.prefix=${OrgName}.${AgentName} -Dindexing=true -jar build/libs/*-01.jar --server.port=9090 2>&1 || exit 1 ); }
(cd agents && (cat build.gradle | grep "io.opencuil.dispatcher:spring" 2>&1 > /dev/null)) && { (cd agents && timeout 20 java                               -Dbot.prefix=${ArgName}.${AgentName}                 -jar build/libs/*-01.jar --server.port=9090 2>&1 || exit 1 ); }
echo -n