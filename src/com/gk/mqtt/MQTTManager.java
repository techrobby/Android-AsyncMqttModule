package com.gk.mqtt;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class MQTTManager extends BroadcastReceiver
{

	private String MQTT_THREAD = "Mqtt_Thread";

	private String wakeLockTag = "MQTTWakeLock"; // Name of the MQTT Wake lock

	private String TAG = "MqttManager";

	private Context context;

	private WakeLock wakelock;

	private Handler mqttThreadHandler;

	private Looper mMqttHandlerLooper;
	
	private Messenger mMessenger; // this is used to interact with the mqtt thread

	private ConnectionCheckRunnable connChkRunnable;

	private ConnectivityManager cm;

	private MqttAsyncClient mqtt = null; // main object for handling mqtt connections

	private MqttConnectOptions op = null; // options to set when you connect to mqtt broker

	private MqttCallback mqttCallBack;

	private IMqttActionListener listernerConnect;

	private MQTTPersistence persistence = null;

	private int reconnectTime = 0;

	private volatile int retryCount = 0;

	/*
	 * this variable when true, does not allow mqtt operation such as publish or connect this will become true when you
	 * force close or force disconnect mqtt
	 */

	private boolean forceDisconnect = false;

	private boolean connectUsingSSL = false;

	private String uid = "uuid";

	private String password = "password";

	private String brokerHostName;

	private String clientId = "clientId";

	private String topic = "topic_1";

	private int brokerPortNumber = 1883;

	private volatile AtomicBoolean haveUnsentMessages = new AtomicBoolean(false);

	private static MQTTManager _instance = null;

	// this is used to check and connect mqtt and will be run on MQTT thread
	private class ConnectionCheckRunnable implements Runnable
	{
		private long sleepTime = 0;

		public void setSleepTime(long t)
		{
			sleepTime = t;
		}

		@Override
		public void run()
		{
			if (sleepTime > 0)
			{
				try
				{
					Thread.sleep(sleepTime);
				}
				catch (InterruptedException e)
				{
					Log.e(TAG, "Exception", e);
				}
				sleepTime = 0;
			}
			connect();
		}
	}

	class IncomingHandler extends Handler
	{
		public IncomingHandler(Looper looper)
		{
			super(looper);
		}

		@Override
		public void handleMessage(Message msg)
		{
			try
			{
				switch (msg.what)
				{
				case MQTTConstants.MSG_PUBLISH:
					Bundle bundle = msg.getData();
					String message = bundle.getString(MQTTConstants.MESSAGE);
					long msgId = bundle.getLong(MQTTConstants.MESSAGE_ID, -1);
					send(new MQTTPacket(message.getBytes(), msgId, System.currentTimeMillis()), msg.arg1);
					break;
				}
			}
			catch (Exception e)
			{
				Log.e(TAG, "Exception in handle message", e);
			}
		}
	}
	
	private MQTTManager()
	{
		persistence = MQTTPersistence.getInstance();
		connChkRunnable = new ConnectionCheckRunnable();
	}

	public static MQTTManager getInstance()
	{
		if (_instance == null)
		{
			synchronized (MQTTManager.class)
			{
				if (_instance == null)
					_instance = new MQTTManager();
			}
		}
		return _instance;
	}

	/*
	 * This method should be used after creating this object. Note : Functions involving 'this' reference and Threads
	 * should not be used or started in constructor as it might happen that incomplete 'this' object creation took place
	 * till that time.
	 */
	public void init(Context ctx)
	{
		context = ctx;
		cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		HandlerThread mqttHandlerThread = new HandlerThread(MQTT_THREAD);
		mqttHandlerThread.start();
		mMqttHandlerLooper = mqttHandlerThread.getLooper();
		mqttThreadHandler = new Handler(mMqttHandlerLooper);

		// register for Screen ON, Network Connection Change
		IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
		filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		context.registerReceiver(this, filter);
		
		mMessenger = new Messenger(new IncomingHandler(mMqttHandlerLooper));
	}

	public Messenger getMessenger()
	{
		return mMessenger;
	}
	
	private void acquireWakeLock()
	{
		if (wakelock == null)
		{
			PowerManager pm = (PowerManager) context.getSystemService(Service.POWER_SERVICE);
			wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag);
			wakelock.setReferenceCounted(false);
		}
		wakelock.acquire();
	}

	private void acquireWakeLock(int timeout)
	{
		if (timeout > 0)
		{
			if (wakelock == null)
			{
				PowerManager pm = (PowerManager) context.getSystemService(Service.POWER_SERVICE);
				wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag);
				wakelock.setReferenceCounted(false);
			}
			wakelock.acquire(timeout * 1000);
		}
		else
			acquireWakeLock();
		Log.d(TAG, "Wakelock Acquired");
	}

	private void releaseWakeLock()
	{
		if (wakelock != null && wakelock.isHeld())
		{
			wakelock.release();
			Log.d(TAG, "Wakelock Released");
		}
	}

	private void connectOnMqttThread()
	{
		mqttThreadHandler.postAtFrontOfQueue(connChkRunnable);
	}

	private void connectOnMqttThread(long t)
	{
		try
		{
			// make MQTT thread wait for t ms to attempt reconnect
			connChkRunnable.setSleepTime(t);
			mqttThreadHandler.postAtFrontOfQueue(connChkRunnable);
		}
		catch (Exception e)
		{
			Log.e(TAG, "Exception in MQTT connect handler", e);
		}
	}

	private boolean isNetworkAvailable()
	{
		if (context == null)
		{
			Log.e(TAG, "Context is null!!");
			return false;
		}
		return (cm != null && cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isAvailable() && cm.getActiveNetworkInfo().isConnected());
	}

	private boolean isConnected()
	{
		if (mqtt == null)
			return false;
		return mqtt.isConnected();
	}

	private void scheduleNextConnectionCheck()
	{
		scheduleNextConnectionCheck(MQTTConstants.MAX_RECONNECT_TIME);
	}

	private void scheduleNextConnectionCheck(int reconnectTime)
	{
		try
		{
			mqttThreadHandler.removeCallbacks(connChkRunnable);
			mqttThreadHandler.postDelayed(connChkRunnable, reconnectTime * 1000);
		}
		catch (Exception e)
		{
			Log.e(TAG, "Exception while scheduling connection check runnable", e);
		}
	}

	// this should and will run only on MQTT thread so no need to synchronize it explicitly
	private void connect()
	{
		try
		{
			if (!isNetworkAvailable())
			{
				Log.d(TAG, "No Network Connection so should not connect");
				return;
			}

			// if force disconnect is in progress don't connect
			if (forceDisconnect)
				return;

			if (op == null)
			{
				op = new MqttConnectOptions();
				op.setUserName(uid);
				op.setPassword(password.toCharArray());
				op.setCleanSession(true);
				op.setKeepAliveInterval((short) MQTTConstants.KEEP_ALIVE_SECONDS);
				op.setConnectionTimeout(MQTTConstants.CONNECTION_TIMEOUT_SECONDS);
				if (connectUsingSSL)
					op.setSocketFactory(Utils.getSSLSocketFactory());
			}

			if (mqtt == null)
			{
				String protocol = connectUsingSSL ? "ssl://" : "tcp://";
				mqtt = new MqttAsyncClient(protocol + brokerHostName + ":" + brokerPortNumber, clientId, null);
				mqtt.setCallback(getMqttCallback());
			}

			if (isConnected()) // if mqtt is already connected, dont do anything
				return;

			// if any network is available, then only connect, else connect at next check or when network gets available
			if (isNetworkAvailable())
			{
				acquireWakeLock(MQTTConstants.CONNECTION_TIMEOUT_SECONDS);
				IMqttToken token = mqtt.connect(op, null, getConnectListener());
				/* Wait till mqtt gets connected as before connect nothing should be done */
				token.waitForCompletion(MQTTConstants.CONNECTION_TIMEOUT_SECONDS);
			}
		}
		catch (MqttSecurityException e)
		{
			Log.e(TAG, "Connect exception", e);
			handleMqttException(e, false);
			releaseWakeLock();
		}
		catch (MqttException e)
		{
			Log.e(TAG, "Connect exception", e);
			handleMqttException(e, true);
			releaseWakeLock();
		}
		catch (Exception e) // this exception should not be thrown on connect
		{
			Log.e(TAG, "Connect exception", e);
			scheduleNextConnectionCheck();
			releaseWakeLock();
		}
	}

	private void handleMqttException(MqttException e, boolean reConnect)
	{
		switch (e.getReasonCode())
		{
			case MqttException.REASON_CODE_BROKER_UNAVAILABLE:
				Log.e(TAG, "Server Unavailable, try reconnecting later");
				Random random = new Random();
				int reconnectIn = random.nextInt(MQTTConstants.SERVER_UNAVAILABLE_MAX_CONNECT_TIME) + 1;
				scheduleNextConnectionCheck(reconnectIn * 60); // Converting minutes to seconds
				break;
			case MqttException.REASON_CODE_CLIENT_ALREADY_DISCONNECTED:
				Log.e(TAG, "Client already disconnected.");
				if (reConnect)
					connectOnMqttThread();
				break;
			case MqttException.REASON_CODE_CLIENT_CLOSED:
				// this will happen only when you close the conn, so dont do any thing
			case MqttException.REASON_CODE_CLIENT_CONNECTED:
				/*
				 * Thrown when an attempt to call MqttClient.disconnect() has been made from within a method on
				 * MqttCallback.
				 */
			case MqttException.REASON_CODE_CLIENT_DISCONNECT_PROHIBITED:
			case MqttException.REASON_CODE_CONNECT_IN_PROGRESS:
			case MqttException.REASON_CODE_SOCKET_FACTORY_MISMATCH:
			case MqttException.REASON_CODE_SSL_CONFIG_ERROR:
			case MqttException.REASON_CODE_TOKEN_INUSE:
			case MqttException.REASON_CODE_FAILED_AUTHENTICATION:
			case MqttException.REASON_CODE_INVALID_CLIENT_ID:
			case MqttException.REASON_CODE_INVALID_MESSAGE:
			case MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION:
			case MqttException.REASON_CODE_MAX_INFLIGHT:
			case MqttException.REASON_CODE_NO_MESSAGE_IDS_AVAILABLE:
			case MqttException.REASON_CODE_NOT_AUTHORIZED:
				break;

			case MqttException.REASON_CODE_CLIENT_DISCONNECTING:
				if (reConnect)
					scheduleNextConnectionCheck(1); // try reconnect after 1 sec, so that disconnect happens properly
				break;
			case MqttException.REASON_CODE_CLIENT_EXCEPTION:
				Log.e(TAG, "Exception : " + e.getCause().getMessage());
				if (e.getCause() instanceof UnknownHostException)
				{
					Log.e(TAG, "DNS Failure , Connect using ips");

					// HANDLE APPROPRIATELY
				}
				// Till this point disconnect has already happened due to exception (This is as per lib)
				else if (reConnect)
					connectOnMqttThread();
				break;
			case MqttException.REASON_CODE_CLIENT_NOT_CONNECTED:
				Log.e(TAG, "Client not connected retry connection");
				if (reConnect)
					connectOnMqttThread();
				break;
			case MqttException.REASON_CODE_CLIENT_TIMEOUT:
				// Till this point disconnect has already happened. This could happen in PING or other TIMEOUT happen
				// such as CONNECT, DISCONNECT
				if (reConnect)
					connectOnMqttThread();
				break;
			case MqttException.REASON_CODE_CONNECTION_LOST:
				Log.e(TAG, "Client not connected retry connection");
				if (reConnect)
					scheduleNextConnectionCheck(getConnRetryTime()); // since we can get this exception many times due
																		// to server exception or during deployment so
																		// we dont retry frequently instead with backoff
				break;
			case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
				scheduleNextConnectionCheck(getConnRetryTime());
				break;
			case MqttException.REASON_CODE_UNEXPECTED_ERROR:
				// This could happen while reading or writing error on a socket, hence disconnection happens, connect
				// immediately
				connectOnMqttThread();
				break;
			default:
				// connect after sometime as unknown exception happened
				connectOnMqttThread(getConnRetryTime());
				break;
		}
		Log.e(TAG, "Exception", e);
	}

	private int getConnRetryTime()
	{
		return getConnRetryTime(false);
	}

	// this function works on exponential retrying
	private int getConnRetryTime(boolean forceExp)
	{
		if ((reconnectTime == 0 || retryCount < MQTTConstants.MAX_RETRY_COUNT) && !forceExp)
		{
			Random random = new Random();
			reconnectTime = random.nextInt(MQTTConstants.RECONNECT_TIME) + 1;
			retryCount++;
		}
		else
		{
			reconnectTime *= 2;
		}
		// if reconnectTime is 0, select the random value. This will happen in case of forceExp = true
		reconnectTime = reconnectTime > MQTTConstants.MAX_RECONNECT_TIME ? MQTTConstants.MAX_RECONNECT_TIME : (reconnectTime == 0 ? (new Random())
				.nextInt(MQTTConstants.RECONNECT_TIME) + 1 : reconnectTime);
		return reconnectTime;
	}

	/* This call back will be called when message is arrived */
	private MqttCallback getMqttCallback()
	{
		if (mqttCallBack == null)
		{
			mqttCallBack = new MqttCallback()
			{
				@Override
				public void messageArrived(String arg0, MqttMessage arg1) throws Exception
				{
					try
					{
						byte[] bytes = arg1.getPayload();
						// handle the data
					}
					catch (Exception e)
					{
						Log.e(TAG, "Exception when msg arrived : ", e);
					}
				}

				@Override
				public void deliveryComplete(IMqttDeliveryToken arg0)
				{
					// nothing needs to be done here as success will get called eventually
				}

				@Override
				public void connectionLost(Throwable arg0)
				{
					Log.w(TAG, "Connection Lost : " + arg0.getMessage());
					connectOnMqttThread();
				}
			};
		}
		return mqttCallBack;
	}

	private final class RetryFailedMessages implements Runnable
	{
		public void run()
		{
			// get the list from where ever messages are persisted
			final List<MQTTPacket> packets = persistence.getAllSentMessages();
			Log.w(TAG, "Retrying to send " + packets.size() + " messages");
			for (MQTTPacket msg : packets)
			{
				send(msg, 1);
			}
			haveUnsentMessages.set(false);
		}
	}

	// this should always run on MQTT Thread
	private void send(MQTTPacket packet, int qos)
	{
		/* only care about failures for messages we care about. */
		if (qos > 0)
		{
			try
			{
				persistence.addSentMessage(packet);
			}
			catch (Exception e)
			{
				Log.e(TAG, "Unable to persist message", e);
			}
		}

		// if force disconnect is in progress dont allow mqtt operations to take place
		if (forceDisconnect)
			return;

		if (!isConnected())
		{
			connect();
			return;
		}

		try
		{
			mqtt.publish(topic, packet.getMessage(), qos, false, packet, new IMqttActionListener()
			{
				@Override
				public void onSuccess(IMqttToken arg0)
				{
					try
					{
						MQTTPacket packet = (MQTTPacket) arg0.getUserContext();
						if (packet != null)
						{
							persistence.removeMessageForPacketId(packet.getPacketId());
							if (packet.getMsgId() > 0)
							{
								Long msgId = packet.getMsgId();
								Log.d(TAG, "Recieved S status for msg with id : " + msgId);
							}
						}
						if (haveUnsentMessages.get())
						{
							mqttThreadHandler.postAtFrontOfQueue(new RetryFailedMessages());
						}
					}
					catch (Exception e)
					{
						Log.e(TAG, "Exception in publish success", e);
						e.printStackTrace();
					}
				}

				@Override
				public void onFailure(IMqttToken arg0, Throwable arg1)
				{
					Log.e(TAG, "Message delivery failed for : " + arg0.getMessageId() + ", exception : " + arg1.getMessage());
					haveUnsentMessages.set(true);
					connectOnMqttThread();
				}
			});
		}
		catch (org.eclipse.paho.client.mqttv3.MqttPersistenceException e)
		{
			Log.e(TAG, "Exception", e);
			haveUnsentMessages.set(true);
		}
		catch (MqttException e)
		{
			haveUnsentMessages.set(true);
			handleMqttException(e, true);
		}
		catch (Exception e)
		{
			// this might happen if mqtt object becomes null while disconnect, so just ignore
		}
	}

	/* Listeners for connection */
	private IMqttActionListener getConnectListener()
	{
		if (listernerConnect == null)
		{
			listernerConnect = new IMqttActionListener()
			{
				@Override
				public void onSuccess(IMqttToken arg0)
				{
					retryCount = 0;
					reconnectTime = 0;
					// resetting the reconnect timer to 0 as it would have been changed in failure
					Log.d(TAG, "Client Connected ....");
					try
					{
						mqtt.subscribe("sub-topic", 1, null, new IMqttActionListener()
						{

							@Override
							public void onSuccess(IMqttToken arg0)
							{
								// handle subscribe success
							}

							@Override
							public void onFailure(IMqttToken arg0, Throwable arg1)
							{
								// handle subscribe failed
							}
						});
					}
					catch (MqttException e)
					{
						Log.e(TAG, "Exception", e);
					}
					// retry sending the unsent messages
					mqttThreadHandler.postAtFrontOfQueue(new RetryFailedMessages());
					releaseWakeLock();
				}

				@Override
				public void onFailure(IMqttToken arg0, Throwable value)
				{
					try
					{
						MqttException exception = (MqttException) value;
						handleMqttException(exception, true);
					}
					catch (Exception e)
					{
						Log.e(TAG, "Exception in connect failure callback", e);
					}
					finally
					{
						releaseWakeLock();
					}
				}
			};
		}
		return listernerConnect;
	}

	/**
	 * The caller should handle the disconnect using returned future (IMqttToken)
	 * 
	 * @param reconnect
	 * @return IMqttToken
	 */
	public IMqttToken disconnect(final boolean reconnect)
	{
		IMqttToken t = null;
		try
		{
			if (mqtt != null)
			{
				forceDisconnect = true;
				t = mqtt.disconnect(MQTTConstants.QUIESCE_TIME, null, new IMqttActionListener()
				{
					@Override
					public void onSuccess(IMqttToken arg0)
					{
						Log.d(TAG, "Explicit Disconnection success");
						handleDisconnect(reconnect);
					}

					@Override
					public void onFailure(IMqttToken arg0, Throwable arg1)
					{
						Log.e(TAG, "Explicit Disconnection failed", arg1);
						// dont care about failure and move on as you have to connect anyways
						handleDisconnect(reconnect);
					}
				});
			}
		}
		catch (MqttException e)
		{
			// we dont need to handle MQTT exception here as we reconnect depends on reconnect var
			e.printStackTrace();
			handleDisconnect(reconnect);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			handleDisconnect(reconnect);
		}
		return t;
	}

	private void handleDisconnect(boolean reconnect)
	{
		forceDisconnect = false;
		try
		{
			mqtt.close();
		}
		catch (Exception e)
		{
			Log.e(TAG, "Exception", e);
		}

		mqtt = null;
		op = null;
		if (reconnect)
			connectOnMqttThread();
		else
		{
			try
			{
				// if you dont want to reconnect simply remove all connection check runnables
				mqttThreadHandler.removeCallbacks(connChkRunnable);
			}
			catch (Exception e)
			{
				Log.e(TAG, "Exception", e);
			}
		}
	}

	@Override
	public void onReceive(Context context, Intent intent)
	{
		String action = intent.getAction();
		if (Intent.ACTION_SCREEN_ON.equals(action) || ConnectivityManager.CONNECTIVITY_ACTION.equals(action))
		{
			boolean isNetwork = isNetworkAvailable();
			Log.d(TAG, "Network change event happened. Network connected : " + isNetwork);
			if (isNetwork)
				connectOnMqttThread();
		}
	}

}
