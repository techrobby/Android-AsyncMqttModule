package com.gk.mqtt;

public class MQTTConstants
{
	/* Time in minutes. Should not be a small value as server */
	public static final int SERVER_UNAVAILABLE_MAX_CONNECT_TIME = 10;

	public static final short KEEP_ALIVE_SECONDS = 120; // this is the time for which conn will remain open w/o messages

	public static final short CONNECTION_TIMEOUT_SECONDS = 60;

	public static final int MAX_RECONNECT_TIME = 120; /* the max amount (in seconds) the reconnect time can be */

	public static final int MAX_RETRY_COUNT = 20;

	/* how often to ping after a failure */
	public static final int RECONNECT_TIME = 10; /* 10 seconds */

	/*
	 * When disconnecting (forcibly) it might happen that some messages are waiting for acks or delivery. So before
	 * disconnecting, wait for this time to let mqtt finish the work and then disconnect w/o letting more msgs to come
	 * in.
	 */
	public static final short QUIESCE_TIME = 500;

}
