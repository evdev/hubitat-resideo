#!/usr/bin/env python3
"""Regenerate the parity manifest from the pinned upstream files in .reference.

The checked-in manifest is used by tests; this maintenance tool is not needed on
Hubitat or when running the normal test suite.
"""
import ast
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = ROOT / ".reference/custom_components/resideo"
ATTRIBUTES = {
    "indoor_temperature": "temperature", "indoor_humidity": "humidity",
    "heat_setpoint": "heatingSetpoint", "cool_setpoint": "coolingSetpoint",
    "setpoint_status": "holdStatus", "co2": "carbonDioxide", "tvoc": "voc",
    "fan_running": "fanRunning", "feels_like": "feelsLikeEnabled",
    "adaptive_recovery": "adaptiveRecoveryEnabled", "schedule": "scheduleEnabled",
    "emergency_heat": "emergencyHeatEnabled", "freeze_protection_low_limit": "freezeProtectionLowLimit",
    "heat_setpoint_min": "heatSetpointMin", "heat_setpoint_max": "heatSetpointMax",
    "cool_setpoint_min": "coolSetpointMin", "cool_setpoint_max": "coolSetpointMax",
}
COMMANDS = {
    "feels_like": "setFeelsLikeEnabled", "adaptive_recovery": "setAdaptiveRecoveryEnabled",
    "schedule": "setScheduleEnabled", "emergency_heat": "setEmergencyHeatEnabled",
    "freeze_protection_low_limit": "setFreezeProtectionLowLimit",
    "exclude_motion": "setExcludeMotion", "exclude_temperature": "setExcludeTemperature",
    "occupancy_sensitivity": "setOccupancySensitivity",
    **{key: "setSetpointLimits" for key in ("heat_setpoint_min", "heat_setpoint_max", "cool_setpoint_min", "cool_setpoint_max")},
}


def camel(key):
    return key.split("_")[0] + "".join(p.title() for p in key.split("_")[1:])


def units(key):
    if key == "temperature_units":
        return "F / C metadata"
    if key == "exclude_temperature":
        return "on / off"
    if "temperature" in key or "setpoint" in key and key != "setpoint_status" or key == "freeze_protection_low_limit":
        return "hub °F/°C"
    if "humidity" in key and key != "humidity_quality" or key == "demand":
        return "%"
    return {"co2": "ppm", "signal_strength": "dBm", "occupancy_timeout": "seconds", "air_filter_remaining": "days",
            "ventilation_timer": "minutes", "ventilation_boost_timer": "minutes", "tvoc": "upstream unspecified",
            "voc": "upstream unspecified"}.get(key, "—")


rows = [{"platform": "climate", "scope": "thermostat", "key": "thermostat",
         "attributes": ["temperature", "humidity", "thermostatMode", "thermostatFanMode", "thermostatOperatingState", "heatingSetpoint", "coolingSetpoint", "holdStatus"],
         "command": "standard Thermostat commands; setHold; resumeSchedule", "units": "hub °F/°C",
         "availability": "reported values; configured supported modes/limits", "write": "accepted → reported-state confirmation"}]
for platform in ("sensor", "binary_sensor", "switch", "number"):
    tree = ast.parse((UPSTREAM / (platform + ".py")).read_text())
    for node in tree.body:
        if not isinstance(node, ast.AnnAssign) or not isinstance(node.target, ast.Name) or not isinstance(node.value, ast.Tuple):
            continue
        if node.target.id not in ("DEVICE_SENSORS", "ACCESSORY_SENSORS", "DEVICE_BINARY_SENSORS", "ACCESSORY_BINARY_SENSORS", "DEVICE_SWITCHES", "ACCESSORY_SWITCHES", "DEVICE_NUMBERS", "SETPOINT_LIMIT_NUMBERS"):
            continue
        scope = "sensor" if node.target.id.startswith("ACCESSORY") else "thermostat"
        for entity in node.value.elts:
            keywords = {kw.arg: kw.value for kw in entity.keywords}
            key = ast.literal_eval(keywords["key"]) if "key" in keywords else ast.literal_eval(entity.args[0])
            availability = "reported value; missing values remain unset"
            if "exists_fn" in keywords:
                availability = ast.unparse(keywords["exists_fn"]).replace("lambda d: ", "").replace("lambda a: ", "")
            if scope == "sensor" and platform == "switch":
                availability = "remote sensor reports OccupancySensitivity"
            attr = ATTRIBUTES.get(key, camel(key))
            rows.append({"platform": platform, "scope": scope, "key": key, "attributes": [attr],
                         "command": COMMANDS.get(key, "—"), "units": units(key), "availability": availability,
                         "write": "accepted → reported-state confirmation" if platform in ("switch", "number") else "read-only"})
rows.append({"platform": "select", "scope": "sensor", "key": "occupancy_sensitivity", "attributes": ["occupancySensitivity"],
             "command": "setOccupancySensitivity", "units": "Off / Low / Medium / High", "availability": "remote sensor reports OccupancySensitivity",
             "write": "pending until sensor check-in; persists across restart"})
(ROOT / "tests/parity.json").write_text(json.dumps(rows, indent=2) + "\n")
lines = ["# Upstream parity matrix", "", "Reference: sfcodes/ha-resideo `0e2947b8ad83608ccf212dd2638f2aa3eda06eaa`.", "",
         "All upstream platform entities are mapped below. Where hardware omits a reading, its Hubitat attribute remains unset. "
         "The availability expressions refer to the upstream property names. Static Hubitat metadata still lists custom commands; unsupported operations are rejected.", "",
         "| Platform | Device | Upstream entity | Hubitat attribute(s) | Command | Units | Availability | Write behavior |",
         "|---|---|---|---|---|---|---|---|"]
for row in rows:
    lines.append("| " + " | ".join(str(row[k]).replace("|", "\\|") if k != "attributes" else ", ".join(row[k])
                                    for k in ("platform", "scope", "key", "attributes", "command", "units", "availability", "write")) + " |")
lines += ["", "Extra integration attributes: `connectionStatus`, `dataStatus`, `updateSource`, `lastUpdated`, `commandStatus`, and `lastCommandError`.", "",
          "Vacation/timed holds, room-priority settings, schedule programming, backlight, ventilation, and Matter provisioning remain read-only where upstream exposes no writable platform control. "
          "The room-priority API method exists upstream but is not exposed by its platforms, so this port does not add a new writable control.", "",
          "`BatteryStatus` is a category, not a percentage. VOC units are unspecified upstream and remain unlabeled. All API thermostat and room temperatures use Fahrenheit; outdoor units use account country.", ""]
(ROOT / "docs/parity.md").parent.mkdir(exist_ok=True)
(ROOT / "docs/parity.md").write_text("\n".join(lines))
print(f"Mapped {len(rows)} upstream entities")
