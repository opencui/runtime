# OpenCUI | Runtime

Runtime is skeleton for private deploy the chatbot you defined on the platform and since exported from platfrom the generated Kotlin code. 

For chatbot developer:
```
// find the working directory:
git clone https://github.com/opencui/runtime.git
cd runtime
git submodule init
git submodule update
(cd core && git pull)
(cd extensions && git pull)
// Extract the exported kotlin project into same directory that hosts core and extensions. 
mkdir botname
cd botname
tar xzvf path/to/exported/bot.tar.gz
// make there is zh/ or en/ directory, and build.gradle/setting.gradle in the current directory.
./gradlew build
java -Ddu.host=ni-dialogflow-du.ni-framely.svc.cluster.local -Ddu.port=80 -Dbot.prefix={orgname.botname} -Ddu.duckling=http://ni-dialogflow-duckling.ni-framely.svc.cluster.local:80/parse -jar build/libs/dispatcher-1.1-SNAPSHOT.jar

```
