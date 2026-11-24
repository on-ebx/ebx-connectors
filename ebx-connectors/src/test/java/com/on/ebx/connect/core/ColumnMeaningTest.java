package com.on.ebx.connect.core;

import java.util.Arrays;

import com.on.ebx.connect.spi.TablePublisher.ColumnContext;

/** Offline: how the model's text, the catalog entry and the glossary term make a column's meaning. */
public final class ColumnMeaningTest
{
	public static void main(final String[] pArgs)
	{
		// the catalog's business name and description win over the model's label and description
		ColumnContext c = ColumnMeaning.merge("Name", "Model text", "Customer name", "Steward text", "CUSTOMER_NAME", "Customer name", "Definition.",
			Arrays.asList("Client name"), "CONFIDENTIAL", Arrays.asList("PERSON_NAME"));
		ColumnMeaningTest.check("Customer name".equals(c.getBusinessName()) && "Steward text".equals(c.getDescription()), "catalog wins");
		ColumnMeaningTest.check("CUSTOMER_NAME".equals(c.getTermCode()) && "Definition.".equals(c.getDefinition()) && c.getSynonyms().size() == 1, "term carried");
		// no catalog entry: the model's label and description
		c = ColumnMeaning.merge("Last name", "Family name of the person.", null, null, null, null, null, null, null, null);
		ColumnMeaningTest.check("Last name".equals(c.getBusinessName()) && "Family name of the person.".equals(c.getDescription()) && c.getTermCode().isEmpty(), "model only");
		// no business name anywhere: the glossary term's label names the column
		c = ColumnMeaning.merge(null, null, " ", null, "IBAN", "IBAN", "ISO 13616...", null, "RESTRICTED", null);
		ColumnMeaningTest.check("IBAN".equals(c.getBusinessName()), "term label as name: " + c.getBusinessName());
		// nothing known at all
		ColumnMeaningTest.check(ColumnMeaning.merge(null, null, null, null, null, null, null, null, null, null).isEmpty(), "empty");
		// how the catalog names an EBX table's columns
		ColumnMeaningTest.check("Identification/Id".equals(ColumnMeaning.fullPath("./Identification/Id")), "./ dropped");
		ColumnMeaningTest.check("addresses".equals(ColumnMeaning.fullPath("/addresses[]")), "/ and [] dropped");
		System.out.println("PASS: column meaning from model, catalog and glossary");
	}

	static void check(final boolean pOk, final String pMessage)
	{
		if (!pOk) throw new AssertionError(pMessage);
	}
}
