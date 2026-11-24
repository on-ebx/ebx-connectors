package com.on.ebx.connect.spi;

/** Authentication schemes a connector type can accept. */
public enum AuthenticationMethod
{
	NONE,
	BASIC,
	API_KEY_HEADER,
	BEARER_TOKEN,
	OAUTH2_CLIENT_CREDENTIALS,
	OAUTH2_AUTHORIZATION_CODE,
	OAUTH2_REFRESH_TOKEN,
	CERTIFICATE,
	SSH_KEY,
	SERVICE_ACCOUNT,
	CUSTOM
}
