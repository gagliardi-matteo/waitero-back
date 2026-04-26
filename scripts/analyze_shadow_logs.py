#!/usr/bin/env python3
"""Analyze analyticsv2 shadow-mode comparison logs and estimate business impact."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

TEXT_SUFFIXES = {".log", ".json", ".jsonl", ".txt", ".out"}
CATALOG_SUFFIXES = {".csv", ".json", ".jsonl"}
REQUIRED_KEYS = {"restaurantId", "type", "v1", "v2", "differences"}
DIFF_UNCHANGED = "UNCHANGED"
DIFF_ONLY_IN_V2 = "ONLY_IN_V2"
DIFF_ONLY_IN_V1 = "ONLY_IN_V1"
DIFF_MOVED_UP = "MOVED_UP_IN_V2"
DIFF_MOVED_DOWN = "MOVED_DOWN_IN_V2"
TOP_N = 5


@dataclass(frozen=True)
class DishSnapshot:
    position: int | None
    dish_id: str | None
    dish_name: str
    category: str | None


@dataclass(frozen=True)
class Difference:
    dish_id: str | None
    dish_name: str
    v1_position: int | None
    v2_position: int | None
    difference_type: str


@dataclass(frozen=True)
class ShadowEntry:
    source: str
    restaurant_id: str
    entry_type: str
    mode: str | None
    date_from: str | None
    date_to: str | None
    base_dish_id: str | None
    cart_dish_ids: tuple[str, ...]
    v1: tuple[DishSnapshot, ...]
    v2: tuple[DishSnapshot, ...]
    differences: tuple[Difference, ...]

    def changed(self) -> bool:
        return any(diff.difference_type != DIFF_UNCHANGED for diff in self.differences)

    def difference_ratio(self) -> float:
        if not self.differences:
            return 0.0
        changed = sum(1 for diff in self.differences if diff.difference_type != DIFF_UNCHANGED)
        return safe_divide(changed, len(self.differences))

    def overlap_ratio(self, limit: int = 5) -> float:
        v1_ids = {dish.dish_id for dish in self.v1[:limit] if dish.dish_id}
        v2_ids = {dish.dish_id for dish in self.v2[:limit] if dish.dish_id}
        return safe_divide(len(v1_ids & v2_ids), max(len(v1_ids), len(v2_ids), 1))

    def v2_signature(self) -> tuple[str | None, ...]:
        return tuple(dish.dish_id for dish in self.v2)

    def context_signature(self) -> tuple[Any, ...]:
        if self.entry_type == "ranking":
            return (self.restaurant_id, "ranking", self.date_from, self.date_to)
        if self.entry_type == "upsell" and self.mode == "dish":
            return (self.restaurant_id, "upsell", "dish", self.base_dish_id, self.date_from, self.date_to)
        if self.entry_type == "upsell" and self.mode == "cart":
            return (self.restaurant_id, "upsell", "cart", self.cart_dish_ids, self.date_from, self.date_to)
        return (self.restaurant_id, self.entry_type, self.mode, self.date_from, self.date_to)


class PriceCatalog:
    def __init__(self, rows: Iterable[dict[str, Any]]) -> None:
        self.by_restaurant_dish: dict[tuple[str, str], float] = {}
        self.by_dish: dict[str, float] = {}
        for row in rows:
            restaurant_id = normalize_identifier(row.get("restaurantId") or row.get("restaurant_id") or row.get("ristoratore_id"))
            dish_id = normalize_identifier(row.get("dishId") or row.get("dish_id") or row.get("piatto_id") or row.get("id"))
            price = normalize_float(row.get("price") if row.get("price") is not None else row.get("prezzo"))
            if dish_id is None or price is None:
                continue
            if restaurant_id:
                self.by_restaurant_dish[(restaurant_id, dish_id)] = price
            self.by_dish[dish_id] = price

    def lookup(self, restaurant_id: str, dish_id: str | None) -> float | None:
        if dish_id is None:
            return None
        if (restaurant_id, dish_id) in self.by_restaurant_dish:
            return self.by_restaurant_dish[(restaurant_id, dish_id)]
        return self.by_dish.get(dish_id)

    def available(self) -> bool:
        return bool(self.by_restaurant_dish or self.by_dish)


def normalize_identifier(value: Any) -> str | None:
    if value is None or value == "":
        return None
    return str(value)


def normalize_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def normalize_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def normalize_text(value: Any, default: str = "") -> str:
    if value is None:
        return default
    return str(value)


def safe_divide(numerator: float, denominator: float) -> float:
    return 0.0 if denominator == 0 else numerator / denominator


def percent_change(base_value: float | None, new_value: float | None) -> float | None:
    if base_value is None or new_value is None:
        return None
    if base_value == 0:
        return 0.0 if new_value == 0 else None
    return (new_value - base_value) / base_value


def ranking_weight(position: int | None, limit: int = TOP_N) -> float:
    if position is None or position < 1 or position > limit:
        return 0.0
    return (limit - position + 1) / limit


def to_percent(value: float) -> float:
    return round(value * 100.0, 2)


def display_percent(value: float | None) -> str:
    if value is None:
        return "n/a"
    return f"{to_percent(value):.2f}%"


def round_money(value: float | None) -> float | None:
    if value is None:
        return None
    return round(value, 2)


def display_money(value: float | None) -> str:
    if value is None:
        return "n/a"
    return f"EUR {round_money(value):.2f}"


def dish_label(dish_id: str | None, dish_name: str) -> str:
    return f"{dish_name} (#{dish_id})" if dish_id else dish_name

def normalize_dish_snapshot(raw: Any) -> DishSnapshot:
    if not isinstance(raw, dict):
        return DishSnapshot(None, None, "UNKNOWN", None)
    return DishSnapshot(
        normalize_int(raw.get("position")),
        normalize_identifier(raw.get("dishId")),
        normalize_text(raw.get("dishName"), "UNKNOWN"),
        normalize_text(raw.get("category")) or None,
    )


def normalize_difference(raw: Any) -> Difference:
    if not isinstance(raw, dict):
        return Difference(None, "UNKNOWN", None, None, "UNKNOWN")
    return Difference(
        normalize_identifier(raw.get("dishId")),
        normalize_text(raw.get("dishName"), "UNKNOWN"),
        normalize_int(raw.get("v1Position")),
        normalize_int(raw.get("v2Position")),
        normalize_text(raw.get("differenceType"), "UNKNOWN"),
    )


def is_shadow_payload(raw: Any) -> bool:
    return isinstance(raw, dict) and REQUIRED_KEYS.issubset(raw.keys()) and raw.get("type") in {"ranking", "upsell"}


def parse_shadow_entry(raw: dict[str, Any], source: str) -> ShadowEntry:
    dish_ids = raw.get("dishIds") if isinstance(raw.get("dishIds"), list) else []
    return ShadowEntry(
        source=source,
        restaurant_id=normalize_text(raw.get("restaurantId"), "UNKNOWN"),
        entry_type=normalize_text(raw.get("type"), "unknown"),
        mode=normalize_text(raw.get("mode")) or None,
        date_from=normalize_text(raw.get("dateFrom")) or None,
        date_to=normalize_text(raw.get("dateTo")) or None,
        base_dish_id=normalize_identifier(raw.get("dishId")),
        cart_dish_ids=tuple(sorted(filter(None, (normalize_identifier(item) for item in dish_ids)))),
        v1=tuple(normalize_dish_snapshot(item) for item in raw.get("v1", [])),
        v2=tuple(normalize_dish_snapshot(item) for item in raw.get("v2", [])),
        differences=tuple(normalize_difference(item) for item in raw.get("differences", [])),
    )


def extract_shadow_entries(text: str, source: Path) -> list[ShadowEntry]:
    decoder = json.JSONDecoder()
    cursor = 0
    entries: list[ShadowEntry] = []
    while True:
        start = text.find("{", cursor)
        if start < 0:
            break
        try:
            payload, end = decoder.raw_decode(text, start)
        except json.JSONDecodeError:
            cursor = start + 1
            continue
        cursor = end
        if is_shadow_payload(payload):
            entries.append(parse_shadow_entry(payload, str(source)))
    return entries


def collect_files(inputs: list[Path], suffixes: set[str]) -> list[Path]:
    files: list[Path] = []
    for entry in inputs:
        if entry.is_file():
            files.append(entry)
        elif entry.is_dir():
            files.extend(sorted(path for path in entry.rglob("*") if path.is_file() and path.suffix.lower() in suffixes))
        else:
            raise FileNotFoundError(f"Input path not found: {entry}")
    return files


def load_entries(inputs: list[Path]) -> tuple[list[ShadowEntry], list[Path]]:
    if not inputs:
        payload = sys.stdin.read()
        if not payload.strip():
            raise ValueError("No input provided. Pass files/directories or pipe logs through stdin.")
        return extract_shadow_entries(payload, Path("<stdin>")), []
    files = collect_files(inputs, TEXT_SUFFIXES)
    if not files:
        raise ValueError("No readable log files found in the provided paths.")
    entries: list[ShadowEntry] = []
    for path in files:
        entries.extend(extract_shadow_entries(path.read_text(encoding="utf-8", errors="replace"), path))
    return entries, files


def extract_catalog_rows(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        for key in ("dishes", "items", "rows", "data"):
            if isinstance(payload.get(key), list):
                return extract_catalog_rows(payload[key])
        return [payload]
    return []


def load_catalog(paths: list[Path]) -> tuple[PriceCatalog | None, list[Path]]:
    if not paths:
        return None, []
    files = collect_files(paths, CATALOG_SUFFIXES)
    rows: list[dict[str, Any]] = []
    for path in files:
        suffix = path.suffix.lower()
        if suffix == ".csv":
            with path.open("r", encoding="utf-8", newline="") as handle:
                rows.extend(dict(row) for row in csv.DictReader(handle))
        elif suffix == ".jsonl":
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.strip():
                    rows.extend(extract_catalog_rows(json.loads(line)))
        else:
            rows.extend(extract_catalog_rows(json.loads(path.read_text(encoding="utf-8", errors="replace"))))
    catalog = PriceCatalog(rows)
    return (catalog if catalog.available() else None), files


def dominant_share(entries: Iterable[ShadowEntry]) -> float:
    grouped: dict[tuple[Any, ...], Counter[tuple[str | None, ...]]] = defaultdict(Counter)
    total = 0
    dominant = 0
    for entry in entries:
        grouped[entry.context_signature()][entry.v2_signature()] += 1
    for variants in grouped.values():
        size = sum(variants.values())
        total += size
        dominant += max(variants.values()) if variants else 0
    return safe_divide(dominant, total)


def counter_top(counter: Counter[str], limit: int) -> list[dict[str, Any]]:
    return [{"dish": dish, "count": count} for dish, count in counter.most_common(limit)]


def promotion_focus(counter: Counter[str]) -> float:
    total = sum(counter.values())
    return 0.0 if total == 0 else safe_divide(sum(count for _, count in counter.most_common(3)), total)

def top_impacts(impacts: dict[str, float], limit: int) -> list[dict[str, Any]]:
    rows = [{"dish": dish, "estimatedRevenueImpact": round_money(value)} for dish, value in impacts.items() if value > 0]
    return sorted(rows, key=lambda item: (-float(item["estimatedRevenueImpact"] or 0.0), item["dish"]))[:limit]


def compute_business_impact(entries: list[ShadowEntry], catalog: PriceCatalog | None, top_n: int) -> dict[str, Any]:
    if catalog is None:
        return {
            "catalog_available": False,
            "price_coverage": 0.0,
            "priced_exposures": 0,
            "total_price_exposures": 0,
            "ranking_top5_revenue_v1": None,
            "ranking_top5_revenue_v2": None,
            "ranking_weighted_revenue_v1": None,
            "ranking_weighted_revenue_v2": None,
            "upsell_revenue_v1": None,
            "upsell_revenue_v2": None,
            "estimated_revenue_v1": None,
            "estimated_revenue_v2": None,
            "estimated_uplift": None,
            "estimated_uplift_pct": None,
            "topUpliftContributors": [],
            "riskyDifferences": [],
        }

    priced_exposures = 0
    total_exposures = 0
    ranking_top5_v1 = 0.0
    ranking_top5_v2 = 0.0
    ranking_weighted_v1 = 0.0
    ranking_weighted_v2 = 0.0
    upsell_v1 = 0.0
    upsell_v2 = 0.0
    uplift_impacts: dict[str, float] = defaultdict(float)
    risk_impacts: dict[str, float] = defaultdict(float)

    for entry in entries:
        if entry.entry_type == "ranking":
            v1_top = entry.v1[:TOP_N]
            v2_top = entry.v2[:TOP_N]
            for dish in v1_top:
                total_exposures += 1
                price = catalog.lookup(entry.restaurant_id, dish.dish_id)
                if price is not None:
                    priced_exposures += 1
                    ranking_top5_v1 += price
                    ranking_weighted_v1 += price * ranking_weight(dish.position)
            for dish in v2_top:
                total_exposures += 1
                price = catalog.lookup(entry.restaurant_id, dish.dish_id)
                if price is not None:
                    priced_exposures += 1
                    ranking_top5_v2 += price
                    ranking_weighted_v2 += price * ranking_weight(dish.position)
            v1_ids = {dish.dish_id: dish for dish in v1_top if dish.dish_id}
            v2_ids = {dish.dish_id: dish for dish in v2_top if dish.dish_id}
            for dish_id in sorted(set(v1_ids) | set(v2_ids)):
                ref = v2_ids.get(dish_id) or v1_ids.get(dish_id)
                if ref is None:
                    continue
                price = catalog.lookup(entry.restaurant_id, dish_id)
                if price is None:
                    continue
                delta = price * ((ranking_weight(v2_ids[dish_id].position) if dish_id in v2_ids else 0.0) - (ranking_weight(v1_ids[dish_id].position) if dish_id in v1_ids else 0.0))
                label = dish_label(ref.dish_id, ref.dish_name)
                if delta > 0:
                    uplift_impacts[label] += delta
                elif delta < 0:
                    risk_impacts[label] += abs(delta)

        if entry.entry_type == "upsell":
            for dish in entry.v1:
                total_exposures += 1
                price = catalog.lookup(entry.restaurant_id, dish.dish_id)
                if price is not None:
                    priced_exposures += 1
                    upsell_v1 += price
            for dish in entry.v2:
                total_exposures += 1
                price = catalog.lookup(entry.restaurant_id, dish.dish_id)
                if price is not None:
                    priced_exposures += 1
                    upsell_v2 += price
            v1_ids = {dish.dish_id: dish for dish in entry.v1 if dish.dish_id}
            v2_ids = {dish.dish_id: dish for dish in entry.v2 if dish.dish_id}
            for dish_id in sorted(set(v1_ids) | set(v2_ids)):
                ref = v2_ids.get(dish_id) or v1_ids.get(dish_id)
                if ref is None:
                    continue
                price = catalog.lookup(entry.restaurant_id, dish_id)
                if price is None:
                    continue
                delta = price * ((1 if dish_id in v2_ids else 0) - (1 if dish_id in v1_ids else 0))
                label = dish_label(ref.dish_id, ref.dish_name)
                if delta > 0:
                    uplift_impacts[label] += delta
                elif delta < 0:
                    risk_impacts[label] += abs(delta)

    estimated_v1 = ranking_weighted_v1 + upsell_v1
    estimated_v2 = ranking_weighted_v2 + upsell_v2
    return {
        "catalog_available": True,
        "price_coverage": safe_divide(priced_exposures, total_exposures),
        "priced_exposures": priced_exposures,
        "total_price_exposures": total_exposures,
        "ranking_top5_revenue_v1": round_money(ranking_top5_v1),
        "ranking_top5_revenue_v2": round_money(ranking_top5_v2),
        "ranking_weighted_revenue_v1": round_money(ranking_weighted_v1),
        "ranking_weighted_revenue_v2": round_money(ranking_weighted_v2),
        "upsell_revenue_v1": round_money(upsell_v1),
        "upsell_revenue_v2": round_money(upsell_v2),
        "estimated_revenue_v1": round_money(estimated_v1),
        "estimated_revenue_v2": round_money(estimated_v2),
        "estimated_uplift": round_money(estimated_v2 - estimated_v1),
        "estimated_uplift_pct": percent_change(estimated_v1, estimated_v2),
        "topUpliftContributors": top_impacts(uplift_impacts, top_n),
        "riskyDifferences": top_impacts(risk_impacts, top_n),
    }


def classify_recommendation(metrics: dict[str, Any]) -> tuple[str, str]:
    uplift_pct = metrics.get("estimated_uplift_pct")
    coverage = metrics.get("price_coverage", 0.0)
    if metrics["v2Consistency"] < 0.95:
        return "V2 worse", "V2 is not deterministic enough yet. The same input does not reliably produce the same V2 output."
    if uplift_pct is not None and coverage >= 0.80:
        if uplift_pct >= 0.05 and metrics["v2Consistency"] >= 0.97:
            return "V2 better", "V2 is stable and the priced evidence indicates positive revenue impact."
        if uplift_pct <= -0.05:
            return "V2 worse", "V2 is stable enough to measure, but the priced evidence indicates negative revenue impact."
        if abs(uplift_pct) <= 0.03 and metrics["v2Consistency"] >= 0.97:
            return "V2 similar", "Priced evidence suggests the revenue impact is close to neutral."
    if metrics["rankingEvents"] > 0 and metrics["top5Overlap"] < 0.40 and metrics["rankingDifference"] > 0.60:
        return "V2 worse", "Ranking divergence is too high relative to V1 for a safe rollout decision."
    if metrics["rankingEvents"] > 0 and metrics["v2Consistency"] >= 0.98 and metrics["rankingDifference"] <= 0.20 and metrics["upsellDifference"] <= 0.25:
        return "V2 similar", "V2 stays close to V1 and the observed differences are minor."
    if metrics["v2Consistency"] >= 0.98 and metrics["top5Overlap"] >= 0.60 and metrics["changedEventRate"] >= 0.20 and metrics["rankingPromotionFocus"] >= 0.35:
        return "V2 better", "V2 differs from V1 in a stable, concentrated way rather than random noise."
    if metrics["v2Consistency"] >= 0.97 and metrics["top5Overlap"] >= 0.55:
        return "V2 similar", "V2 is stable, but the current evidence does not justify a stronger claim than similarity."
    return "V2 worse", "V2 diverges materially without enough overlap or stability to support rollout."


def summarize(entries: list[ShadowEntry], top_n: int, catalog: PriceCatalog | None, restaurant_id: str | None = None) -> dict[str, Any]:
    ranking_entries = [entry for entry in entries if entry.entry_type == "ranking"]
    upsell_entries = [entry for entry in entries if entry.entry_type == "upsell"]
    ranking_promoted = Counter()
    ranking_demoted = Counter()
    upsell_new = Counter()
    for entry in entries:
        for diff in entry.differences:
            label = dish_label(diff.dish_id, diff.dish_name)
            if entry.entry_type == "ranking":
                if diff.difference_type in {DIFF_MOVED_UP, DIFF_ONLY_IN_V2}:
                    ranking_promoted[label] += 1
                if diff.difference_type in {DIFF_MOVED_DOWN, DIFF_ONLY_IN_V1}:
                    ranking_demoted[label] += 1
            elif entry.entry_type == "upsell" and diff.difference_type == DIFF_ONLY_IN_V2:
                upsell_new[label] += 1
    metrics = {
        "restaurantId": restaurant_id,
        "totalEvents": len(entries),
        "rankingEvents": len(ranking_entries),
        "upsellEvents": len(upsell_entries),
        "changedEvents": sum(1 for entry in entries if entry.changed()),
        "changedEventRate": safe_divide(sum(1 for entry in entries if entry.changed()), len(entries)),
        "rankingDifference": 0.0 if not ranking_entries else sum(entry.difference_ratio() for entry in ranking_entries) / len(ranking_entries),
        "top5Overlap": 0.0 if not ranking_entries else sum(entry.overlap_ratio(5) for entry in ranking_entries) / len(ranking_entries),
        "upsellDifference": 0.0 if not upsell_entries else sum(entry.difference_ratio() for entry in upsell_entries) / len(upsell_entries),
        "upsellTop5Overlap": 0.0 if not upsell_entries else sum(entry.overlap_ratio(5) for entry in upsell_entries) / len(upsell_entries),
        "v2Consistency": dominant_share(entries),
        "rankingConsistency": dominant_share(ranking_entries),
        "upsellConsistency": dominant_share(upsell_entries),
        "rankingPromotionFocus": promotion_focus(ranking_promoted),
        "rankingPromotedDishes": counter_top(ranking_promoted, top_n),
        "rankingDemotedDishes": counter_top(ranking_demoted, top_n),
        "newUpsellSuggestions": counter_top(upsell_new, top_n),
    }
    metrics.update(compute_business_impact(entries, catalog, top_n))
    metrics["recommendation"], metrics["recommendationReason"] = classify_recommendation(metrics)
    return metrics

def build_business_lines(metrics: dict[str, Any]) -> list[str]:
    if not metrics.get("catalog_available"):
        return ["- Revenue impact: unavailable. Provide --catalog with dishId, optional restaurantId, and price/prezzo."]
    lines = [
        f"- Price coverage: {display_percent(metrics['price_coverage'])} ({metrics['priced_exposures']}/{metrics['total_price_exposures']} priced dish exposures)",
        f"- Estimated revenue V1: {display_money(metrics['estimated_revenue_v1'])}",
        f"- Estimated revenue V2: {display_money(metrics['estimated_revenue_v2'])}",
        f"- Estimated uplift: {display_money(metrics['estimated_uplift'])} ({display_percent(metrics['estimated_uplift_pct'])})",
        f"- Top-5 dish revenue V1: {display_money(metrics['ranking_top5_revenue_v1'])}",
        f"- Top-5 dish revenue V2: {display_money(metrics['ranking_top5_revenue_v2'])}",
        f"- Ranking weighted revenue V1: {display_money(metrics['ranking_weighted_revenue_v1'])}",
        f"- Ranking weighted revenue V2: {display_money(metrics['ranking_weighted_revenue_v2'])}",
        f"- Upsell revenue opportunity V1: {display_money(metrics['upsell_revenue_v1'])}",
        f"- Upsell revenue opportunity V2: {display_money(metrics['upsell_revenue_v2'])}",
    ]
    if metrics["estimated_uplift_pct"] is None:
        lines.append("- V2 expected revenue change: n/a because the V1 priced baseline is zero.")
    elif metrics["estimated_uplift_pct"] >= 0:
        lines.append(f"- V2 expected to increase revenue by {display_percent(metrics['estimated_uplift_pct'])}")
    else:
        lines.append(f"- V2 expected to decrease revenue by {display_percent(abs(metrics['estimated_uplift_pct']))}")
    return lines


def build_impact_section(title: str, rows: list[dict[str, Any]], suffix: str) -> list[str]:
    lines = [title]
    if rows:
        for item in rows:
            lines.append(f"- {item['dish']}: {display_money(item['estimatedRevenueImpact'])} {suffix}")
    else:
        lines.append(f"- No {title.lower()} were observed.")
    lines.append("")
    return lines


def build_report(global_summary: dict[str, Any], restaurants: list[dict[str, Any]], catalog_files: list[Path]) -> str:
    lines = [
        "Analytics V2 Shadow Mode Analysis",
        "",
        "Global Summary",
        f"- Parsed comparison events: {global_summary['totalEvents']}",
        f"- Restaurants analyzed: {len(restaurants)}",
        f"- Ranking events: {global_summary['rankingEvents']}",
        f"- Upsell events: {global_summary['upsellEvents']}",
        f"- V2 differs from V1 in {display_percent(global_summary['changedEventRate'])} of all events",
        f"- Average ranking difference: {display_percent(global_summary['rankingDifference'])}",
        f"- Average top-5 ranking overlap: {display_percent(global_summary['top5Overlap'])}",
        f"- Average upsell difference: {display_percent(global_summary['upsellDifference'])}",
        f"- Average top-5 upsell overlap: {display_percent(global_summary['upsellTop5Overlap'])}",
        f"- V2 consistency: {display_percent(global_summary['v2Consistency'])}",
    ]
    if catalog_files:
        lines.append(f"- Catalog files used for pricing: {len(catalog_files)}")
    lines.extend(build_business_lines(global_summary))
    lines.append(f"- Recommendation: {global_summary['recommendation']}")
    lines.append(f"  Reason: {global_summary['recommendationReason']}")
    lines.append("")
    lines.extend(build_impact_section("Top Dishes Contributing To Uplift", global_summary["topUpliftContributors"], "expected uplift"))
    lines.extend(build_impact_section("Risky Differences", global_summary["riskyDifferences"], "at risk"))
    lines.append("Per-Restaurant Summary")
    if not restaurants:
        lines.append("- No restaurant-level data found.")
        return "\n".join(lines)
    for restaurant in restaurants:
        lines.append(f"Restaurant {restaurant['restaurantId']}")
        lines.append(f"- Events: {restaurant['totalEvents']} (ranking {restaurant['rankingEvents']}, upsell {restaurant['upsellEvents']})")
        lines.append(f"- V2 differs from V1 in {display_percent(restaurant['changedEventRate'])} of events")
        lines.append(f"- Ranking difference: {display_percent(restaurant['rankingDifference'])}")
        lines.append(f"- Top-5 ranking overlap: {display_percent(restaurant['top5Overlap'])}")
        lines.append(f"- Upsell difference: {display_percent(restaurant['upsellDifference'])}")
        lines.append(f"- Top-5 upsell overlap: {display_percent(restaurant['upsellTop5Overlap'])}")
        lines.append(f"- V2 consistency: {display_percent(restaurant['v2Consistency'])}")
        lines.extend(build_business_lines(restaurant))
        lines.append(f"- Recommendation: {restaurant['recommendation']}")
        lines.append(f"  Reason: {restaurant['recommendationReason']}")
        if restaurant["topUpliftContributors"]:
            lines.append("- Top dishes contributing to uplift:")
            for item in restaurant["topUpliftContributors"]:
                lines.append(f"- {item['dish']}: {display_money(item['estimatedRevenueImpact'])}")
        else:
            lines.append("- Top dishes contributing to uplift: none observed")
        if restaurant["riskyDifferences"]:
            lines.append("- Risky differences:")
            for item in restaurant["riskyDifferences"]:
                lines.append(f"- {item['dish']}: {display_money(item['estimatedRevenueImpact'])} at risk")
        else:
            lines.append("- Risky differences: none observed")
        lines.append("")
    return "\n".join(lines).rstrip()


def build_json_payload(global_summary: dict[str, Any], restaurants: list[dict[str, Any]], files: list[Path], catalog_files: list[Path]) -> dict[str, Any]:
    return {
        "inputs": [str(path) for path in files],
        "catalogInputs": [str(path) for path in catalog_files],
        "globalSummary": global_summary,
        "restaurants": restaurants,
    }


def write_output(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Analyze analyticsv2 shadow-mode comparison logs and summarize rollout readiness.")
    parser.add_argument("inputs", nargs="*", type=Path, help="Log files or directories to scan. Reads stdin when omitted.")
    parser.add_argument("--catalog", action="append", type=Path, default=[], help="CSV/JSON/JSONL dish price catalog. Expected fields: dishId or id, optional restaurantId, and price/prezzo.")
    parser.add_argument("--json-out", type=Path, help="Optional path for a machine-readable JSON summary.")
    parser.add_argument("--report-out", type=Path, help="Optional path for the human-readable report.")
    parser.add_argument("--top-n", type=int, default=10, help="Number of promoted/new dishes to show in summary tables. Defaults to 10.")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        entries, files = load_entries(args.inputs)
        catalog, catalog_files = load_catalog(args.catalog)
    except Exception as exc:
        print(f"analysis failed: {exc}", file=sys.stderr)
        return 1
    if not entries:
        print("analysis failed: no shadow comparison payloads were found in the provided input.", file=sys.stderr)
        return 1
    by_restaurant: dict[str, list[ShadowEntry]] = defaultdict(list)
    for entry in entries:
        by_restaurant[entry.restaurant_id].append(entry)
    restaurants = [
        summarize(group, args.top_n, catalog, restaurant_id)
        for restaurant_id, group in sorted(by_restaurant.items(), key=lambda item: str(item[0]))
    ]
    global_summary = summarize(entries, args.top_n, catalog)
    report = build_report(global_summary, restaurants, catalog_files)
    payload = build_json_payload(global_summary, restaurants, files, catalog_files)
    print(report)
    if args.report_out:
        write_output(args.report_out, report + "\n")
    if args.json_out:
        write_output(args.json_out, json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

