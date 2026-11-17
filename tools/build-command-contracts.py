#!/usr/bin/env python3
"""Record upstream request builders with an offline transport for contract tests.

Use --source-root to select a freshly verified checkout of the pinned project.
Only the inspected command methods and hold selector are compiled from its AST;
the upstream modules, authentication, and network transport are never imported.
The generated cases are consumed without Python or network access by Groovy.
"""
import argparse
import ast
import asyncio
import copy
import hashlib
import json
import logging
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
REVISION = "0e2947b8ad83608ccf212dd2638f2aa3eda06eaa"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source-root", type=Path, default=ROOT / ".reference")
args = parser.parse_args()
source = args.source_root / "custom_components/resideo"

# Resolve only constant expressions needed by the inspected request methods.
constants = {}
for node in ast.parse((source / "aioresideo/const.py").read_text()).body:
    if isinstance(node, ast.Assign) and all(isinstance(t, ast.Name) for t in node.targets):
        try:
            value = ast.literal_eval(node.value)
        except (ValueError, TypeError):
            if not isinstance(node.value, ast.JoinedStr):
                continue
            value = "".join(part.value if isinstance(part, ast.Constant)
                            else str(constants[part.value.id]) for part in node.value.values)
        for target in node.targets:
            constants[target.id] = value


def methods(path, names):
    found = {}
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in names:
            node = copy.deepcopy(node)
            node.decorator_list = []
            node.returns = None
            for arg in node.args.posonlyargs + node.args.args + node.args.kwonlyargs:
                arg.annotation = None
            found[node.name] = node
    assert found.keys() == names, (path, names - found.keys())
    return list(found.values())


names = {"_command", "set_cool_setpoint", "set_heat_setpoint", "set_system_switch",
         "set_fan", "set_hold", "set_feels_like", "set_adaptive_recovery",
         "set_schedule_enabled", "set_freeze_protection", "set_setpoint_capabilities",
         "set_accessory_value"}
namespace = {**constants, "_LOGGER": logging.getLogger("command-contract-audit")}
module = ast.Module(body=methods(source / "aioresideo/client.py", names)
                    + methods(source / "climate.py", {"_write_hold_status"}), type_ignores=[])
exec(compile(ast.fix_missing_locations(module), "<inspected-upstream-builders>", "exec"), namespace)


async def record_put(self, endpoint, **kwargs):
    return {"method": "PUT", "url": constants["API_BASE_URL"] + endpoint,
            "body": kwargs["json"]}


Recorder = type("Recorder", (), {name: namespace[name] for name in names | {"_write_hold_status"}})
Recorder.put = record_put
client = Recorder()
mac = "AABBCCDDEEFF"
cases = []


def add(command, inputs, method, *values, sensor=False, setup=None, kwargs=None):
    request = asyncio.run(getattr(client, method)(mac, *values, **(kwargs or {})))
    scenario_setup = {"excludeMotion": True, "excludeTemp": False, "sensitivity": "Medium"} if sensor else {}
    scenario_setup.update(setup or {})
    cases.append({"command": command, "args": inputs, "sensor": sensor,
                  "setup": scenario_setup, "upstream_method": method, "request": request})


for local, remote in [("heat", "Heat"), ("cool", "Cool"), ("off", "Off"),
                      ("auto", "Auto"), ("emergency heat", "EmergencyHeat")]:
    add("setThermostatMode", [local], "set_system_switch", remote)
    add({"emergency heat": "emergencyHeat"}.get(local, local), [], "set_system_switch", remote)
for local, remote in [("auto", "Auto"), ("circulate", "Circulate"), ("on", "On")]:
    add("setThermostatFanMode", [local], "set_fan", remote)
    add({"auto": "fanAuto", "circulate": "fanCirculate", "on": "fanOn"}[local], [], "set_fan", remote)
for local, remote in [("none", "NoHold"), ("temporary", "TemporaryHold"), ("permanent", "PermanentHold")]:
    add("setHold", [local], "set_hold", remote, setup={"schedule": True})
add("resumeSchedule", [], "set_hold", "NoHold", setup={"schedule": True})
for enabled, hold in [(False, "NoHold"), (False, "PermanentHold"), (True, "NoHold"),
                      (True, "TemporaryHold"), (True, "PermanentHold"), (True, "VacationHold")]:
    client.device = SimpleNamespace(schedule_enabled=enabled, setpoint_status=hold)
    for command, method, value in [("setHeatingSetpoint", "set_heat_setpoint", 68.5),
                                    ("setCoolingSetpoint", "set_cool_setpoint", 78.5)]:
        add(command, [value], method, value, kwargs={"status": client._write_hold_status()},
            setup={"schedule": enabled, "hold": hold})
for value in [False, True]:
    text = "on" if value else "off"
    add("setScheduleEnabled", [text], "set_schedule_enabled", value)
    add("setFeelsLikeEnabled", [text], "set_feels_like", value)
    add("setAdaptiveRecoveryEnabled", [text], "set_adaptive_recovery",
        "AdaptiveIntelligentRecovery" if value else "None")
    add("setEmergencyHeatEnabled", [text], "set_system_switch", "EmergencyHeat" if value else "Heat")
add("setSetpointLimits", [50, 85, 55, 90], "set_setpoint_capabilities",
    kwargs={"heat_min": 50, "heat_max": 85, "cool_min": 55, "cool_max": 90})
add("setSetpointLimits", [10, 30, 15, 32], "set_setpoint_capabilities",
    kwargs={"heat_min": 50, "heat_max": 86, "cool_min": 59, "cool_max": 89.6}, setup={"scale": "C"})
for value in [35, 45, 40.6]:
    add("setFreezeProtectionLowLimit", [value], "set_freeze_protection", value)
add("setFreezeProtectionLowLimit", [5], "set_freeze_protection", 41, setup={"scale": "C"})
for command, method, celsius, fahrenheit in [("setHeatingSetpoint", "set_heat_setpoint", 20, 68),
                                          ("setCoolingSetpoint", "set_cool_setpoint", 25, 77)]:
    add(command, [celsius], method, fahrenheit, setup={"scale": "C"})
for sensitivity in ["Off", "Low", "Medium", "High"]:
    add("setOccupancySensitivity", [sensitivity.lower()], "set_accessory_value", 1, sensor=True,
        kwargs={"sensitivity": sensitivity, "exclude_motion": True, "exclude_temp": False})
for value in [False, True]:
    add("setExcludeMotion", ["on" if value else "off"], "set_accessory_value", 1, sensor=True,
        kwargs={"sensitivity": "Medium", "exclude_motion": value, "exclude_temp": False})
    add("setExcludeTemperature", ["on" if value else "off"], "set_accessory_value", 1, sensor=True,
        kwargs={"sensitivity": "Medium", "exclude_motion": True, "exclude_temp": value})

paths = ["aioresideo/client.py", "aioresideo/const.py", "climate.py"]
result = {"repository": "https://github.com/sfcodes/ha-resideo", "revision": REVISION,
          "headers": {"Accept": "application/json", "User-Agent": constants["APP_USER_AGENT"],
                      "Ocp-Apim-Subscription-Key": constants["OCP_APIM_SUBSCRIPTION_KEY"]},
          "source_hashes_without_trailing_whitespace": {
              path: hashlib.sha256((source / path).read_text().rstrip().encode()).hexdigest() for path in paths},
          "integer_half_ties": "Not compared: Hubitat uses half-up; upstream Python uses ties-to-even. Both emit valid integer Fahrenheit values.",
          "cases": cases}
destination = ROOT / "tests/fixtures/command-contracts.json"
destination.write_text(json.dumps(result, indent=2) + "\n")
print(f"Recorded {len(cases)} offline upstream command requests in {destination}")
