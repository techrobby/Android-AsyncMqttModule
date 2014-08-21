package com.gk.mqtt;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class Utils
{

	public static String algorithmName = "TLSv1";

	private static String validServerName = "server.in"; // set server name here

	// Create a trust manager that does not validate certificate chains
	public static TrustManager[] trustServerCerts = new TrustManager[] { new X509TrustManager()
	{

		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException
		{
		}

		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException
		{

			String cn_recieved = getCN(chain[0].getSubjectDN().getName());
			if (cn_recieved == null || !cn_recieved.endsWith(validServerName))
			{
				throw new CertificateException("Not a valid certificate.");
			}

		}

		public X509Certificate[] getAcceptedIssuers()
		{
			return null;
		}

	} };

	private static String getCN(String dn)
	{
		int i = 0;
		i = dn.indexOf("CN=");
		if (i == -1)
		{
			return null;
		}

		// get the remaining DN without CN=
		dn = dn.substring(i + 3);
		char[] dncs = dn.toCharArray();
		for (i = 0; i < dncs.length; i++)
		{
			if (dncs[i] == ',' && i > 0 && dncs[i - 1] != '\\')
			{
				break;
			}
		}
		return dn.substring(0, i);
	}

	public static SSLSocketFactory getSSLSocketFactory()
	{
		try
		{
			SSLContext sc = SSLContext.getInstance(algorithmName);
			sc.init(null, trustServerCerts, new java.security.SecureRandom());
			return sc.getSocketFactory();
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
