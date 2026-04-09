#!/usr/bin/env python3
"""
WAITER0 behavioral user simulator.

This script calls the real backend API. It does not write directly to the DB and
it does not assign A/B variants manually. Variants are assigned by the backend
through the same sessionId passed to menu, upsell and order APIs.

Typical usage:
  python scripts/simulate_users.py --config scripts/user_simulation_config.example.json

The simulation produces CSV files for daily KPIs, order-level outcomes, session
outcomes and backend A/B metrics when an analytics token is configured.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable
from http.client import RemoteDisconnected
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class Persona:
    name: str
    min_budget: float
    max_budget: float
    order_probability: float
    upsell_accept_probability: float
    click_probability: float
    detail_view_probability: float
    impulse_probability: float
    bad_decision_probability: float
    max_items: int


PERSONAS: dict[str, Persona] = {
    "LOW_SPENDER": Persona(
        name="LOW_SPENDER",
        min_budget=9.0,
        max_budget=19.0,
        order_probability=0.48,
        upsell_accept_probability=0.10,
        click_probability=0.28,
        detail_view_probability=0.18,
        impulse_probability=0.04,
        bad_decision_probability=0.12,
        max_items=2,
    ),
    "STANDARD": Persona(
        name="STANDARD",
        min_budget=16.0,
        max_budget=34.0,
        order_probability=0.68,
        upsell_accept_probability=0.20,
        click_probability=0.42,
        detail_view_probability=0.28,
        impulse_probability=0.08,
        bad_decision_probability=0.10,
        max_items=3,
    ),
    "HIGH_SPENDER": Persona(
        name="HIGH_SPENDER",
        min_budget=30.0,
        max_budget=70.0,
        order_probability=0.82,
        upsell_accept_probability=0.32,
        click_probability=0.50,
        detail_view_probability=0.35,
        impulse_probability=0.16,
        bad_decision_probability=0.08,
        max_items=5,
    ),
    "CHAOTIC": Persona(
        name="CHAOTIC",
        min_budget=8.0,
        max_budget=55.0,
        order_probability=0.56,
        upsell_accept_probability=0.24,
        click_probability=0.58,
        detail_view_probability=0.40,
        impulse_probability=0.26,
        bad_decision_probability=0.34,
        max_items=4,
    ),
}

PERSONA_WEIGHTS = {
    "LOW_SPENDER": 0.22,
    "STANDARD": 0.52,
    "HIGH_SPENDER": 0.16,
    "CHAOTIC": 0.10,
}


@dataclass(frozen=True)
class TableConfig:
    table_id: int
    qr_token: str
    table_public_id: str | None = None


@dataclass(frozen=True)
class RestaurantConfig:
    restaurant_id: int
    name: str
    tables: list[TableConfig]
    weekday_sessions_min: int = 18
    weekday_sessions_max: int = 45
    weekend_sessions_min: int = 55
    weekend_sessions_max: int = 110
    analytics_token: str | None = None
    api_token: str | None = None
    auto_pay_orders: bool = True
    latitude: float | None = None
    longitude: float | None = None


@dataclass(frozen=True)
class SimulationConfig:
    base_url: str
    output_dir: Path
    days: int
    restaurants: list[RestaurantConfig]
    seed: int | None = None
    start_date: date = field(default_factory=date.today)
    request_timeout_seconds: float = 12.0
    sleep_between_sessions_ms: int = 0
    fail_fast: bool = False


@dataclass
class Dish:
    id: int
    name: str
    price: float
    category: str
    available: bool
    recommended: bool
    rank_index: int


@dataclass
class CartItem:
    dish: Dish
    quantity: int
    source: str | None = None
    source_dish_id: int | None = None


@dataclass
class SessionResult:
    simulated_day: date
    restaurant_id: int
    restaurant_name: str
    session_id: str
    persona: str
    table_id: int
    ordered: bool
    revenue: float
    item_count: int
    upsell_accepted: bool
    viewed_count: int
    clicked_count: int
    error: str = ""


class ApiError(RuntimeError):
    pass


class ApiClient:
    def __init__(self, base_url: str, timeout: float = 12.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def get(self, path: str, params: dict[str, Any] | None = None, token: str | None = None) -> Any:
        url = self._url(path, params)
        request = Request(url, headers=self._headers(token), method="GET")
        return self._send(request)

    def post(self, path: str, body: dict[str, Any], token: str | None = None) -> Any:
        data = json.dumps(body).encode("utf-8")
        headers = self._headers(token)
        headers["Content-Type"] = "application/json"
        request = Request(self._url(path), data=data, headers=headers, method="POST")
        return self._send(request)

    def _url(self, path: str, params: dict[str, Any] | None = None) -> str:
        url = f"{self.base_url}{path}"
        if params:
            url = f"{url}?{urlencode(params, doseq=True)}"
        return url

    def _headers(self, token: str | None = None) -> dict[str, str]:
        headers = {"Accept": "application/json", "User-Agent": "waitero-behavior-simulator/1.0"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        return headers

    def _send(self, request: Request) -> Any:
        last_error: Exception | None = None
        for attempt in range(4):
            try:
                with urlopen(request, timeout=self.timeout) as response:
                    payload = response.read()
                    if not payload:
                        return None
                    return json.loads(payload.decode("utf-8"))
            except HTTPError as exc:
                body = exc.read().decode("utf-8", errors="replace")
                if exc.code < 500 or attempt == 3:
                    raise ApiError(f"HTTP {exc.code} {request.full_url}: {body}") from exc
                last_error = exc
            except (URLError, RemoteDisconnected, ConnectionResetError) as exc:
                last_error = exc
                if attempt == 3:
                    raise ApiError(f"Network error {request.full_url}: {exc}") from exc
            except json.JSONDecodeError as exc:
                raise ApiError(f"Invalid JSON from {request.full_url}: {exc}") from exc
            time.sleep(0.15 * (attempt + 1))
        raise ApiError(f"Request failed after retries {request.full_url}: {last_error}")


class BehaviorEngine:
    def __init__(self, rng: random.Random) -> None:
        self.rng = rng

    def choose_persona(self) -> Persona:
        names = list(PERSONA_WEIGHTS)
        weights = [PERSONA_WEIGHTS[name] for name in names]
        return PERSONAS[self.rng.choices(names, weights=weights, k=1)[0]]

    def session_count(self, restaurant: RestaurantConfig, current_day: date) -> int:
        is_weekend = current_day.weekday() >= 5
        if is_weekend:
            low, high = restaurant.weekend_sessions_min, restaurant.weekend_sessions_max
        else:
            low, high = restaurant.weekday_sessions_min, restaurant.weekday_sessions_max
        base = self.rng.randint(low, high)
        noise = self.rng.gauss(1.0, 0.13)
        return max(0, int(round(base * max(0.55, noise))))

    def viewing_window(self, menu: list[Dish], persona: Persona) -> list[Dish]:
        if not menu:
            return []
        min_views = min(len(menu), 4)
        max_views = min(len(menu), 18 if persona.name != "LOW_SPENDER" else 12)
        count = self.rng.randint(min_views, max(min_views, max_views))
        ranked_bias_count = int(count * self.rng.uniform(0.55, 0.82))
        top_pool = menu[: max(count + 4, min(len(menu), 10))]
        random_pool = menu
        selected: list[Dish] = []
        selected.extend(self._sample_unique(top_pool, min(ranked_bias_count, len(top_pool))))
        remaining = [dish for dish in random_pool if dish.id not in {item.id for item in selected}]
        selected.extend(self._sample_unique(remaining, max(0, count - len(selected))))
        self.rng.shuffle(selected)
        return selected

    def clicked_dishes(self, viewed: list[Dish], persona: Persona) -> list[Dish]:
        clicked = [dish for dish in viewed if self.rng.random() < persona.click_probability]
        if viewed and not clicked and self.rng.random() < 0.55:
            clicked.append(self.rng.choice(viewed))
        return clicked

    def build_cart(self, viewed: list[Dish], clicked: list[Dish], persona: Persona) -> list[CartItem]:
        budget = self.rng.uniform(persona.min_budget, persona.max_budget)
        candidates = clicked if clicked else viewed
        candidates = [dish for dish in candidates if dish.available]
        if not candidates or self.rng.random() > persona.order_probability:
            return []

        cart: list[CartItem] = []
        total = 0.0
        max_items = self.rng.randint(1, persona.max_items)
        shuffled = candidates[:]
        self.rng.shuffle(shuffled)

        for dish in sorted(shuffled, key=lambda item: self.preference_score(item, persona), reverse=True):
            if len(cart) >= max_items:
                break
            if total + dish.price > budget and self.rng.random() > persona.impulse_probability:
                continue
            if self.rng.random() < self.purchase_probability(dish, persona, budget, total):
                quantity = 2 if self.rng.random() < self.quantity_two_probability(persona, dish) else 1
                cart.append(CartItem(dish=dish, quantity=quantity))
                total += dish.price * quantity

        if not cart and candidates and self.rng.random() < persona.impulse_probability:
            dish = self.rng.choice(candidates)
            cart.append(CartItem(dish=dish, quantity=1))
        return cart

    def accept_upsell(self, persona: Persona, suggestion: Dish, current_total: float) -> bool:
        affordability = 1.0 if current_total + suggestion.price <= persona.max_budget else 0.55
        category_factor = 1.15 if normalize_category(suggestion.category) in {"beverage", "side", "dessert"} else 0.85
        probability = persona.upsell_accept_probability * affordability * category_factor
        if self.rng.random() < persona.bad_decision_probability:
            probability += self.rng.uniform(-0.12, 0.18)
        return self.rng.random() < clamp(probability, 0.02, 0.62)

    def preference_score(self, dish: Dish, persona: Persona) -> float:
        rank_bias = 1.0 / math.sqrt(dish.rank_index + 1)
        price_penalty = dish.price / max(persona.max_budget, 1.0)
        recommended_bonus = 0.10 if dish.recommended else 0.0
        noise = self.rng.uniform(-0.32, 0.32)
        if self.rng.random() < persona.bad_decision_probability:
            noise += self.rng.uniform(-0.7, 0.7)
        return rank_bias - (0.55 * price_penalty) + recommended_bonus + noise

    def purchase_probability(self, dish: Dish, persona: Persona, budget: float, total: float) -> float:
        remaining = max(budget - total, 0.0)
        affordability = clamp(remaining / max(dish.price, 1.0), 0.15, 1.25)
        base = persona.order_probability * 0.54
        noise = self.rng.uniform(-0.16, 0.18)
        return clamp(base + (0.18 * affordability) + noise, 0.04, 0.88)

    def quantity_two_probability(self, persona: Persona, dish: Dish) -> float:
        cheap = dish.price <= persona.max_budget * 0.22
        return 0.18 + (0.16 if cheap else 0.0) + (0.08 if persona.name == "HIGH_SPENDER" else 0.0)

    def _sample_unique(self, values: list[Dish], count: int) -> list[Dish]:
        if count <= 0 or not values:
            return []
        return self.rng.sample(values, min(count, len(values)))


class Simulator:
    def __init__(self, config: SimulationConfig) -> None:
        self.config = config
        self.rng = random.Random(config.seed)
        self.behavior = BehaviorEngine(self.rng)
        self.api = ApiClient(config.base_url, config.request_timeout_seconds)
        self.sessions: list[SessionResult] = []

    def run(self) -> None:
        self.config.output_dir.mkdir(parents=True, exist_ok=True)
        for day_offset in range(self.config.days):
            current_day = self.config.start_date + timedelta(days=day_offset)
            for restaurant in self.config.restaurants:
                self._simulate_restaurant_day(restaurant, current_day)
        self._write_outputs()

    def _simulate_restaurant_day(self, restaurant: RestaurantConfig, current_day: date) -> None:
        sessions = self.behavior.session_count(restaurant, current_day)
        for index in range(sessions):
            if self.config.sleep_between_sessions_ms > 0:
                time.sleep(self.config.sleep_between_sessions_ms / 1000.0)
            try:
                result = self._simulate_session(restaurant, current_day, index)
            except Exception as exc:  # Keep long simulations running unless fail-fast is requested.
                if self.config.fail_fast:
                    raise
                result = SessionResult(
                    simulated_day=current_day,
                    restaurant_id=restaurant.restaurant_id,
                    restaurant_name=restaurant.name,
                    session_id="ERROR",
                    persona="UNKNOWN",
                    table_id=0,
                    ordered=False,
                    revenue=0.0,
                    item_count=0,
                    upsell_accepted=False,
                    viewed_count=0,
                    clicked_count=0,
                    error=str(exc),
                )
            self.sessions.append(result)

    def _simulate_session(self, restaurant: RestaurantConfig, current_day: date, index: int) -> SessionResult:
        if not restaurant.tables:
            raise ValueError(f"Restaurant {restaurant.restaurant_id} has no configured tables")

        persona = self.behavior.choose_persona()
        table = self.rng.choice(restaurant.tables)
        session_id = f"sim-{restaurant.restaurant_id}-{current_day.isoformat()}-{index}-{uuid.uuid4().hex[:10]}"
        device_id = f"dev-{uuid.uuid4().hex[:18]}"
        fingerprint = f"fp-{uuid.uuid4().hex[:20]}"

        self._register_table_access(restaurant, table, device_id, fingerprint)
        menu = self._load_menu(restaurant.restaurant_id, session_id)
        viewed = self.behavior.viewing_window(menu, persona)
        self._track_many("view_menu_item", restaurant, table, session_id, viewed, {"surface": "menu"})

        clicked = self.behavior.clicked_dishes(viewed, persona)
        self._track_many("click_dish", restaurant, table, session_id, clicked, {"surface": "menu"})

        detail_views = [dish for dish in clicked if self.rng.random() < persona.detail_view_probability]
        self._track_many("view_dish", restaurant, table, session_id, detail_views, {"surface": "detail"})

        cart = self.behavior.build_cart(viewed, clicked, persona)
        for item in cart:
            self._track_event("add_to_cart", restaurant, table, session_id, item.dish.id, {"quantity": item.quantity, "source": "menu"})

        upsell_accepted = self._maybe_apply_upsell(restaurant, table, session_id, cart, persona)
        if not cart:
            return SessionResult(current_day, restaurant.restaurant_id, restaurant.name, session_id, persona.name, table.table_id, False, 0.0, 0, False, len(viewed), len(clicked))

        self._submit_order(restaurant, table, session_id, device_id, fingerprint, cart)
        revenue = sum(item.dish.price * item.quantity for item in cart)
        item_count = sum(item.quantity for item in cart)
        return SessionResult(current_day, restaurant.restaurant_id, restaurant.name, session_id, persona.name, table.table_id, True, revenue, item_count, upsell_accepted, len(viewed), len(clicked))

    def _register_table_access(self, restaurant: RestaurantConfig, table: TableConfig, device_id: str, fingerprint: str) -> None:
        body = {
            "tablePublicId": table.table_public_id,
            "qrToken": table.qr_token,
            "restaurantId": str(restaurant.restaurant_id),
            "tableId": table.table_id,
            "deviceId": device_id,
            "fingerprint": fingerprint,
            "latitude": jitter_coordinate(restaurant.latitude, self.rng),
            "longitude": jitter_coordinate(restaurant.longitude, self.rng),
            "accuracy": self.rng.uniform(8.0, 80.0),
        }
        response = self.api.post("/api/table/access", body)
        if not response or not response.get("allowed"):
            raise ApiError(f"Table access denied for restaurant={restaurant.restaurant_id} table={table.table_id}: {response}")

    def _load_menu(self, restaurant_id: int, session_id: str) -> list[Dish]:
        payload = self.api.get(f"/api/customer/menu/piatti/{restaurant_id}", {"sessionId": session_id})
        if not isinstance(payload, list):
            raise ApiError(f"Menu response is not a list for restaurant={restaurant_id}: {payload}")
        dishes: list[Dish] = []
        for index, raw in enumerate(payload):
            if not isinstance(raw, dict):
                continue
            dish_id = raw.get("id")
            if dish_id is None:
                continue
            dishes.append(Dish(
                id=int(dish_id),
                name=str(raw.get("nome") or f"dish-{dish_id}"),
                price=decimal_to_float(raw.get("prezzo")),
                category=str(raw.get("categoria") or "UNKNOWN"),
                available=raw.get("disponibile") is not False,
                recommended=raw.get("consigliato") is True,
                rank_index=index,
            ))
        return dishes

    def _maybe_apply_upsell(self, restaurant: RestaurantConfig, table: TableConfig, session_id: str, cart: list[CartItem], persona: Persona) -> bool:
        if not cart:
            return False
        current_ids = [item.dish.id for item in cart]
        suggestions = self._load_cart_upsell(restaurant.restaurant_id, session_id, current_ids)
        if not suggestions and cart:
            suggestions = self._load_dish_upsell(restaurant.restaurant_id, session_id, cart[0].dish.id)
        suggestions = [dish for dish in suggestions if dish.id not in set(current_ids) and dish.available]
        if not suggestions:
            return False
        suggestion = self._choose_upsell_candidate(suggestions)
        current_total = sum(item.dish.price * item.quantity for item in cart)
        if not self.behavior.accept_upsell(persona, suggestion, current_total):
            return False
        source_dish_id = self.rng.choice(current_ids)
        cart.append(CartItem(dish=suggestion, quantity=1, source="upsell", source_dish_id=source_dish_id))
        self._track_event("add_to_cart", restaurant, table, session_id, suggestion.id, {"quantity": 1, "source": "upsell", "sourceDishId": source_dish_id})
        return True

    def _load_cart_upsell(self, restaurant_id: int, session_id: str, dish_ids: list[int]) -> list[Dish]:
        if not dish_ids:
            return []
        payload = self.api.get(
            "/api/customer/upsell/cart-suggestions",
            {"restaurantId": restaurant_id, "dishIds": dish_ids, "sessionId": session_id},
        )
        return self._parse_suggestions(payload)

    def _load_dish_upsell(self, restaurant_id: int, session_id: str, dish_id: int) -> list[Dish]:
        payload = self.api.get(f"/api/customer/upsell/{dish_id}", {"restaurantId": restaurant_id, "sessionId": session_id})
        return self._parse_suggestions(payload)

    def _parse_suggestions(self, payload: Any) -> list[Dish]:
        if not isinstance(payload, list):
            return []
        suggestions: list[Dish] = []
        for index, raw in enumerate(payload):
            if not isinstance(raw, dict) or raw.get("id") is None:
                continue
            suggestions.append(Dish(
                id=int(raw["id"]),
                name=str(raw.get("nome") or f"dish-{raw['id']}"),
                price=decimal_to_float(raw.get("prezzo")),
                category=str(raw.get("categoria") or "UNKNOWN"),
                available=raw.get("disponibile") is not False,
                recommended=raw.get("consigliato") is True,
                rank_index=index,
            ))
        return suggestions

    def _choose_upsell_candidate(self, suggestions: list[Dish]) -> Dish:
        if self.rng.random() < 0.72:
            return suggestions[0]
        return self.rng.choice(suggestions[: min(4, len(suggestions))])

    def _submit_order(
        self,
        restaurant: RestaurantConfig,
        table: TableConfig,
        session_id: str,
        device_id: str,
        fingerprint: str,
        cart: list[CartItem],
    ) -> Any:
        body = {
            "token": table.qr_token,
            "restaurantId": str(restaurant.restaurant_id),
            "tableId": table.table_id,
            "deviceId": device_id,
            "fingerprint": fingerprint,
            "sessionId": session_id,
            "noteCucina": self._random_note(),
            "items": [
                {
                    "dishId": item.dish.id,
                    "quantity": item.quantity,
                    "source": item.source,
                    "sourceDishId": item.source_dish_id,
                }
                for item in cart
            ],
        }
        order = self.api.post("/api/customer/orders", body)
        if restaurant.auto_pay_orders and restaurant.api_token and isinstance(order, dict) and order.get("id") is not None:
            return self.api.post(f"/api/orders/{order['id']}/pay", {"paymentMode": "FULL"}, token=restaurant.api_token)
        return order

    def _track_many(self, event_type: str, restaurant: RestaurantConfig, table: TableConfig, session_id: str, dishes: Iterable[Dish], metadata: dict[str, Any]) -> None:
        for dish in dishes:
            self._track_event(event_type, restaurant, table, session_id, dish.id, metadata)

    def _track_event(self, event_type: str, restaurant: RestaurantConfig, table: TableConfig, session_id: str, dish_id: int | None, metadata: dict[str, Any]) -> None:
        body = {
            "eventType": event_type,
            "userId": None,
            "sessionId": session_id,
            "restaurantId": restaurant.restaurant_id,
            "tableId": table.table_id,
            "dishId": dish_id,
            "metadata": metadata,
        }
        self.api.post("/api/events", body)

    def _random_note(self) -> str | None:
        notes = [None, None, None, "", "Senza cipolla", "Pane a parte", "Poco sale", "Ben cotto"]
        return self.rng.choice(notes)

    def _write_outputs(self) -> None:
        self._write_sessions_csv()
        self._write_daily_csv()
        self._write_ab_csv()

    def _write_sessions_csv(self) -> None:
        path = self.config.output_dir / "simulation_sessions.csv"
        with path.open("w", newline="", encoding="utf-8") as file:
            writer = csv.DictWriter(file, fieldnames=list(SessionResult.__dataclass_fields__.keys()))
            writer.writeheader()
            for row in self.sessions:
                writer.writerow({
                    "simulated_day": row.simulated_day.isoformat(),
                    "restaurant_id": row.restaurant_id,
                    "restaurant_name": row.restaurant_name,
                    "session_id": row.session_id,
                    "persona": row.persona,
                    "table_id": row.table_id,
                    "ordered": row.ordered,
                    "revenue": round(row.revenue, 2),
                    "item_count": row.item_count,
                    "upsell_accepted": row.upsell_accepted,
                    "viewed_count": row.viewed_count,
                    "clicked_count": row.clicked_count,
                    "error": row.error,
                })

    def _write_daily_csv(self) -> None:
        path = self.config.output_dir / "simulation_daily_metrics.csv"
        rows: dict[tuple[str, int, str], list[SessionResult]] = {}
        for session in self.sessions:
            rows.setdefault((session.simulated_day.isoformat(), session.restaurant_id, session.restaurant_name), []).append(session)

        with path.open("w", newline="", encoding="utf-8") as file:
            fieldnames = ["day", "restaurant_id", "restaurant_name", "sessions", "orders", "revenue", "aov", "items_per_order", "upsell_orders", "upsell_rate", "error_sessions"]
            writer = csv.DictWriter(file, fieldnames=fieldnames)
            writer.writeheader()
            for (day, restaurant_id, restaurant_name), sessions in sorted(rows.items()):
                orders = [session for session in sessions if session.ordered]
                revenue = sum(session.revenue for session in orders)
                item_count = sum(session.item_count for session in orders)
                upsell_orders = sum(1 for session in orders if session.upsell_accepted)
                writer.writerow({
                    "day": day,
                    "restaurant_id": restaurant_id,
                    "restaurant_name": restaurant_name,
                    "sessions": len(sessions),
                    "orders": len(orders),
                    "revenue": round(revenue, 2),
                    "aov": round(safe_divide(revenue, len(orders)), 2),
                    "items_per_order": round(safe_divide(item_count, len(orders)), 4),
                    "upsell_orders": upsell_orders,
                    "upsell_rate": round(safe_divide(upsell_orders, len(orders)), 4),
                    "error_sessions": sum(1 for session in sessions if session.error),
                })

    def _write_ab_csv(self) -> None:
        path = self.config.output_dir / "simulation_ab_metrics.csv"
        with path.open("w", newline="", encoding="utf-8") as file:
            fieldnames = ["restaurant_id", "variant", "orders", "revenue", "avg_order_value", "items_per_order", "uplift_revenue", "uplift_avg_order_value", "uplift_items_per_order", "status"]
            writer = csv.DictWriter(file, fieldnames=fieldnames)
            writer.writeheader()
            for restaurant in self.config.restaurants:
                if not restaurant.analytics_token:
                    writer.writerow({"restaurant_id": restaurant.restaurant_id, "variant": "UNAVAILABLE", "status": "missing analytics_token"})
                    continue
                try:
                    metrics = self.api.get("/api/analytics/ab-test", token=restaurant.analytics_token)
                    self._write_ab_rows(writer, restaurant.restaurant_id, metrics)
                except Exception as exc:
                    writer.writerow({"restaurant_id": restaurant.restaurant_id, "variant": "UNAVAILABLE", "status": str(exc)})

    def _write_ab_rows(self, writer: csv.DictWriter, restaurant_id: int, metrics: Any) -> None:
        if not isinstance(metrics, dict):
            writer.writerow({"restaurant_id": restaurant_id, "variant": "UNAVAILABLE", "status": f"invalid analytics response: {metrics}"})
            return
        variant_a = metrics.get("A") or metrics.get("variantA") or {}
        variant_b = metrics.get("B") or metrics.get("variantB") or {}
        uplift = metrics.get("uplift") or {}
        for label, row in (("A", variant_a), ("B", variant_b)):
            writer.writerow({
                "restaurant_id": restaurant_id,
                "variant": label,
                "orders": row.get("orders"),
                "revenue": row.get("revenue"),
                "avg_order_value": row.get("avgOrderValue") or row.get("avg_order_value"),
                "items_per_order": row.get("itemsPerOrder") or row.get("items_per_order"),
                "uplift_revenue": uplift.get("revenue") if label == "A" else "",
                "uplift_avg_order_value": uplift.get("avgOrderValue") if label == "A" else "",
                "uplift_items_per_order": uplift.get("itemsPerOrder") if label == "A" else "",
                "status": "ok",
            })


def normalize_category(category: str) -> str:
    value = category.strip().lower()
    if any(token in value for token in ("bev", "drink", "bib", "vino", "birra", "acqua")):
        return "beverage"
    if any(token in value for token in ("cont", "side", "frit", "patat")):
        return "side"
    if any(token in value for token in ("dess", "dol", "sweet")):
        return "dessert"
    return "main"


def decimal_to_float(value: Any) -> float:
    if value is None:
        return 0.0
    try:
        return float(Decimal(str(value)))
    except (InvalidOperation, ValueError):
        return 0.0


def safe_divide(numerator: float, denominator: float) -> float:
    return 0.0 if denominator == 0 else numerator / denominator


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def jitter_coordinate(value: float | None, rng: random.Random) -> float | None:
    if value is None:
        return None
    return value + rng.uniform(-0.00025, 0.00025)


def parse_config(path: Path | None, args: argparse.Namespace) -> SimulationConfig:
    raw: dict[str, Any] = {}
    if path:
        raw = json.loads(path.read_text(encoding="utf-8"))

    restaurants = [parse_restaurant(item) for item in raw.get("restaurants", [])]
    if not restaurants:
        raise ValueError("Config must define exactly 3 restaurants with table qr tokens.")
    if len(restaurants) != 3:
        raise ValueError(f"Config must define exactly 3 restaurants; found {len(restaurants)}.")

    return SimulationConfig(
        base_url=args.base_url or raw.get("base_url", "http://localhost:8080"),
        output_dir=Path(args.output_dir or raw.get("output_dir", "simulation_output")),
        days=args.days or int(raw.get("days", 30)),
        restaurants=restaurants,
        seed=args.seed if args.seed is not None else raw.get("seed"),
        start_date=parse_date(args.start_date or raw.get("start_date")) if (args.start_date or raw.get("start_date")) else date.today(),
        request_timeout_seconds=float(raw.get("request_timeout_seconds", 12.0)),
        sleep_between_sessions_ms=int(raw.get("sleep_between_sessions_ms", 0)),
        fail_fast=bool(args.fail_fast or raw.get("fail_fast", False)),
    )


def parse_restaurant(raw: dict[str, Any]) -> RestaurantConfig:
    tables = [TableConfig(
        table_id=int(item["table_id"]),
        qr_token=str(item["qr_token"]),
        table_public_id=item.get("table_public_id"),
    ) for item in raw.get("tables", [])]
    return RestaurantConfig(
        restaurant_id=int(raw["restaurant_id"]),
        name=str(raw.get("name") or f"restaurant-{raw['restaurant_id']}"),
        tables=tables,
        weekday_sessions_min=int(raw.get("weekday_sessions_min", 18)),
        weekday_sessions_max=int(raw.get("weekday_sessions_max", 45)),
        weekend_sessions_min=int(raw.get("weekend_sessions_min", 55)),
        weekend_sessions_max=int(raw.get("weekend_sessions_max", 110)),
        analytics_token=raw.get("analytics_token"),
        api_token=raw.get("api_token") or raw.get("analytics_token"),
        auto_pay_orders=bool(raw.get("auto_pay_orders", True)),
        latitude=raw.get("latitude"),
        longitude=raw.get("longitude"),
    )


def parse_date(value: str) -> date:
    return date.fromisoformat(value)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Simulate realistic Waitero customer behavior against a real backend API.")
    parser.add_argument("--config", type=Path, help="JSON config with restaurants, table qr tokens and optional analytics tokens.")
    parser.add_argument("--base-url", help="Backend base URL. Overrides config base_url.")
    parser.add_argument("--output-dir", help="Output directory for CSV files. Overrides config output_dir.")
    parser.add_argument("--days", type=int, help="Number of simulated days. Defaults to 30.")
    parser.add_argument("--start-date", help="First simulated date in YYYY-MM-DD format. Defaults to today.")
    parser.add_argument("--seed", type=int, help="Random seed for reproducible noisy simulations.")
    parser.add_argument("--fail-fast", action="store_true", help="Stop on the first failed session instead of recording an error row.")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        config = parse_config(args.config, args)
        simulator = Simulator(config)
        simulator.run()
    except Exception as exc:
        print(f"simulation failed: {exc}", file=sys.stderr)
        return 1
    print(f"simulation complete: {config.output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())






