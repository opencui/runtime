##Integration How to

Framely is designed to handle multiple channels of different types at the same time, and it is also connect to one support (human agent message platform) system. All these channels and support are managed by singlton Dispatcher, and this doc describes how we develop new channel implementation for Framely.

Both channel and support are potentially two faced components on Framely. On one side, it serves restful api so that other messaging source facebook can forward user message to Framely, on another side, it allows dispatcher to forward Framely formated message to different channels uniformly. In essense, channel implementation is responsible to convert the channel dependent messages passed into Framely into a channel independent format on the restful side, after messages is processed by Framely chatbot, channel implementation converts the channel independent response back to channel dependent format and send it out to its destination through provided api for user to consume.   

Some pointers:
1. Channel independent message is defined as FramelyMessage.  
2. Session dependent information can be saved into UserSession, and bot dependent information can be saved in BotStore, which can be accessed through session manager from dispatcher.

Three steps to develop a new channel for a third party like Messenger:
1. Figure out what do we need to provide through callback url, and parameters for these things. Also what do we need to have from a third party system. Ask PM to add UI component for this third party in setting/integration section on platform. Also write the readme on how one needs to configure on both sides to make this integration work. 
2. Implement the restful service to serve api per third party documentation, so that we can parse the messages sent from the third party system, and forward that to dispatcher for processing. We will use the quarkus rest easy framework. Use ngrok to develop api side.
3. Implement the restful client to forward the response to third party system. Again use quarkus reat easy client library.  Use portman to develop the client side.

The technology stacks that we rely on:
1. Kotlin: a new dialect of java.
2. Quarkus: a spring boot like rest framework, more mature for native deploy. 
3. Serialization should use jackson (warped in the io.opencui.serialization). 
4. Use third party SDK if we need to. (Twilio has its java SDK, which makes life a bit easier). 


We will add channel support for: 
1. google business messaging. 
2. RCS (may need multiple versions for different vendors). 
3. Businless Chat from Apple.
