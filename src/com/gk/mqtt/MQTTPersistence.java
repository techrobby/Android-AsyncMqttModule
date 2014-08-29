package com.gk.mqtt;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils.InsertHelper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class MQTTPersistence extends SQLiteOpenHelper
{
	private static final String TAG = "MQTTPersistence";

	public static final String MQTT_DATABASE_NAME = "mqttpersistence";

	public static final int MQTT_DATABASE_VERSION = 1;

	public static final String MQTT_DATABASE_TABLE = "messages";

	public static final String MQTT_MESSAGE_ID = "msgId";

	public static final String MQTT_PACKET_ID = "mqttId";

	public static final String MQTT_MESSAGE = "data";

	public static final String MQTT_MSG_ID_INDEX = "mqttMsgIdIndex";

	public static final String MQTT_TIME_STAMP = "mqttTimeStamp";

	public static final String MQTT_TIME_STAMP_INDEX = "mqttTimeStampIndex";

	private SQLiteDatabase mDb;

	private static MQTTPersistence mqttPersistence;

	public static MQTTPersistence getInstance(Context context)
	{
		if (mqttPersistence == null)
		{
			synchronized (MQTTPersistence.class)
			{
				if (mqttPersistence == null)
				{
					mqttPersistence = new MQTTPersistence(context);
				}
			}
		}
		return mqttPersistence;
	}

	private MQTTPersistence(Context context)
	{
		super(context, MQTT_DATABASE_NAME, null, MQTT_DATABASE_VERSION);
		mDb = getWritableDatabase();
	}

	public void addSentMessage(MQTTPacket packet) throws MQTTPersistenceException
	{
		InsertHelper ih = null;
		try
		{
			Log.d("MqttPersistence", "Persisting message data: " + new String(packet.getMessage()));
			ih = new InsertHelper(mDb, MQTT_DATABASE_TABLE);
			ih.prepareForReplace();
			ih.bind(ih.getColumnIndex(MQTT_MESSAGE), packet.getMessage());
			ih.bind(ih.getColumnIndex(MQTT_MESSAGE_ID), packet.getMsgId());
			ih.bind(ih.getColumnIndex(MQTT_TIME_STAMP), packet.getTimeStamp());
			long rowid = ih.execute();
			if (rowid < 0)
			{
				throw new MQTTPersistenceException("Unable to persist message");
			}
			packet.setPacketId(rowid);
		}
		finally
		{
			if (ih != null)
			{
				ih.close();
			}
		}
	}

	@Override
	public void close()
	{
		mDb.close();
	}

	public List<MQTTPacket> getAllSentMessages()
	{
		Cursor c = mDb.query(MQTT_DATABASE_TABLE, new String[] { MQTT_MESSAGE, MQTT_MESSAGE_ID, MQTT_TIME_STAMP, MQTT_PACKET_ID }, null, null, null, null, MQTT_TIME_STAMP);
		try
		{
			List<MQTTPacket> vals = new ArrayList<MQTTPacket>(c.getCount());
			int dataIdx = c.getColumnIndex(MQTT_MESSAGE);
			int idIdx = c.getColumnIndex(MQTT_MESSAGE_ID);
			int tsIdx = c.getColumnIndex(MQTT_TIME_STAMP);
			int packetIdIdx = c.getColumnIndex(MQTT_PACKET_ID);

			while (c.moveToNext())
			{
				MQTTPacket packet = new MQTTPacket(c.getBlob(dataIdx), c.getLong(idIdx), c.getLong(tsIdx), c.getLong(packetIdIdx));
				vals.add(packet);
			}

			return vals;
		}
		finally
		{
			c.close();
		}
	}

	public boolean isMessageSent(long mqttMsgId)
	{
		Cursor c = mDb.query(MQTT_DATABASE_TABLE, new String[] { MQTT_MESSAGE_ID }, MQTT_MESSAGE_ID + "=?", new String[] { Long.toString(mqttMsgId) }, null, null, null);
		try
		{
			int count = c.getCount();
			return (count == 0);
		}
		finally
		{
			c.close();
		}
	}

	@Override
	public void onCreate(SQLiteDatabase db)
	{
		if (db == null)
		{
			db = mDb;
		}

		String sql = "CREATE TABLE IF NOT EXISTS " + MQTT_DATABASE_TABLE + " ( " + MQTT_PACKET_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," + MQTT_MESSAGE_ID + " INTEGER,"
				+ MQTT_MESSAGE + " BLOB," + MQTT_TIME_STAMP + " INTEGER" + " ) ";
		db.execSQL(sql);

		sql = "CREATE INDEX IF NOT EXISTS " + MQTT_MSG_ID_INDEX + " ON " + MQTT_DATABASE_TABLE + "(" + MQTT_MESSAGE_ID + ")";
		db.execSQL(sql);

		sql = "CREATE INDEX IF NOT EXISTS " + MQTT_TIME_STAMP_INDEX + " ON " + MQTT_DATABASE_TABLE + "(" + MQTT_TIME_STAMP + ")";
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
	{
		// do nothing
	}

	public void removeMessage(long msgId)
	{
		String[] bindArgs = new String[] { Long.toString(msgId) };
		int numRows = mDb.delete(MQTT_DATABASE_TABLE, MQTT_MESSAGE_ID + "=?", bindArgs);
		Log.d(TAG, "Removed " + numRows + " Rows from " + MQTT_DATABASE_TABLE + " with Msg ID: " + msgId);
	}

	public void removeMessageForPacketId(long packetId)
	{
		String[] bindArgs = new String[] { Long.toString(packetId) };
		int numRows = mDb.delete(MQTT_DATABASE_TABLE, MQTT_PACKET_ID + "=?", bindArgs);
		Log.d(TAG, "Removed " + numRows + " Rows from " + MQTT_DATABASE_TABLE + " with Packet ID: " + packetId);
	}
}
