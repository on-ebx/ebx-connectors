#!/usr/bin/env python3
"""Static security contract for the EBX connector diagnostics service."""

from pathlib import Path

MODULE = Path(__file__).resolve().parents[2]
UI = MODULE / "main/java/com/on/ebx/connect/ebx/ui"
DECLARATION = (UI / "ConnectorDiagnosticsServiceDeclaration.java").read_text()
SERVICE = (UI / "ConnectorDiagnosticsUserService.java").read_text()
ADAPTER = (UI / "ConnectorDiagnosticsAdapter.java").read_text()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


require("setPermissionRule" in DECLARATION,
        "diagnostics declaration must install an EBX service permission rule")
require("getAdaptationAccessPermission" in SERVICE and ".isHidden()" in SERVICE,
        "diagnostics must honor occurrence-level EBX read permissions")
require(SERVICE.count("isReadable(") >= 3,
        "read permission must be checked at declaration, setup and event execution boundaries")
require("setLeftButtons(validate, test, connect, preview, disconnect)" in SERVICE and "if (this.readable)" in SERVICE,
        "diagnostic actions must not be rendered for an unreadable record")
require("ConnectorDiagnosticsAdapter.denied(" in SERVICE,
        "event execution must fail closed if record visibility changes after launch")

for forbidden in ("ProcedureContext", "ValueContextForUpdate", "doModifyContent", "doDelete", "executeOperation"):
    require(forbidden not in ADAPTER and forbidden not in SERVICE,
            "diagnostics must remain read-only; forbidden API found: " + forbidden)

require("DiagnosticsLaunch.proven(" in SERVICE and SERVICE.index("DiagnosticsLaunch.proven(") < SERVICE.index("ConnectorDiagnosticsAdapter.disconnect("),
        "an action named in the URL must prove it comes from the connector form before anything runs")

require("Redaction.redact" in ADAPTER,
        "every adapter result must pass through centralized secret redaction")
require("Collections.unmodifiableList" in ADAPTER,
        "sanitized diagnostic output must be immutable after construction")
require("getStackTrace" not in ADAPTER and "printStackTrace" not in ADAPTER,
        "diagnostics must not expose exception stack traces")

print("Connector diagnostics permission and read-only contracts verified.")
