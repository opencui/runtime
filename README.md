# OpenCUI | Runtime

Runtime is skeleton for private deploy the chatbot you defined on the platform and since export the generated Kotlin code. 

Dialog Understanding:

The OpenCUI runtime defines a set of interface for dialog understanding, and provides 
reference implementation based on duckling and transformer based Tensorflow models that can be accessed from:

```
docker run -it --rm -p 8000:8000 registry.cn-beijing.aliyuncs.com/ni/apps:0616-v1
docker run -it --rm -p 8501:8501 registry.us-east-1.aliyuncs.com/framely/apps:du.private--20220815--tag--du-debug-v10.3-v2.2
```

For chatbot developer:
```
// find the working directory:
git clone https://github.com/opencui/runtime.git
cd runtime
git submodule init
git submodule update

// Extract the exported kotlin project into same directory that hosts core and extensions. 
mkdir botname
cd botname
tar xzvf path/to/exported/bot.tar.gz 
./gradlew build
java -Ddu.host=ni-dialogflow-du.ni-framely.svc.cluster.local -Ddu.port=80 -Dbot.prefix={orgname.botname} -Ddu.duckling=http://ni-dialogflow-duckling.ni-framely.svc.cluster.local:80/parse -jar build/libs/dispatcher-1.1-SNAPSHOT.jar

```
