package com.on.ebx.connect.spi;

/**
 * Value type of a connector configuration property.
 *
 * <p>The type is UI-neutral. An EBX form, REST client, or other consumer can map it to its own
 * widget and validation rules without introducing an EBX dependency into the connector SPI.</p>
 */
public enum ConfigurationPropertyType
{
	STRING,
	SECRET,
	BOOLEAN,
	INTEGER,
	URL,
	ENUMERATION,
	CERTIFICATE,
	RESOURCE,
	FILE,
	JSON
}
