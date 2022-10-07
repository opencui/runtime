# OpenCUI Runtime
OpenCUI is a declarative and component-based framework for conversational user interface (CUI), designed to make it easy to build conversational frontend for your backend services.


Dialog Understanding:

The OpenCUI runtime defines a set of interface for dialog understanding, and provides 
reference implementation based on duckling and transformer based Tensorflow models that can be accessed from:
```
docker run -it --rm -p 8000:8000 registry.cn-beijing.aliyuncs.com/ni/apps:0616-v1
docker run -it --rm -p 8501:8501 registry.us-east-1.aliyuncs.com/framely/apps:du.private--20220815--tag--du-debug-v10.3-v2.2
```

For core developer:
```
./gradlew core:test
```

For extension developer:

Use extension directory.

For chatbot builder:

Export the declared chatbot on build.opencui.io, and follow the readme there.
