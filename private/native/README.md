#  Deploy Framely Chatbots

When you create or clone chatbot on Framely, you can decide whether you want to have Framely host the chatbot for you, or whether you want to deploy it yourself. This document shows you how to deploy your chatbot after you export your chatbot from Framely platform.

Since the chatbot exported from Framely is actually generated kotlin code, in theory you can deploy it anyway you like. Here is how you deploy Framely chatbot in different environment.

## Deploy it locally.
During the development, it is usually useful to bring up the chatbot on a linux box locally.

### Start the services needed by dialog understanding
For this part, yoiu will need to install the docker, and optionally add youself to docker group so that you do not need to use sudo all the time.

Bring up the services needed for dialog understanding (DU), it is useful for both run test, and actually service. The first docker image is transformer based abstractive understanding service, and the second is the Duckling service for extractive understanding. 
```shell script
docker run -it --rm -p 8500:8500 -p 8501:8501 registry.us-east-1.aliyuncs.com/framely/apps:du.private--20220815--tag--du-debug-v10.3-v2.2
docker run -it --rm -p 8000:8000 registry.cn-beijing.aliyuncs.com/ni/apps:0616-v1
```

### Build and running the exported chatbot
For this part, make sure you have java (at least 11), quarkus (at least 1.10), and gradle (6.7.1 and above) installed.
Also, for now, we still use jvm to run the service. We will later support the native deployment.

Extract the exported chatbot source code (in form of tar ball) into the desired directory. 


The application can be packaged using:
```shell script
# From framely home, the exported chatbot source is always in form of {{ OrgName }}_{{ AgentName }}.tar.gz.
tar -xf where/{{ OrgName }}_{{ AgentName }}_{{ Version }}.tar.gz  -C quarkus/agents/
```

Before you build things, modify configuration of the services needed by dialog understanding:
```shell script
# change to these in quarkus/src/main/resources/application.properties if you also
# started these services locally.
du.host = localhost
du.port = 8501
du.duckling = http://localhost:8000/parse
```

You can then build the agent with the following command:
```shell script
./gradlew :quarkus:build
./gradlew :quarkus:buildAgentJar -Porg_name={{ OrgName }} -Pagent_name={{ AgentName }} -Pagent_lang={{ lang }} -Pagent_version={{version (start with v_)}}
```

It produces the `quarkus-1.0-SNAPSHOT-runner.jar` file in the `/build` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/lib` directory. If you want to build an _über-jar_, execute the following command:

```shell script
./gradlew :quarkus:build -Dquarkus.package.type=uber-jar -Porg_name={{ OrgName }} -Pagent_name={{ AgentName }} -Pagent_lang={{ lang }}
```

You can then run the binary:
```shell script
java -jar quarkus/build/quarkus-1.1-SNAPSHOT-runner.jar
```


