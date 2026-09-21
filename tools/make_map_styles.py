#!/usr/bin/env python3
"""Builds the bundled map styles from OpenFreeMap's published ones.

Two changes are made:
  * every place/road/water label uses the English name (falling back to any Latin-script name, then the
    local one) instead of "Latin name + local name";
  * the dark style gets a visible railway layer from zoom 6 – upstream only draws rails from zoom 13, in a
    grey that is almost black;
  * the dark style is re-coloured: upstream is near-black on black (grey-40 labels, grey-10 roads), which
    can't be read at a glance. Land stays dark, but water is blue, roads are visible and labels are light.

Usage: tools/make_map_styles.py <assets dir> dark [liberty ...]   (writes map_<style>.json)
"""
import json
import sys
import urllib.request

ENGLISH = ["coalesce", ["get", "name:en"], ["get", "name_en"], ["get", "name:latin"], ["get", "name"]]


HALO = {"text-halo-color": "#0b0f14", "text-halo-width": 1.4, "text-halo-blur": 0}

# layer id (or prefix ending in *) -> paint overrides for the dark style
DARK_PAINT = {
    "background": {"background-color": "#11161c"},
    "water": {"fill-color": "#0c2a40"},
    "waterway": {"line-color": "#12395a"},
    "landcover_wood": {"fill-color": "#13231b"},
    "landuse_park": {"fill-color": "#15281e"},
    "landuse_residential": {"fill-color": "#161c24"},
    "landcover_ice_shelf": {"fill-color": "#1b232c"},
    "landcover_glacier": {"fill-color": "#1b232c"},
    "building": {"fill-color": "#1b222b"},
    "highway_path": {"line-color": "#262e38"},
    "highway_minor": {"line-color": "#2c3540"},
    "highway_major_casing": {"line-color": "#1a2028"},
    "highway_major_inner": {"line-color": "#3d4856"},
    "highway_major_subtle": {"line-color": "#3d4856"},
    "highway_motorway_casing": {"line-color": "#1a2028"},
    "highway_motorway_inner": {"line-color": "#5a6678"},
    "highway_motorway_subtle": {"line-color": "#5a6678"},
    "railway*": {"line-color": "#5d6d7e"},
    "railway_dashline": {"line-color": "#11161c"},
    "railway_minor_dashline": {"line-color": "#11161c"},
    "railway_transit_dashline": {"line-color": "#11161c"},
    "rail_overview": {"line-color": "#71849a"},
    "boundary*": {"line-color": "#46525f"},
    "water_name": {"text-color": "#6fa3cc", **HALO},
    "highway_name*": {"text-color": "#97a3af", **HALO},
    "place_other": {"text-color": "#aab4be", **HALO},
    "place_suburb": {"text-color": "#aab4be", **HALO},
    "place_village": {"text-color": "#b9c2cb", **HALO},
    "place_state": {"text-color": "#8d98a4", **HALO},
    "place_town": {"text-color": "#d3dae1", **HALO},
    "place_city*": {"text-color": "#eef2f5", **HALO},
    "place_country*": {"text-color": "#eef2f5", **HALO},
}


def dark_paint(layer_id):
    if layer_id in DARK_PAINT:
        return DARK_PAINT[layer_id]
    for key, paint in DARK_PAINT.items():
        if key.endswith("*") and layer_id.startswith(key[:-1]):
            return paint
    return None


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
        for layer in layers:
            paint = dark_paint(layer["id"])
            if paint:
                layer.setdefault("paint", {}).update(paint)
    return style


if __name__ == "__main__":
    out = sys.argv[1]
    for name in sys.argv[2:]:
        with open(f"{out}/map_{name}.json", "w") as f:
            json.dump(build(name), f, separators=(",", ":"))
        print("wrote", f"{out}/map_{name}.json")
