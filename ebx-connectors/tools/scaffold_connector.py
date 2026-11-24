#!/usr/bin/env python3
"""Create an intentionally unregistered, review-ready connector skeleton."""

import argparse
import re
import sys
from pathlib import Path


CATEGORIES = (
    "AI_ML", "BUSINESS_APPS", "CLOUD_FILE_STORAGE", "DATA_PLATFORMS",
    "DATABASE", "MESSAGING_EVENTS", "LOCATION_REFERENCE_DATA",
    "CORPORATE_REGISTRIES", "RISK_COMPLIANCE", "GENERIC_INTEGRATION",
)


def java_class(value):
    words = re.findall(r"[A-Za-z0-9]+", value)
    result = "".join(word[:1].upper() + word[1:] for word in words)
    if not result or not re.match(r"[A-Za-z_]", result):
        raise ValueError("name must contain a Java identifier")
    return result + ("Connector" if not result.endswith("Connector") else "")


def validate(args):
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", args.type):
        raise ValueError("type must be lower-case kebab-case")
    if not args.label.strip() or len(args.label) > 120:
        raise ValueError("label must contain 1 to 120 characters")
    if args.category not in CATEGORIES:
        raise ValueError("unsupported category")
    if args.logo_name and not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]*", args.logo_name):
        raise ValueError("logo-name must be a safe file stem")


def render(args):
    cls = java_class(args.name or args.label)
    test_cls = cls + "DescriptorTest"
    logo = args.logo_name or cls[:-9]
    guide = args.type.upper() + "-USER-GUIDE.html"
    values = {"type": args.type, "label": args.label.strip(), "category": args.category,
              "cls": cls, "test_cls": test_cls, "logo": logo, "guide": guide}
    class_text = """package com.on.ebx.connect.connectors;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorAvailability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;

/** Scaffold only. Complete the evidence checklist before registering this connector. */
public final class {cls} implements Connector
{{
    private ConnectorConfig config;

    public String getType() {{ return "{type}"; }}
    public String getLabel() {{ return "{label}"; }}
    public List<Operation> getOperations() {{ return Collections.emptyList(); }}
    public void configure(final ConnectorConfig pConfig) {{ this.config = pConfig; }}

    public ConnectorDescriptor getDescriptor()
    {{
        return ConnectorDescriptor.builder(getType(), getLabel(), "{category}")
            .description("TODO: evidence-backed connector description")
            .availability(ConnectorAvailability.UNAVAILABLE,
                "Scaffold only; implementation and acceptance evidence are incomplete")
            .build();
    }}

    public JsonNode execute(final ConnectorContext pContext, final String pOperation,
        final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
    {{
        throw new ConnectorException("{label} is an unregistered scaffold and cannot execute");
    }}
}}
""".format(**values)
    test_text = """package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.ConnectorAvailability;
import com.on.ebx.connect.spi.ConnectorDescriptor;

/** Descriptor safety contract for the generated scaffold. */
public final class {test_cls}
{{
    private {test_cls}() {{ }}

    public static void main(final String[] pArgs)
    {{
        final {cls} connector = new {cls}();
        final ConnectorDescriptor descriptor = connector.getDescriptor();
        check("{type}".equals(descriptor.getType()), "type");
        check("{label}".equals(descriptor.getLabel()), "label");
        check("{category}".equals(descriptor.getCategory()), "category");
        check(descriptor.getAvailability() == ConnectorAvailability.UNAVAILABLE, "availability");
        check(connector.getOperations().isEmpty(), "operations must remain empty until implemented");
        System.out.println("PASS: {label} scaffold remains unavailable and non-executable");
    }}

    private static void check(final boolean pCondition, final String pMessage)
    {{
        if (!pCondition) throw new AssertionError(pMessage);
    }}
}}
""".format(**values)
    profile = """# Generated staging profile; this file is not loaded automatically.
type={type}
label={label}
category={category}
class=com.on.ebx.connect.connectors.{cls}
logo={logo}.svg
guide=docs/{guide}
availability=UNAVAILABLE
registration=pending-review
""".format(**values)
    fixture = """# Acceptance fixtures for {label}

Store only synthetic, redacted protocol fixtures here. Never commit credentials, tokens,
customer records, or proprietary payloads. Add a README entry for every fixture explaining
its source contract, redactions, expected operation, and bounds.
""".format(**values)
    svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 160" role="img" aria-labelledby="title desc">
  <title id="title">{label} placeholder</title>
  <desc id="desc">Neutral placeholder; replace only with an approved, licensed mark.</desc>
  <rect width="160" height="160" rx="24" fill="#667085"/>
  <circle cx="80" cy="68" r="34" fill="none" stroke="#fff" stroke-width="8"/>
  <path d="M80 49v25m0 17v2" stroke="#fff" stroke-width="9" stroke-linecap="round"/>
  <text x="80" y="134" text-anchor="middle" font-family="Arial, sans-serif" font-size="15" font-weight="700" fill="#fff">PENDING</text>
</svg>
""".format(**values)
    guide_text = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{label} connector guide (scaffold)</title><link rel="stylesheet" href="connector-guide.css"></head>
<body><main><p><a href="USER-GUIDE.html">&larr; EBX Connectors user guide</a></p>
<h1>{label}</h1><aside><strong>Scaffold status:</strong> not registered, not executable, and not production-ready.</aside>
<h2>Purpose and supported boundary</h2><p>TODO: document only capabilities supported by primary evidence and automated tests.</p>
<h2>Prerequisites and least privilege</h2><p>TODO: endpoint, supported version, authentication, permissions, TLS, and dependencies.</p>
<h2>Configure in the Data Connector perspective</h2><p>TODO: connector record, credentials, safe connection test, and mapping setup.</p>
<h2>Operations, limits, and recovery</h2><p>TODO: inputs, outputs, pagination, explicit bounds, idempotency, monitoring, and failure recovery.</p>
<h2>Acceptance evidence</h2><p>TODO: fixture tests, live acceptance environment, evidence date, and known exclusions.</p>
</main></body></html>
""".format(**values)
    return {
        "src/main/java/com/on/ebx/connect/connectors/%s.java" % cls: class_text,
        "src/test/java/com/on/ebx/connect/connectors/%s.java" % test_cls: test_text,
        "src/main/resources/com/on/ebx/connect/scaffolds/%s.properties" % args.type: profile,
        "src/test/resources/connectors/%s/README.md" % args.type: fixture,
        "src/main/webapp/www/common/images/connect/logos/%s.svg" % logo: svg,
        "docs/%s" % guide: guide_text,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--type", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--category", required=True, choices=CATEGORIES)
    parser.add_argument("--name", help="Java class base name (defaults to label)")
    parser.add_argument("--logo-name", help="safe SVG file stem")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--check", action="store_true", help="print files without writing")
    args = parser.parse_args(argv)
    try:
        validate(args)
        files = render(args)
        existing = [str(path) for rel in files for path in [args.root / rel] if path.exists()]
        if existing:
            raise ValueError("refusing to overwrite existing files:\n  " + "\n  ".join(existing))
        for rel in sorted(files):
            print(rel)
        if not args.check:
            for rel, content in files.items():
                path = args.root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("w", encoding="utf-8", newline="\n") as stream:
                    stream.write(content)
        return 0
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    sys.exit(main())
