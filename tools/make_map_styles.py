#!/usr/bin/env python3
"""Builds the bundled map styles from OpenFreeMap's published ones.

Two changes are made:
  * every place/road/water label uses the English name (falling back to any Latin-script name, then the
    local one) instead of "Latin name + local name";
  * the dark style gets a visible railway layer from zoom 6 – upstream only draws rails from zoom 13, in a
    grey that is almost black.

Usage: tools/make_map_styles.py <assets dir> dark [liberty ...]   (writes map_<style>.json)
"""
import json
import sys
import urllib.request

ENGLISH = ["coalesce", ["get", "name:en"], ["get", "name_en"], ["get", "name:latin"], ["get", "name"]]


def build(name):
    request = urllib.request.Request(f"https://tiles.openfreemap.org/styles/{name}", headers={"User-Agent": "style-builder"})
    style = json.load(urllib.request.urlopen(request, timeout=60))
    layers = style["layers"]
    for layer in layers:
        field = layer.get("layout", {}).get("text-field")
        if field is not None and "name" in json.dumps(field):
            layer["layout"]["text-field"] = ENGLISH
    if name == "dark":
        first_label = next(i for i, layer in enumerate(layers) if layer["type"] == "symbol")
        layers.insert(first_label, {
            "id": "rail_overview",
            "type": "line",
            "source": "openmaptiles",
            "source-layer": "transportation",
            "minzoom": 6,
            "filter": ["all", ["==", ["get", "class"], "rail"], ["!", ["has", "service"]]],
            "layout": {"line-cap": "round", "line-join": "round"},
            "paint": {
                "line-color": "#5d6d7e",
                "line-width": ["interpolate", ["linear"], ["zoom"], 6, 0.5, 10, 1.0, 14, 1.8],
            },
        })
    return style


if __name__ == "__main__":
    out = sys.argv[1]
    for name in sys.argv[2:]:
        with open(f"{out}/map_{name}.json", "w") as f:
            json.dump(build(name), f, separators=(",", ":"))
        print("wrote", f"{out}/map_{name}.json")
