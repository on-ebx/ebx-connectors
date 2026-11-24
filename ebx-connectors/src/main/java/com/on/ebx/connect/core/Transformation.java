package com.on.ebx.connect.core;

import java.util.List;

/**
 * One step of a transformation pipeline ({@code trim|upper|left(2)}). Receives the current value
 * (String, BigDecimal, Boolean, List or null) and returns the new one; null means "no value".
 */
public interface Transformation
{
	Object apply(Object pValue, List<String> pArgs, TransformationContext pContext) throws Exception;
}
