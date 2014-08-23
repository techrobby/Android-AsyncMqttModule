ASYNCHRONOUS MQTT MODULE/MANAGER FOR ANDROID
=======================================================================

AsyncMqttModule is a wrapper over Paho Mqtt library to connect and perform other operations on the MQTT brokers. The module is custom made for the Android environment. Following are the main highlights of the project.

1. All the operations are asynchronous.
2. Ordering of messages is maintained.
3. Message Persistence is handled using SqliteDb.
4. It overcomes some of the limitations in the PAHO library (Mentioned below in next section)
5. Checks for connectivity and auto reconnects.
6. All the operations are happening on the background (MQTT_Thread) Thread.
7. Supports SSL.

HOW STUFF WORKS
-------------------

MqttManager is a singleton class which initialize the objects and other stuff through its init function. 
A new thread (MQTT Thread) is started and attached to a queue using Android Handler. All the mqtt operations are done on this thread except explicit disconnect. Mqtt Paho library provides the capability of asynchronous operations, but in order to have the messages inorder we need a queue. Once MQTT is connected a connection check runnable is scheduled to check if connection is still there or not. 
Note: Paho library pings the MQTT broker periodically but we cannot just rely on this only, hence, explicit connection check mechanism is implemented. One can disable it if required.
In order to save battery, no alarm manager is used, also wakelock is acquired for a particular amount of time during connect call only. Few system level callbacks are used which notifies the module about SCREEN LOCK/UNLOCK and NETWORK CONNECTIVITY CHANGE, so that connection to the broker can be made instantly after network availability. All the MQTTExceptions are handled according to the type of exception (see code). In many cases reconnect attempt is not made immediately as there are cases when server is unavailable and trying to connect continuously will lead to battery drainage, hence exponential retry is imposed. 
All the messages will be stored in the MqttPersistence database before being published and once an ack is received they are removed from the DB. Only QOS 1 or 2 messages will be persisted. There is a problem in Paho library that after you publish a message if there is any exception or disconnection before message persistence, the message will be lost as it will not get inserted into PAHO db just after publish (refer paho source code), hence, MQTTPersistence is used so that even before mqtt publish message gets inserted in db and once you get an ack it is removed.

USAGE
-------------------

Get the instance of MQTTManager and call its init function. When you want to connect to the broker, call connectOnMqttThread() function and you are done. Once mqtt is connected, onSuccess of IMqttActionListener is called. Here you should ideally subscribe to topics. When a new message is received messageArrived callback will be called (refer paho documentation). To disconnect call the disconnect method which will return IMqttToken object which is a Future object using which state of operation can be tracked. In order to send the message on the MQTT_Thread use the messenger object returned by getMessenger() function. 
Example to send message : 
				Message msg = Message.obtain();
				msg.what = 1; 
				String data = "Hello world";
				Bundle bundle = new Bundle();
				bundle.putString(MQTTConstants.MESSAGE, data);
				msg.setData(bundle);
				msg.replyTo = mMessenger;
				MqttManager.getInstance().getMessenger().send(msg);

Message will be persisted to a databse maintained by the MqttManager only.

For any info or enhancements, mail me at "kanwar.iitd@gmail.com"
